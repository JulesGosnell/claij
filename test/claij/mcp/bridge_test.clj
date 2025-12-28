(ns claij.mcp.bridge-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.core.async :refer [chan go-loop <! >!! <!! timeout alts!!]]
   [clojure.data.json :as json]
   [claij.mcp.bridge :as bridge :refer [start-process-bridge json-string? start-mcp-bridge]]))

(deftest bridge-test

  (testing "start-process-bridge:"
    (testing "Process read and echo sequence"
      (let [input-chan (chan)
            output-chan (timeout 200)
            stop (start-process-bridge "bash" [] input-chan output-chan)]
        (>!! input-chan "read x")
        (>!! input-chan "x")
        (>!! input-chan "echo $x")
        (is (= "x" (<!! output-chan)) "Should receive 'x' after read x and echo")
        (>!! input-chan "read y")
        (>!! input-chan "y")
        (>!! input-chan "echo $y")
        (is (= "y" (<!! output-chan)) "Should receive 'y' after read y and echo")
        (stop)))

    (testing "MCP agent empty input"
      (let [input-chan (chan)
            output-chan (timeout 200)
            stop (start-process-bridge "bash" [] input-chan output-chan)]
        (>!! input-chan "")
        (>!! input-chan "echo hello")
        (is (= "hello" (<!! output-chan)) "Empty input should not crash, echo should work")
        (stop)))

    (testing "Process stop immediately"
      (let [input-chan (chan)
            output-chan (chan)
            stop (start-process-bridge "bash" [] input-chan output-chan)]
        (stop)
        (is (nil? (<!! (timeout 200))) "Stop should clean up without errors")))

    (testing "Process multiple output lines"
      (let [input-chan (chan)
            output-chan (chan)
            outputs (atom [])
            stop (start-process-bridge "bash" [] input-chan output-chan)]
        (go-loop []
          (when-some [msg (<! output-chan)]
            (swap! outputs conj msg)
            (recur)))
        (>!! input-chan "ls")
        (<!! (timeout 200))
        (is (pos? (count @outputs)) "Should receive at least one line from ls")
        (stop))))

  (testing "json-string?"
    (testing "valid JSON objects"
      (is (json-string? "{}") "empty object")
      (is (json-string? "{\"key\": \"value\"}") "simple object")
      (is (json-string? "  {\"key\": 123}") "leading whitespace")
      (is (json-string? "\t{\"nested\": {}}") "tab whitespace")
      (is (json-string? "\n{\"array\": [1,2,3]}") "newline whitespace"))

    (testing "invalid JSON (not objects)"
      (is (not (json-string? "foo")) "plain string")
      (is (not (json-string? "")) "empty string")
      (is (not (json-string? "[]")) "array")
      (is (not (json-string? "123")) "number")
      (is (not (json-string? "null")) "null")
      (is (not (json-string? "\"string\"")) "quoted string")))

  (testing "start-mcp-bridge"
    (testing "valid stdio config"
      ;; Note: Uses string keys to match code expectations
      (let [input-chan (chan)
            output-chan (chan)
            stop (start-mcp-bridge {"command" "echo" "args" ["hello"] "transport" "stdio"}
                                   input-chan output-chan)]
        (is (fn? stop) "Should return a stop function")
        (stop)))

    (testing "invalid config - empty command"
      (is (thrown? IllegalArgumentException
                   (start-mcp-bridge {"command" "" "args" [] "transport" "stdio"} (chan) (chan)))
          "Empty command should throw"))

    (testing "invalid config - nil command"
      (is (thrown? IllegalArgumentException
                   (start-mcp-bridge {"command" nil "args" [] "transport" "stdio"} (chan) (chan)))
          "Nil command should throw"))

    (testing "invalid config - non-vector args"
      (is (thrown? IllegalArgumentException
                   (start-mcp-bridge {"command" "bash" "args" "not-a-vector" "transport" "stdio"} (chan) (chan)))
          "Non-vector args should throw"))

    (testing "nil args is allowed"
      (let [input-chan (chan)
            output-chan (chan)
            stop (start-mcp-bridge {"command" "echo" "args" nil "transport" "stdio"}
                                   input-chan output-chan)]
        (is (fn? stop) "nil args should be allowed")
        (stop)))

    (testing "unsupported transport throws"
      (is (thrown? IllegalArgumentException
                   (start-mcp-bridge {"command" "bash" "args" [] "transport" "http"} (chan) (chan)))
          "Unsupported transport should throw"))))

;; =============================================================================
;; ID Correlation Tests
;; =============================================================================

(defn mock-bridge
  "Creates a mock bridge for testing correlation logic."
  []
  {:pending (atom {})
   :input-chan (chan 100)
   :output-chan (chan 100)
   :stop (fn [] nil)})

(deftest pending-count-test
  (testing "pending-count returns correct count"
    (let [bridge (mock-bridge)]
      (is (= 0 (bridge/pending-count bridge)))

      ;; Manually add to pending
      (swap! (:pending bridge) assoc 1 {:promise (promise) :timestamp 0})
      (is (= 1 (bridge/pending-count bridge)))

      (swap! (:pending bridge) assoc 2 {:promise (promise) :timestamp 0})
      (is (= 2 (bridge/pending-count bridge)))

      (swap! (:pending bridge) dissoc 1)
      (is (= 1 (bridge/pending-count bridge))))))

(deftest send-request-tracking-test
  (testing "send-request adds to pending"
    (let [bridge (mock-bridge)
          request {"id" "test-123" "method" "tools/call"}]
      (is (= 0 (bridge/pending-count bridge)))

      (bridge/send-request bridge request)

      (is (= 1 (bridge/pending-count bridge)))
      (is (contains? @(:pending bridge) "test-123"))
      (is (= request (get-in @(:pending bridge) ["test-123" :request])))))

  (testing "send-request throws without id"
    (let [bridge (mock-bridge)
          request {"method" "tools/call"}]
      (is (thrown? IllegalArgumentException
                   (bridge/send-request bridge request))))))

(deftest send-batch-tracking-test
  (testing "send-batch tracks all requests"
    (let [bridge (mock-bridge)
          requests [{"id" "req-1" "method" "tools/call"}
                    {"id" "req-2" "method" "tools/call"}
                    {"id" "req-3" "method" "tools/call"}]]
      (is (= 0 (bridge/pending-count bridge)))

      (let [promises (bridge/send-batch bridge requests)]
        (is (= 3 (count promises)))
        (is (= 3 (bridge/pending-count bridge)))
        (is (every? #(contains? promises %) ["req-1" "req-2" "req-3"]))))))

(deftest await-response-timeout-test
  (testing "await-response returns timeout error on timeout"
    (let [p (promise)
          result (bridge/await-response p "test-id" 50)]
      (is (= "test-id" (get result "id")))
      (is (= "timeout" (get-in result ["error" "message"])))))

  (testing "await-response returns value when delivered"
    (let [p (promise)
          response {"id" "test-id" "result" {"value" 42}}
          _ (future (Thread/sleep 10) (deliver p response))
          result (bridge/await-response p "test-id" 1000)]
      (is (= response result)))))

(deftest await-responses-test
  (testing "await-responses waits for all with shared deadline"
    (let [p1 (promise)
          p2 (promise)
          p3 (promise)
          promises {"id-1" p1 "id-2" p2 "id-3" p3}

          ;; Deliver some responses
          _ (deliver p1 {"id" "id-1" "result" "one"})
          _ (deliver p2 {"id" "id-2" "result" "two"})
          ;; p3 will timeout

          results (bridge/await-responses promises 100)]

      (is (= {"id" "id-1" "result" "one"} (get results "id-1")))
      (is (= {"id" "id-2" "result" "two"} (get results "id-2")))
      (is (= "timeout" (get-in results ["id-3" "error" "message"])))))

  (testing "await-responses handles all delivered"
    (let [p1 (promise)
          p2 (promise)
          promises {"a" p1 "b" p2}
          _ (deliver p1 {"id" "a" "result" 1})
          _ (deliver p2 {"id" "b" "result" 2})
          results (bridge/await-responses promises 1000)]
      (is (= {"id" "a" "result" 1} (get results "a")))
      (is (= {"id" "b" "result" 2} (get results "b"))))))

(deftest clear-stale-requests-test
  (testing "clear-stale-requests removes old requests"
    (let [bridge (mock-bridge)
          now (System/currentTimeMillis)
          old-time (- now 10000)
          new-time (- now 100)]

      (swap! (:pending bridge) assoc
             "old-1" {:promise (promise) :timestamp old-time :request {"id" "old-1"}}
             "old-2" {:promise (promise) :timestamp old-time :request {"id" "old-2"}}
             "new-1" {:promise (promise) :timestamp new-time :request {"id" "new-1"}})

      (is (= 3 (bridge/pending-count bridge)))

      (let [cleared (bridge/clear-stale-requests bridge 5000)]
        (is (= 2 cleared))
        (is (= 1 (bridge/pending-count bridge)))
        (is (contains? @(:pending bridge) "new-1"))
        (is (not (contains? @(:pending bridge) "old-1")))
        (is (not (contains? @(:pending bridge) "old-2"))))))

  (testing "clear-stale-requests delivers error to stale promises"
    (let [bridge (mock-bridge)
          old-promise (promise)
          old-time (- (System/currentTimeMillis) 10000)]

      (swap! (:pending bridge) assoc
             "stale" {:promise old-promise :timestamp old-time :request {"id" "stale"}})

      (bridge/clear-stale-requests bridge 5000)

      (is (realized? old-promise))
      (is (= "stale" (get-in @old-promise ["error" "message"]))))))

(deftest send-request-writes-to-channel-test
  (testing "send-request writes JSON to input channel"
    (let [bridge (mock-bridge)
          request {"id" "chan-test" "method" "test/method" "params" {"x" 1}}]

      (bridge/send-request bridge request)

      (let [[msg _] (alts!! [(:input-chan bridge) (timeout 100)])]
        (is (some? msg))
        (is (string? msg))
        (let [parsed (json/read-str msg)]
          (is (= "chan-test" (get parsed "id")))
          (is (= "test/method" (get parsed "method"))))))))

(deftest drain-notifications-test
  (testing "drain-notifications collects messages from output channel"
    (let [bridge (mock-bridge)]
      ;; Put some messages on the output channel
      (>!! (:output-chan bridge) {"jsonrpc" "2.0" "method" "notification/1"})
      (>!! (:output-chan bridge) {"jsonrpc" "2.0" "method" "notification/2"})

      (let [notifications (bridge/drain-notifications bridge)]
        (is (= 2 (count notifications)))
        (is (= "notification/1" (get (first notifications) "method")))
        (is (= "notification/2" (get (second notifications) "method"))))))

  (testing "drain-notifications returns empty vector when no messages"
    (let [bridge (mock-bridge)]
      (is (= [] (bridge/drain-notifications bridge))))))

(deftest default-mcp-config-test
  (testing "default-mcp-config has expected structure"
    (is (map? bridge/default-mcp-config))
    (is (= "bash" (get bridge/default-mcp-config "command")))
    (is (vector? (get bridge/default-mcp-config "args")))
    (is (= "stdio" (get bridge/default-mcp-config "transport")))))

(deftest send-and-await-test
  (testing "send-and-await sends requests and returns responses vector"
    (let [bridge (mock-bridge)
          requests [{"id" "saa-1" "method" "test/call"}
                    {"id" "saa-2" "method" "test/call"}]]

      ;; send-and-await returns a vector of responses in same order as requests
      ;; Since no responses are delivered, all should timeout
      (let [results (bridge/send-and-await bridge requests 100)]
        (is (vector? results))
        (is (= 2 (count results)))
        ;; Both should timeout since we didn't deliver responses
        (is (= "timeout" (get-in (first results) ["error" "message"])))
        (is (= "timeout" (get-in (second results) ["error" "message"])))))))
