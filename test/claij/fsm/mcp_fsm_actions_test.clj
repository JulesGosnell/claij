(ns claij.fsm.mcp-fsm-actions-test
  "Unit tests for MCP FSM action implementations.
   Tests action logic directly - no FSM machinery needed."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clojure.tools.logging :as log]
   [clojure.core.async :refer [chan >!! <!! close!]]
   [claij.mcp.bridge :as bridge]
   [claij.fsm.mcp-fsm :refer [wrap unwrap take! hack
                              start-action shed-action init-action
                              service-action cache-action mcp-end-action
                              check-cache-and-continue]]))

;;------------------------------------------------------------------------------
;; Test Fixtures
;;------------------------------------------------------------------------------

(defn quiet-logging [f]
  (with-redefs [log/log* (fn [& _])]
    (f)))

(use-fixtures :each quiet-logging)

;;------------------------------------------------------------------------------
;; Helper Function Tests
;;------------------------------------------------------------------------------

(deftest wrap-test
  (testing "3-arity creates map with id, document, message"
    (let [result (wrap ["from" "to"] "doc" {"m" 1})]
      (is (= ["from" "to"] (get result "id")))
      (is (= "doc" (get result "document")))
      (is (= {"m" 1} (get result "message")))))

  (testing "2-arity creates map without document"
    (let [result (wrap ["a" "b"] {"x" 2})]
      (is (= ["a" "b"] (get result "id")))
      (is (= {"x" 2} (get result "message")))
      (is (not (contains? result "document"))))))

(deftest unwrap-test
  (testing "extracts message"
    (is (= {"foo" "bar"} (unwrap {"message" {"foo" "bar"}}))))

  (testing "returns nil for missing message"
    (is (nil? (unwrap {"id" "test"})))))

(deftest take!-test
  (testing "returns value from channel"
    (let [c (chan 1)]
      (>!! c "hello")
      (is (= "hello" (take! c 100 :default)))))

  (testing "returns default on timeout"
    (let [c (chan)]
      (is (= :timed-out (take! c 10 :timed-out))))))

(deftest hack-test
  (testing "passes through normal messages"
    (let [c (chan 1)]
      (>!! c {"method" "tools/call"})
      (is (= {"method" "tools/call"} (hack c)))))

  (testing "sheds list_changed and returns next"
    (let [c (chan 3)]
      (>!! c {"method" "notifications/tools/list_changed"})
      (>!! c {"method" "notifications/prompts/list_changed"})
      (>!! c {"id" 1 "result" {}})
      (is (= {"id" 1 "result" {}} (hack c))))))

;;------------------------------------------------------------------------------
;; start-action Tests
;;------------------------------------------------------------------------------

(deftest start-action-test
  (testing "initializes bridge and transitions to shedding on init message"
    (let [result (atom nil)
          handler (fn [ctx event] (reset! result {:ctx ctx :event event}))
          ;; Mock bridge - puts init response on output channel
          mock-bridge (fn [_config _ic oc]
                        ;; Put init response (as JSON string - transducer parses it)
                        (>!! oc "{\"method\":\"initialize\",\"id\":0}")
                        ;; Return stop fn
                        (fn [] nil))
          f2 (start-action {} {} {} {})
          event {"document" "test-doc"}]
      (with-redefs [bridge/start-mcp-bridge mock-bridge]
        (f2 {} event [] handler))

      ;; Check transition
      (is (= ["starting" "shedding"] (get-in @result [:event "id"])))
      (is (= "test-doc" (get-in @result [:event "document"])))
      (is (= "initialize" (get-in @result [:event "message" "method"])))

      ;; Check context setup
      (is (some? (get-in @result [:ctx :mcp/bridge])))
      (is (= 0 (get-in @result [:ctx :mcp/request-id])))
      (is (= "test-doc" (get-in @result [:ctx :mcp/document])))
      (is (some? (get-in @result [:ctx :malli/registry])))
      (is (some? (get-in @result [:ctx :id->schema])))))

  (testing "uses default init-request on timeout"
    (let [result (atom nil)
          handler (fn [ctx event] (reset! result {:ctx ctx :event event}))
          ;; Mock bridge - doesn't put anything (simulates timeout)
          mock-bridge (fn [_config _ic _oc]
                        (fn [] nil))
          f2 (start-action {} {} {} {})
          event {"document" "doc"}]
      (with-redefs [bridge/start-mcp-bridge mock-bridge]
        (f2 {} event [] handler))

      ;; Should still transition with default init-request
      (is (= ["starting" "shedding"] (get-in @result [:event "id"])))))

  (testing "uses init-request when server sends notification first"
    (let [result (atom nil)
          handler (fn [ctx event] (reset! result {:ctx ctx :event event}))
          ;; Mock bridge - sends a notification instead of init
          mock-bridge (fn [_config _ic oc]
                        (>!! oc "{\"method\":\"notifications/tools/list_changed\"}")
                        (fn [] nil))
          f2 (start-action {} {} {} {})
          event {"document" "doc"}]
      (with-redefs [bridge/start-mcp-bridge mock-bridge]
        (f2 {} event [] handler))

      ;; Should use our init-request (which has :method key from initialise-request)
      (is (= ["starting" "shedding"] (get-in @result [:event "id"]))))))

;;------------------------------------------------------------------------------
;; shed-action Tests
;;------------------------------------------------------------------------------

(deftest shed-action-test
  (testing "sends message to bridge and transitions to initing"
    (let [result (atom nil)
          handler (fn [ctx event] (reset! result {:ctx ctx :event event}))
          ;; Create mock channels
          ic (chan 10)
          oc (chan 10)
          ;; Put init response on output
          _ (>!! oc {"id" 0 "result" {"protocolVersion" "2025-06-18"}})

          f2 (shed-action {} {} {} {})
          context {:mcp/bridge {:input ic :output oc}}
          event {"document" "doc"
                 "message" {"method" "initialize" "id" 0}}]
      (f2 context event [] handler)

      ;; Check message was sent to input channel
      (is (= {"method" "initialize" "id" 0} (<!! ic)))

      ;; Check transition
      (is (= ["shedding" "initing"] (get-in @result [:event "id"])))
      (is (= "doc" (get-in @result [:event "document"])))
      (is (= {"id" 0 "result" {"protocolVersion" "2025-06-18"}}
             (get-in @result [:event "message"])))

      (close! ic)
      (close! oc))))

;;------------------------------------------------------------------------------
;; init-action Tests
;;------------------------------------------------------------------------------

(deftest init-action-test
  (testing "extracts capabilities and transitions to servicing"
    (let [result (atom nil)
          handler (fn [ctx event] (reset! result {:ctx ctx :event event}))
          ;; Create the action's runtime fn
          f2 (init-action {} {} {} {})
          ;; Input event with capabilities
          event {"document" "test-doc"
                 "message" {"result" {"capabilities" {"tools" {}
                                                      "prompts" {}}}}}]
      ;; Call action
      (f2 {} event [] handler)

      ;; Check output
      (is (= ["initing" "servicing"] (get-in @result [:event "id"])))
      (is (= "test-doc" (get-in @result [:event "document"])))
      ;; initialised-notification uses keyword keys
      (is (= "notifications/initialized" (get-in @result [:event "message" :method])))))

  (testing "handles missing capabilities gracefully"
    (let [result (atom nil)
          handler (fn [ctx event] (reset! result {:ctx ctx :event event}))
          f2 (init-action {} {} {} {})
          event {"document" "doc" "message" {}}]
      (f2 {} event [] handler)
      (is (= ["initing" "servicing"] (get-in @result [:event "id"]))))))

;;------------------------------------------------------------------------------
;; check-cache-and-continue Tests
;;------------------------------------------------------------------------------

(deftest check-cache-and-continue-test
  (testing "requests missing tools"
    (let [result (atom nil)
          handler (fn [ctx event] (reset! result {:ctx ctx :event event}))
          context {"state" {"tools" nil "prompts" [] "resources" []}
                   :mcp/request-id 0}]
      (check-cache-and-continue context handler)

      (is (= ["caching" "servicing"] (get-in @result [:event "id"])))
      (is (= "tools/list" (get-in @result [:event "message" "method"])))
      (is (= 1 (:mcp/request-id (:ctx @result))))))

  (testing "requests missing prompts when tools populated"
    (let [result (atom nil)
          handler (fn [ctx event] (reset! result {:ctx ctx :event event}))
          context {"state" {"tools" [{"name" "bash"}] "prompts" nil "resources" []}
                   :mcp/request-id 5}]
      (check-cache-and-continue context handler)

      (is (= "prompts/list" (get-in @result [:event "message" "method"])))
      (is (= 6 (:mcp/request-id (:ctx @result))))))

  (testing "requests missing resources"
    (let [result (atom nil)
          handler (fn [ctx event] (reset! result {:ctx ctx :event event}))
          context {"state" {"tools" [{}] "prompts" [{}] "resources" nil}
                   :mcp/request-id 0}]
      (check-cache-and-continue context handler)

      (is (= "resources/list" (get-in @result [:event "message" "method"])))))

  (testing "goes to llm when cache complete"
    (let [result (atom nil)
          handler (fn [ctx event] (reset! result {:ctx ctx :event event}))
          context {"state" {"tools" [{}] "prompts" [{}] "resources" [{}]}
                   :mcp/document "my-doc"}]
      (check-cache-and-continue context handler)

      (is (= ["caching" "llm"] (get-in @result [:event "id"])))
      (is (= "my-doc" (get-in @result [:event "document"]))))))

;;------------------------------------------------------------------------------
;; cache-action Tests
;;------------------------------------------------------------------------------

(deftest cache-action-test
  (testing "refreshes cache on list response"
    (let [result (atom nil)
          handler (fn [ctx event] (reset! result {:ctx ctx :event event}))
          f2 (cache-action {} {} {"id" ["servicing" "caching"]} {})
          context {"state" {"tools" nil "prompts" [{}] "resources" [{}]}
                   :mcp/request-id 0
                   :mcp/document "doc"}
          ;; Simulate tools/list response
          event {"message" {"result" {"tools" [{"name" "bash"}]}}}]
      (f2 context event [] handler)

      ;; Should have updated tools in cache
      (let [new-cache (get-in @result [:ctx "state"])]
        (is (= [{"name" "bash"}] (get new-cache "tools"))))))

  (testing "handles list_changed notification"
    (let [result (atom nil)
          handler (fn [ctx event] (reset! result {:ctx ctx :event event}))
          f2 (cache-action {} {} {"id" ["servicing" "caching"]} {})
          context {"state" {"tools" [{"name" "old"}] "prompts" [] "resources" []}
                   :mcp/request-id 0}
          ;; list_changed notification
          event {"message" {"method" "notifications/tools/list_changed"}}]
      (f2 context event [] handler)

      ;; Should invalidate tools and request refresh
      (is (= ["caching" "servicing"] (get-in @result [:event "id"])))
      (is (= "tools/list" (get-in @result [:event "message" "method"])))
      (is (nil? (get-in @result [:ctx "state" "tools"])) "tools should be invalidated")))

  (testing "checks cache on initial entry"
    (let [result (atom nil)
          handler (fn [ctx event] (reset! result {:ctx ctx :event event}))
          f2 (cache-action {} {} {"id" ["initing" "caching"]} {})
          context {"state" {"tools" nil "prompts" [] "resources" []}
                   :mcp/request-id 0}
          event {"message" {}}]
      (f2 context event [] handler)

      ;; Should request missing tools
      (is (= ["caching" "servicing"] (get-in @result [:event "id"])))
      (is (= "tools/list" (get-in @result [:event "message" "method"]))))))

;;------------------------------------------------------------------------------
;; service-action Tests
;;------------------------------------------------------------------------------

(deftest service-action-test
  (testing "routes llm response back to llm"
    (let [result (atom nil)
          handler (fn [ctx event] (reset! result {:ctx ctx :event event}))
          ;; Mock channels
          ic (chan 10)
          oc (chan 10)
          ;; Put response on output channel
          _ (>!! oc {"id" 1 "result" {"content" [{"type" "text" "text" "done"}]}})

          f2 (service-action {} {} {"id" ["llm" "servicing"]} {})
          context {:mcp/bridge {:input ic :output oc}}
          event {"message" {"method" "tools/call"} "document" "doc"}]
      (f2 context event [] handler)

      (is (= ["servicing" "llm"] (get-in @result [:event "id"])))
      (is (= "doc" (get-in @result [:event "document"])))
      (close! ic)
      (close! oc)))

  (testing "routes caching response back to caching"
    (let [result (atom nil)
          handler (fn [ctx event] (reset! result {:ctx ctx :event event}))
          ic (chan 10)
          oc (chan 10)
          _ (>!! oc {"id" 1 "result" {"tools" []}})

          f2 (service-action {} {} {"id" ["caching" "servicing"]} {})
          context {:mcp/bridge {:input ic :output oc}}
          event {"message" {"method" "tools/list"}}]
      (f2 context event [] handler)

      (is (= ["servicing" "caching"] (get-in @result [:event "id"])))
      (close! ic)
      (close! oc)))

  (testing "routes notification to caching"
    (let [result (atom nil)
          handler (fn [ctx event] (reset! result {:ctx ctx :event event}))
          ic (chan 10)
          oc (chan 10)
          ;; Server sends notification
          _ (>!! oc {"method" "notifications/tools/list_changed"})

          f2 (service-action {} {} {"id" ["llm" "servicing"]} {})
          context {:mcp/bridge {:input ic :output oc}}
          event {"message" {"method" "tools/call"} "document" "doc"}]
      (f2 context event [] handler)

      (is (= ["servicing" "caching"] (get-in @result [:event "id"])))
      (close! ic)
      (close! oc))))

;;------------------------------------------------------------------------------
;; mcp-end-action Tests
;;------------------------------------------------------------------------------

(deftest mcp-end-action-test
  (testing "delivers to completion promise"
    (let [p (promise)
          f2 (mcp-end-action {} {} {} {})
          context {:fsm/completion-promise p}
          trail [{:from "llm" :to "end" :event {"result" "done"}}]]
      (f2 context {} trail nil)

      (let [[ctx trail-out] (deref p 100 :timeout)]
        (is (not= :timeout [ctx trail-out]))
        (is (= context ctx))
        (is (= trail trail-out)))))

  (testing "handles missing promise gracefully"
    (let [f2 (mcp-end-action {} {} {} {})]
      ;; Should not throw
      (is (nil? (f2 {} {} [] nil))))))
