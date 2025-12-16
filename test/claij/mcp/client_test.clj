(ns claij.mcp.client-test
  "Unit tests for MCP client API.
   
   Tests the thin client layer that preserves JSON-RPC format."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.core.async :refer [chan >!! <!! timeout alts!!]]
   [clojure.data.json :as json]
   [claij.mcp.client :as client]
   [claij.mcp.bridge :as bridge]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn mock-bridge
  "Creates a mock bridge for testing."
  []
  {:pending (atom {})
   :input-chan (chan 100)
   :output-chan (chan 100)
   :stop (fn [] nil)})

;; =============================================================================
;; call-batch Tests
;; =============================================================================

(deftest call-batch-empty-test
  (testing "call-batch with empty requests returns empty vector"
    (let [bridge (mock-bridge)]
      (is (= [] (client/call-batch bridge []))))))

(deftest call-batch-preserves-format-test
  (testing "call-batch preserves JSON-RPC format"
    (let [bridge (mock-bridge)
          request {"jsonrpc" "2.0"
                   "id" "test-1"
                   "method" "tools/call"
                   "params" {"name" "calc" "arguments" {"x" 1}}}]

      ;; Send request (async - will block waiting for response)
      (future
        (Thread/sleep 10)
        ;; Deliver response to pending promise
        (when-let [{:keys [promise]} (get @(:pending bridge) "test-1")]
          (deliver promise {"jsonrpc" "2.0"
                            "id" "test-1"
                            "result" {"content" [{"type" "text" "text" "42"}]}})))

      (let [responses (client/call-batch bridge [request] {:timeout-ms 1000})]
        (is (= 1 (count responses)))
        (is (= "test-1" (get (first responses) "id")))
        (is (contains? (first responses) "result"))))))

(deftest call-batch-timeout-test
  (testing "call-batch returns timeout error on timeout"
    (let [bridge (mock-bridge)
          request {"jsonrpc" "2.0"
                   "id" "timeout-test"
                   "method" "tools/call"
                   "params" {"name" "slow_tool"}}]

      ;; Don't deliver any response - let it timeout
      (let [responses (client/call-batch bridge [request] {:timeout-ms 50})]
        (is (= 1 (count responses)))
        (is (= "timeout-test" (get (first responses) "id")))
        (is (= "timeout" (get-in (first responses) ["error" "message"])))))))

(deftest call-batch-multiple-test
  (testing "call-batch handles multiple requests"
    (let [bridge (mock-bridge)
          requests [{"jsonrpc" "2.0" "id" "req-1" "method" "tools/call" "params" {"name" "a"}}
                    {"jsonrpc" "2.0" "id" "req-2" "method" "tools/call" "params" {"name" "b"}}
                    {"jsonrpc" "2.0" "id" "req-3" "method" "tools/call" "params" {"name" "c"}}]]

      ;; Deliver responses (out of order to test correlation)
      (future
        (Thread/sleep 10)
        (when-let [{:keys [promise]} (get @(:pending bridge) "req-2")]
          (deliver promise {"jsonrpc" "2.0" "id" "req-2" "result" {"value" "two"}}))
        (Thread/sleep 5)
        (when-let [{:keys [promise]} (get @(:pending bridge) "req-1")]
          (deliver promise {"jsonrpc" "2.0" "id" "req-1" "result" {"value" "one"}}))
        (Thread/sleep 5)
        (when-let [{:keys [promise]} (get @(:pending bridge) "req-3")]
          (deliver promise {"jsonrpc" "2.0" "id" "req-3" "result" {"value" "three"}})))

      (let [responses (client/call-batch bridge requests {:timeout-ms 1000})]
        (is (= 3 (count responses)))
        ;; Responses should be in request order
        (is (= "req-1" (get (nth responses 0) "id")))
        (is (= "req-2" (get (nth responses 1) "id")))
        (is (= "req-3" (get (nth responses 2) "id")))
        ;; Values should match
        (is (= "one" (get-in (nth responses 0) ["result" "value"])))
        (is (= "two" (get-in (nth responses 1) ["result" "value"])))
        (is (= "three" (get-in (nth responses 2) ["result" "value"])))))))

;; =============================================================================
;; call-one Tests
;; =============================================================================

(deftest call-one-test
  (testing "call-one returns single response (not vector)"
    (let [bridge (mock-bridge)
          request {"jsonrpc" "2.0"
                   "id" "single"
                   "method" "tools/call"
                   "params" {"name" "tool"}}]

      (future
        (Thread/sleep 10)
        (when-let [{:keys [promise]} (get @(:pending bridge) "single")]
          (deliver promise {"jsonrpc" "2.0" "id" "single" "result" {"ok" true}})))

      (let [response (client/call-one bridge request {:timeout-ms 1000})]
        (is (map? response)) ;; Not a vector
        (is (= "single" (get response "id")))
        (is (= true (get-in response ["result" "ok"])))))))

;; =============================================================================
;; ping Tests
;; =============================================================================

(deftest ping-success-test
  (testing "ping returns true when server responds"
    (let [bridge (mock-bridge)]

      (future
        (Thread/sleep 10)
        (when-let [{:keys [promise]} (get @(:pending bridge) "ping")]
          (deliver promise {"jsonrpc" "2.0" "id" "ping" "result" {"tools" []}})))

      (is (true? (client/ping bridge {:timeout-ms 1000}))))))

(deftest ping-timeout-test
  (testing "ping returns false on timeout"
    (let [bridge (mock-bridge)]
      ;; Don't deliver response
      (is (false? (client/ping bridge {:timeout-ms 50}))))))

;; =============================================================================
;; send-notification Tests
;; =============================================================================

(deftest send-notification-test
  (testing "send-notification writes to input channel"
    (let [bridge (mock-bridge)]

      (client/send-notification bridge "notifications/initialized" {})

      (let [[msg _] (alts!! [(:input-chan bridge) (timeout 100)])]
        (is (some? msg))
        (let [parsed (json/read-str msg)]
          (is (= "2.0" (get parsed "jsonrpc")))
          (is (= "notifications/initialized" (get parsed "method")))
          (is (nil? (get parsed "id")))))))) ;; Notifications have no id
