(ns claij.fsm.mcp-fsm-test
  "Tests for MCP Protocol FSM - verifies cache building, threading, and action implementations"
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clojure.tools.logging :as log]
   [clojure.core.async :refer [chan >!! <!! close!]]
   [claij.action :refer [def-action action?]]
   [claij.fsm :as fsm :refer [start-fsm]]
   [claij.mcp.bridge :as bridge]
   [claij.mcp.client :as client]
   [claij.fsm.mcp-fsm :as mcp-fsm :refer [mcp-actions mcp-fsm wrap unwrap take!
                                          drain-notifications send-and-wait
                                          start-action shed-action init-action
                                          service-action cache-action mcp-end-action
                                          check-cache-and-continue]]))

;;==============================================================================
;; Unit Tests for Utility Functions
;;==============================================================================

(deftest mcp-fsm-test

  (testing "wrap"
    (testing "3-arity creates map with id, document, and message"
      (let [result (wrap ["a" "b"] "doc" {"foo" "bar"})]
        (is (= ["a" "b"] (get result "id")))
        (is (= "doc" (get result "document")))
        (is (= {"foo" "bar"} (get result "message")))))

    (testing "2-arity creates map with id and message only"
      (let [result (wrap ["x" "y"] {"baz" 123})]
        (is (= ["x" "y"] (get result "id")))
        (is (= {"baz" 123} (get result "message")))
        (is (not (contains? result "document"))))))

  (testing "unwrap"
    (testing "extracts message from wrapped map"
      (is (= {"foo" "bar"} (unwrap {"message" {"foo" "bar"}}))))

    (testing "returns nil for missing message"
      (is (nil? (unwrap {"id" "test"})))))

  (testing "mcp-actions"
    (testing "contains expected action keys"
      (is (contains? mcp-actions "start"))
      (is (contains? mcp-actions "shed"))
      (is (contains? mcp-actions "init"))
      (is (contains? mcp-actions "service"))
      (is (contains? mcp-actions "cache"))
      (is (contains? mcp-actions "llm"))
      (is (contains? mcp-actions "end")))

    (testing "all values are action vars"
      (doseq [[k v] mcp-actions]
        (is (var? v) (str "Expected var for action: " k))
        (is (action? v) (str "Expected action? true for: " k)))))

  (testing "mcp-fsm"
    (testing "has correct id"
      (is (= "mcp" (get mcp-fsm "id"))))

    (testing "has expected states"
      (let [state-ids (set (map #(get % "id") (get mcp-fsm "states")))]
        (is (contains? state-ids "starting"))
        (is (contains? state-ids "shedding"))
        (is (contains? state-ids "initing"))
        (is (contains? state-ids "servicing"))
        (is (contains? state-ids "caching"))
        (is (contains? state-ids "llm"))
        (is (contains? state-ids "end"))))))

;;==============================================================================
;; Stub Actions for Unit Testing (no MCP server required)
;;==============================================================================
;; These simulate the MCP protocol flow without starting a real subprocess.
;; Used for fast, deterministic tests that verify FSM machinery and cache logic.
;;==============================================================================

(def stub-tools
  "Fake tool data for cache population tests"
  [{"name" "bash"
    "description" "Execute bash commands"
    "inputSchema" {"type" "object"
                   "properties" {"command" {"type" "string"}}
                   "required" ["command"]}}
   {"name" "read_file"
    "description" "Read file contents"
    "inputSchema" {"type" "object"
                   "properties" {"path" {"type" "string"}}
                   "required" ["path"]}}])

(def stub-prompts
  "Fake prompt data for cache population tests"
  [{"name" "code_review"
    "description" "Review code for issues"}
   {"name" "summarize"
    "description" "Summarize content"}])

(def stub-resources
  "Fake resource data for cache population tests"
  [{"uri" "file:///home/user/project/README.md"
    "name" "README.md"
    "mimeType" "text/markdown"}
   {"uri" "file:///home/user/project/src/core.clj"
    "name" "core.clj"
    "mimeType" "text/x-clojure"}])

(def-action stub-start-action
  "Stub start action - skips MCP bridge, initializes context."
  :any
  [_config _fsm _ix _state]
  (fn [context {d "document" :as _event} _trail handler]
    (log/info "stub-start-action: initializing without MCP bridge")
    (let [updated-context (assoc context
                                 :mcp/document d
                                 ;; Fake capabilities for cache initialization
                                 :stub/capabilities {"tools" {} "prompts" {} "resources" {}})]
      (handler updated-context
               {"id" ["starting" "shedding"]
                "document" d
                "message" {"jsonrpc" "2.0"
                           "id" 0
                           "result" {"protocolVersion" "2025-06-18"
                                     "capabilities" {"tools" {}
                                                     "prompts" {}
                                                     "resources" {}}}}}))))

(def-action stub-shed-action
  "Stub shed action - immediately passes through to init."
  :any
  [_config _fsm _ix _state]
  (fn [context {{_im "method" :as message} "message" document "document" :as _event} _trail handler]
    (log/info "stub-shed-action: passing through")
    (handler context
             {"id" ["shedding" "initing"]
              "document" document
              "message" {"jsonrpc" "2.0"
                         "id" 0
                         "result" {"protocolVersion" "2025-06-18"
                                   "capabilities" {"tools" {}
                                                   "prompts" {}
                                                   "resources" {}}}}})))

(def-action stub-init-action
  "Stub init action - sets up cache structure from fake capabilities."
  :any
  [_config _fsm _ix _state]
  (fn [context {document "document" :as _event} _trail handler]
    (log/info "stub-init-action: initializing cache structure")
    ;; Initialize cache with empty collections for each capability
    (let [updated-context (assoc context "state"
                                 {"tools" nil
                                  "prompts" nil
                                  "resources" nil})]
      (handler updated-context
               {"id" ["initing" "servicing"]
                "document" document
                "message" {"jsonrpc" "2.0"
                           "method" "initialized"}}))))

(def-action stub-service-action
  "Stub service action - routes messages without real MCP communication."
  :any
  [_config _fsm ix _state]
  (fn [context {m "message" document "document" :as _event} _trail handler]
    (let [[from _to] (get ix "id")]
      (log/info "stub-service-action: routing from" from)
      (cond
        ;; From caching - return fake list response based on what's being requested
        (= from "caching")
        (let [method (get m "method")]
          (cond
            (= method "tools/list")
            (handler context
                     {"id" ["servicing" "caching"]
                      "message" {"jsonrpc" "2.0"
                                 "id" (get m "id")
                                 "result" {"tools" stub-tools}}})

            (= method "prompts/list")
            (handler context
                     {"id" ["servicing" "caching"]
                      "message" {"jsonrpc" "2.0"
                                 "id" (get m "id")
                                 "result" {"prompts" stub-prompts}}})

            (= method "resources/list")
            (handler context
                     {"id" ["servicing" "caching"]
                      "message" {"jsonrpc" "2.0"
                                 "id" (get m "id")
                                 "result" {"resources" stub-resources}}})

            :else
            (handler context
                     {"id" ["servicing" "caching"]
                      "message" {"jsonrpc" "2.0"
                                 "id" (get m "id")
                                 "result" {}}})))

        ;; From initing - go to caching
        (= from "initing")
        (handler context
                 {"id" ["servicing" "caching"]
                  "message" {}})

        ;; From llm - return tool result
        (= from "llm")
        (handler context
                 (cond-> {"id" ["servicing" "llm"]
                          "message" {"jsonrpc" "2.0"
                                     "id" 1
                                     "result" {"content" [{"type" "text"
                                                           "text" "stub tool result"}]}}}
                   document (assoc "document" document)))

        ;; Default - go to llm
        :else
        (handler context
                 (cond-> {"id" ["servicing" "llm"]}
                   document (assoc "document" document)))))))

(def-action stub-cache-action
  "Stub cache action - populates cache with fake data."
  :any
  [_config _fsm ix _state]
  (fn [context {m "message" :as _event} _trail handler]
    (let [{method "method" result "result"} m
          cache (get context "state")]
      (log/info "stub-cache-action: cache state" (pr-str (keys cache)))
      (cond
        ;; Received list response - update cache
        result
        (let [;; Extract the data type from result keys
              data-key (first (filter #(not= % "jsonrpc") (keys result)))
              data (get result data-key)
              capability-key (when data-key
                               (cond
                                 (= data-key "tools") "tools"
                                 (= data-key "prompts") "prompts"
                                 (= data-key "resources") "resources"
                                 :else nil))
              updated-context (if capability-key
                                (assoc-in context ["state" capability-key] data)
                                context)
              new-cache (get updated-context "state")]
          (log/info "stub-cache-action: updated" capability-key "with" (count data) "items")
          ;; Check if all caches are populated
          (if (and (seq (get new-cache "tools"))
                   (seq (get new-cache "prompts"))
                   (seq (get new-cache "resources")))
            ;; All populated - go to llm
            (handler updated-context
                     {"id" ["caching" "llm"]
                      "document" (:mcp/document updated-context)})
            ;; Need more data - request next missing item
            (let [missing (cond
                            (nil? (get new-cache "tools")) "tools/list"
                            (nil? (get new-cache "prompts")) "prompts/list"
                            (nil? (get new-cache "resources")) "resources/list")]
              (handler updated-context
                       {"id" ["caching" "servicing"]
                        "message" {"jsonrpc" "2.0"
                                   "id" 1
                                   "method" missing}}))))

        ;; Initial entry - request first missing item
        :else
        (let [missing (cond
                        (nil? (get cache "tools")) "tools/list"
                        (nil? (get cache "prompts")) "prompts/list"
                        (nil? (get cache "resources")) "resources/list")]
          (if missing
            (handler context
                     {"id" ["caching" "servicing"]
                      "message" {"jsonrpc" "2.0"
                                 "id" 1
                                 "method" missing}})
            ;; All populated - go to llm
            (handler context
                     {"id" ["caching" "llm"]
                      "document" (:mcp/document context)})))))))

(def-action stub-llm-action
  "Stub LLM action that immediately transitions to end state."
  :any
  [_config _fsm _ix _state]
  (fn [context _event _trail handler]
    (log/info "stub-llm-action: completing FSM")
    (handler context {"id" ["llm" "end"]
                      "result" {"content" [{"type" "text"
                                            "text" "Stub LLM response - FSM flow completed"}]}})))

(def-action stub-end-action
  "Stub end action that delivers completion via promise."
  :any
  [_config _fsm _ix _state]
  (fn [context _event trail _handler]
    (log/info "stub-end-action: delivering completion")
    (when-let [p (:fsm/completion-promise context)]
      (deliver p [context trail]))
    nil))

;;==============================================================================
;; Test Action Selection
;;==============================================================================

(def stub-mcp-actions
  "Complete stub action set for unit testing without MCP server."
  {"start" #'stub-start-action
   "shed" #'stub-shed-action
   "init" #'stub-init-action
   "service" #'stub-service-action
   "cache" #'stub-cache-action
   "llm" #'stub-llm-action
   "end" #'stub-end-action})

(def ^:dynamic *use-real-mcp* false)

(defn get-test-actions
  "Returns stub or real actions based on *use-real-mcp*"
  []
  (if *use-real-mcp*
    mcp-actions
    stub-mcp-actions))

;;==============================================================================
;; Trail Analysis Helpers
;;==============================================================================

(defn count-transitions-to
  "Count how many times a state appears as destination in the trail."
  [trail state-name]
  (->> trail
       (filter #(= (:to %) state-name))
       count))

;;==============================================================================
;; UNIT TESTS (no MCP server required)
;;==============================================================================

(deftest mcp-fsm-smoke-test
  (testing "MCP FSM can start, accept input, and complete"
    (let [context {:id->action (get-test-actions)}
          result (fsm/run-sync mcp-fsm context
                               {"id" ["start" "starting"]
                                "document" "smoke test"}
                               30000)] ; 30 second timeout (stub is fast)

      (is (not= result :timeout) "FSM should complete within timeout")
      (when (not= result :timeout)
        (let [[_final-context trail] result
              final-event (fsm/last-event trail)]
          (is (map? final-event) "FSM should return final event")
          (log/info "FSM completed successfully with event:" final-event))))))

(deftest mcp-fsm-flow-test
  (testing "MCP FSM action tracking and cache construction"
    (let [context {:id->action (get-test-actions)
                   "state" {}}
          {:keys [submit await]} (start-fsm context mcp-fsm)]

      (submit {"id" ["start" "starting"]
               "document" "test action flow"})

      (let [result (await 30000)]
        (is (not= result :timeout) "FSM should complete within timeout")

        (when (not= result :timeout)
          (let [[final-context trail] result
                cache (get final-context "state")]

            (testing "state transitions from trail"
              (log/info "Trail has" (count trail) "transitions")
              ;; Most MCP transitions have "omit" true - only llm/end are recorded
              (is (>= (count-transitions-to trail "llm") 1) "should transition to llm state")
              (is (>= (count-transitions-to trail "end") 1) "should transition to end state")
              (log/info "States visited:" (distinct (map :to trail))))

            (testing "cache initialization from capabilities"
              (is (map? cache) "Cache should be a map")
              (is (contains? cache "tools") "Cache should have tools key")
              (is (contains? cache "prompts") "Cache should have prompts key")
              (is (contains? cache "resources") "Cache should have resources key")
              (log/info "Cache keys initialized:" (keys cache)))

            (testing "cache population with data"
              (is (coll? (get cache "tools")) "Tools should be a collection")
              (is (seq (get cache "tools")) "Tools should be non-empty")
              (is (coll? (get cache "prompts")) "Prompts should be a collection")
              (is (seq (get cache "prompts")) "Prompts should be non-empty")
              (is (coll? (get cache "resources")) "Resources should be a collection")
              (is (seq (get cache "resources")) "Resources should be non-empty")
              (log/info "Cache sizes - tools:" (count (get cache "tools"))
                        "prompts:" (count (get cache "prompts"))
                        "resources:" (count (get cache "resources"))))

            (testing "cache structure validation"
              (let [tools (get cache "tools")]
                (is (every? #(contains? % "name") tools) "Each tool should have a name")
                (is (every? #(contains? % "description") tools) "Each tool should have a description")
                (is (every? #(contains? % "inputSchema") tools) "Each tool should have an inputSchema"))

              (let [prompts (get cache "prompts")]
                (is (every? #(contains? % "name") prompts) "Each prompt should have a name")
                (is (every? #(contains? % "description") prompts) "Each prompt should have a description"))

              (let [resources (get cache "resources")]
                (is (every? #(contains? % "uri") resources) "Each resource should have a uri")
                (is (every? #(contains? % "name") resources) "Each resource should have a name")
                (is (every? #(contains? % "mimeType") resources) "Each resource should have a mimeType")))))))))

;;==============================================================================
;; INTEGRATION TESTS (require MCP server)
;;==============================================================================

(deftest ^:integration mcp-fsm-real-server-test
  (testing "MCP FSM with real MCP server"
    (binding [*use-real-mcp* true]
      (let [context {:id->action (get-test-actions)
                     "state" {}}
            ;; Simple task that completes in one LLM turn without tool calls
            result (fsm/run-sync mcp-fsm context
                                 {"id" ["start" "starting"]
                                  "document" "Reply with just the number 42. Do not use any tools."}
                                 90000)] ; 90s should be plenty for one LLM call

        (is (not= result :timeout) "FSM should complete within timeout")
        (when (not= result :timeout)
          (let [[final-context trail] result]
            (log/info "Integration test completed with" (count trail) "transitions")))))))

(deftest ^:integration mcp-fsm-tool-call-test
  (testing "MCP FSM can make tool calls via llm↔servicing loop"
    (binding [*use-real-mcp* true]
      (let [context {:id->action (get-test-actions)
                     "state" {}}
            {:keys [submit await]} (start-fsm context mcp-fsm)]

        (submit {"id" ["start" "starting"]
                 "document" "Please use the bash tool to run 'hostname' and return the result"})

        (let [result (await 180000)]
          (is (not= result :timeout) "FSM should complete within 3 minutes")

          (when (not= result :timeout)
            (let [[_final-context trail] result
                  final-event (fsm/last-event trail)]

              (testing "llm state visited multiple times (entry + tool response)"
                (is (>= (count-transitions-to trail "llm") 2)
                    "llm state should be visited at least twice for tool call loop"))

              (testing "servicing state visited for tool routing"
                (is (>= (count-transitions-to trail "servicing") 1)
                    "servicing state should handle tool call"))

              (testing "tool call result captured"
                (is (map? final-event) "Final event should be a map")
                (let [tool-result (get final-event "result")]
                  (is (some? tool-result) "Final event should have result from tool call")
                  (when tool-result
                    (let [content (get tool-result "content")
                          text (get-in content [0 "text"])]
                      (is (some? text) "Result should have text content")
                      (is (re-find #"[a-zA-Z]" (str text)) "Result should contain letters (hostname)")
                      (log/info "Tool call result:" text)))))

              (log/info "Trail transitions:" (count trail)))))))))

;;==============================================================================
;; Test Fixtures
;;==============================================================================

(defn quiet-logging
  "Suppress logging during tests unless DEBUG env var is set."
  [f]
  (if (System/getenv "DEBUG")
    (f)
    (with-redefs [log/log* (fn [& _])]
      (f))))

(use-fixtures :each quiet-logging)

;;==============================================================================
;; Action Unit Tests (no FSM machinery needed)
;;==============================================================================

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

(deftest drain-notifications-test
  (testing "drains all notifications from output channel"
    (let [mock-bridge {:output-chan (chan 10)}]
      ;; Put some notifications
      (>!! (:output-chan mock-bridge) {"method" "notifications/tools/list_changed"})
      (>!! (:output-chan mock-bridge) {"method" "notifications/prompts/list_changed"})
      ;; Drain them
      (drain-notifications mock-bridge)
      ;; Channel should now be empty (take! returns default)
      (is (nil? (take! (:output-chan mock-bridge) 10 nil)))))

  (testing "handles empty channel gracefully"
    (let [mock-bridge {:output-chan (chan 10)}]
      ;; Should not block or throw
      (drain-notifications mock-bridge)
      (is true))))

(deftest start-action-test
  (testing "initializes correlated bridge and transitions to initing"
    (let [result (atom nil)
          handler (fn [ctx event] (reset! result {:ctx ctx :event event}))
          init-response {"jsonrpc" "2.0" "id" 0 "result" {"protocolVersion" "2025-06-18"}}
          mock-bridge {:output-chan (chan 10) :pending (atom {}) :input-chan (chan 10) :stop (fn [])}
          f2 (start-action {} {} {} {})
          event {"document" "test-doc"}]
      ;; Mock bridge creation and send-and-wait
      (with-redefs [bridge/create-correlated-bridge (fn [_config] mock-bridge)
                    send-and-wait (fn [_bridge _request _timeout] init-response)]
        (f2 {} event [] handler))

      (is (= ["starting" "initing"] (get-in @result [:event "id"])))
      (is (= "test-doc" (get-in @result [:event "document"])))
      (is (= init-response (get-in @result [:event "message"])))
      (is (= mock-bridge (get-in @result [:ctx :mcp/bridge])))
      (is (= 0 (get-in @result [:ctx :mcp/request-id])))
      (is (= "test-doc" (get-in @result [:ctx :mcp/document]))))))

(deftest shed-action-test
  (testing "sends message to bridge and transitions to initing"
    (let [result (atom nil)
          handler (fn [ctx event] (reset! result {:ctx ctx :event event}))
          response {"id" 0 "result" {"protocolVersion" "2025-06-18"}}
          mock-bridge {:output-chan (chan 10) :pending (atom {}) :input-chan (chan 10)}
          f2 (shed-action {} {} {} {})
          context {:mcp/bridge mock-bridge}
          event {"document" "doc"
                 "message" {"method" "initialize" "id" 0}}]
      ;; Mock send-and-wait to return our response
      (with-redefs [send-and-wait (fn [_bridge _request _timeout] response)]
        (f2 context event [] handler))

      (is (= ["shedding" "initing"] (get-in @result [:event "id"])))
      (is (= "doc" (get-in @result [:event "document"])))
      (is (= response (get-in @result [:event "message"]))))))

(deftest init-action-test
  (testing "extracts capabilities and transitions to servicing"
    (let [result (atom nil)
          handler (fn [ctx event] (reset! result {:ctx ctx :event event}))
          f2 (init-action {} {} {} {})
          event {"document" "test-doc"
                 "message" {"result" {"capabilities" {"tools" {}
                                                      "prompts" {}}}}}]
      (f2 {} event [] handler)

      (is (= ["initing" "servicing"] (get-in @result [:event "id"])))
      (is (= "test-doc" (get-in @result [:event "document"])))
      (is (= "notifications/initialized" (get-in @result [:event "message" :method])))))

  (testing "handles missing capabilities gracefully"
    (let [result (atom nil)
          handler (fn [ctx event] (reset! result {:ctx ctx :event event}))
          f2 (init-action {} {} {} {})
          event {"document" "doc" "message" {}}]
      (f2 {} event [] handler)
      (is (= ["initing" "servicing"] (get-in @result [:event "id"]))))))

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

  (testing "goes to llm when cache complete"
    (let [result (atom nil)
          handler (fn [ctx event] (reset! result {:ctx ctx :event event}))
          context {"state" {"tools" [{}] "prompts" [{}] "resources" [{}]}
                   :mcp/document "my-doc"}]
      (check-cache-and-continue context handler)

      (is (= ["caching" "llm"] (get-in @result [:event "id"])))
      (is (= "my-doc" (get-in @result [:event "document"]))))))

(deftest cache-action-test
  (testing "refreshes cache on list response"
    (let [result (atom nil)
          handler (fn [ctx event] (reset! result {:ctx ctx :event event}))
          f2 (cache-action {} {} {"id" ["servicing" "caching"]} {})
          context {"state" {"tools" nil "prompts" [{}] "resources" [{}]}
                   :mcp/request-id 0
                   :mcp/document "doc"}
          event {"message" {"result" {"tools" [{"name" "bash"}]}}}]
      (f2 context event [] handler)

      (let [new-cache (get-in @result [:ctx "state"])]
        (is (= [{"name" "bash"}] (get new-cache "tools"))))))

  (testing "handles list_changed notification"
    (let [result (atom nil)
          handler (fn [ctx event] (reset! result {:ctx ctx :event event}))
          f2 (cache-action {} {} {"id" ["servicing" "caching"]} {})
          context {"state" {"tools" [{"name" "old"}] "prompts" [] "resources" []}
                   :mcp/request-id 0}
          event {"message" {"method" "notifications/tools/list_changed"}}]
      (f2 context event [] handler)

      (is (= ["caching" "servicing"] (get-in @result [:event "id"])))
      (is (= "tools/list" (get-in @result [:event "message" "method"])))
      (is (nil? (get-in @result [:ctx "state" "tools"])) "tools should be invalidated"))))

(deftest service-action-test
  (testing "routes llm request through client and back to llm"
    (let [result (atom nil)
          handler (fn [ctx event] (reset! result {:ctx ctx :event event}))
          mock-bridge {:output-chan (chan 10) :pending (atom {}) :input-chan (chan 10)}
          response {"jsonrpc" "2.0" "id" 1 "result" {"content" [{"type" "text" "text" "done"}]}}
          f2 (service-action {} {} {"id" ["llm" "servicing"]} {})
          context {:mcp/bridge mock-bridge}
          event {"message" {"jsonrpc" "2.0" "id" 1 "method" "tools/call" "params" {"name" "test"}}
                 "document" "doc"}]
      ;; Mock client/call-batch to return our response
      (with-redefs [client/call-batch (fn [_bridge _requests _opts] [response])]
        (f2 context event [] handler))

      (is (= ["servicing" "llm"] (get-in @result [:event "id"])))
      (is (= "doc" (get-in @result [:event "document"])))
      (is (= response (get-in @result [:event "message"])))))

  (testing "routes batch llm requests and returns batch response"
    (let [result (atom nil)
          handler (fn [ctx event] (reset! result {:ctx ctx :event event}))
          mock-bridge {:output-chan (chan 10) :pending (atom {}) :input-chan (chan 10)}
          responses [{"jsonrpc" "2.0" "id" 1 "result" {"content" [{"type" "text" "text" "one"}]}}
                     {"jsonrpc" "2.0" "id" 2 "result" {"content" [{"type" "text" "text" "two"}]}}]
          f2 (service-action {} {} {"id" ["llm" "servicing"]} {})
          context {:mcp/bridge mock-bridge}
          ;; Batch request - vector of requests
          event {"message" [{"jsonrpc" "2.0" "id" 1 "method" "tools/call" "params" {"name" "a"}}
                            {"jsonrpc" "2.0" "id" 2 "method" "tools/call" "params" {"name" "b"}}]
                 "document" "doc"}]
      (with-redefs [client/call-batch (fn [_bridge _requests _opts] responses)]
        (f2 context event [] handler))

      (is (= ["servicing" "llm"] (get-in @result [:event "id"])))
      ;; Batch response - vector of responses
      (is (vector? (get-in @result [:event "message"])))
      (is (= 2 (count (get-in @result [:event "message"]))))))

  (testing "routes caching request and returns response"
    (let [result (atom nil)
          handler (fn [ctx event] (reset! result {:ctx ctx :event event}))
          mock-bridge {:output-chan (chan 10) :pending (atom {}) :input-chan (chan 10)}
          response {"jsonrpc" "2.0" "id" 1 "result" {"tools" []}}
          f2 (service-action {} {} {"id" ["caching" "servicing"]} {})
          context {:mcp/bridge mock-bridge}
          event {"message" {"jsonrpc" "2.0" "id" 1 "method" "tools/list"}}]
      (with-redefs [send-and-wait (fn [_bridge _request _timeout] response)]
        (f2 context event [] handler))

      (is (= ["servicing" "caching"] (get-in @result [:event "id"])))
      (is (= response (get-in @result [:event "message"]))))))

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
      (is (nil? (f2 {} {} [] nil))))))
