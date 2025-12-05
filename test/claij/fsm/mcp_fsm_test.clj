(ns claij.fsm.mcp-fsm-test
  "Tests for MCP Protocol FSM - verifies cache building and threading"
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.tools.logging :as log]
   [claij.fsm :refer [start-fsm llm-action]]
   [claij.fsm.mcp-fsm :refer [mcp-actions mcp-fsm]]))

;;==============================================================================
;; LLM Stub/Real Switch
;;==============================================================================
;; Set to true to use real LLM calls, false to use stub responses.
;; Default is false (stub) for fast, deterministic tests.
;;==============================================================================

(def ^:dynamic *use-real-llm* false)

(defn stub-llm-action
  "Stub LLM action that immediately transitions to end state.
   Returns a valid MCP content format response without making API calls."
  [context _fsm _ix _state _event _trail handler]
  (log/info "stub-llm-action: returning canned end transition")
  (handler context {"id" ["llm" "end"]
                    "result" {"content" [{"type" "text"
                                          "text" "Stub LLM response - FSM flow completed"}]}}))

(defn get-test-actions
  "Returns mcp-actions with either real or stub LLM based on *use-real-llm*"
  []
  (if *use-real-llm*
    mcp-actions
    (assoc mcp-actions "llm" stub-llm-action)))

;;==============================================================================
;; Simple Action Tracking
;;==============================================================================
;; TODO: This is a TEMPORARY solution for testing this specific FSM.
;;
;; FUTURE: Generic FSM Testing Infrastructure
;; 1. FSM machinery should record its own path (state/transition traversal)
;; 2. Trail should be FSM-centric, post-processed for LLM (see Issue 1)
;; 3. Latch-based coordination: shared start/end actions for proper async waiting
;;
;; See doc/MCP.md "Future: Generic FSM Testing Infrastructure" for details.
;;==============================================================================

(defn make-tracker
  "Creates a simple action call counter.
  Returns [counter-atom wrapper-fn]"
  []
  (let [counter (atom {})]
    [counter
     (fn [action-name original-action]
       (fn [context & args]
         (swap! counter update action-name (fnil inc 0))
         (apply original-action context args)))]))

(defn wrap-actions
  "Wraps all actions with tracking wrapper."
  [actions wrapper-fn]
  (reduce-kv
   (fn [m action-name action-fn]
     (assoc m action-name (wrapper-fn action-name action-fn)))
   {}
   actions))

;;==============================================================================
;; TESTS
;;==============================================================================

(deftest ^:integration mcp-fsm-smoke-test
  (testing "MCP FSM can start, accept input, and complete"
    (let [context {:id->action (get-test-actions)}
          result (claij.fsm/run-sync mcp-fsm context
                                     {"id" ["start" "starting"]
                                      "document" "smoke test"}
                                     120000)] ; 2 minute timeout

      (is (not= result :timeout) "FSM should complete within timeout")
      (when (not= result :timeout)
        (let [[final-context trail] result
              final-event (claij.fsm/last-event trail)]
          (is (map? final-event) "FSM should return final event")
          (log/info "FSM completed successfully with event:" final-event))))))

(deftest ^:integration mcp-fsm-flow-test
  (testing "MCP FSM action tracking and cache construction"
    (let [[counter wrapper-fn] (make-tracker)
          tracked-actions (wrap-actions (get-test-actions) wrapper-fn)
          context {:id->action tracked-actions
                   "state" {}} ;; Initialize with empty state for cache
          [submit await _stop] (start-fsm context mcp-fsm)]

      (submit {"id" ["start" "starting"]
               "document" "test action flow"})

      ;; Wait for FSM completion with timeout
      (let [result (await 120000)]
        (is (not= result :timeout) "FSM should complete within 10 seconds")

        (when (not= result :timeout)
          (let [[final-context trail] result
                counts @counter
                cache (get final-context "state")]

            (testing "action tracking"
              (log/info "Final action call counts:" counts)

              ;; At minimum, start and end should have been called
              (is (>= (get counts "start" 0) 1) "start action should be called")
              (is (>= (get counts "end" 0) 1) "end action should be called")

              ;; Log what we observed for analysis
              (log/info "Actions called:" (keys counts))
              (log/info "Total action invocations:" (apply + (vals counts))))

            (testing "cache initialization from capabilities"
              ;; Cache should be initialized with capability keys
              (is (map? cache) "Cache should be a map")
              (is (contains? cache "tools") "Cache should have tools key")
              (is (contains? cache "prompts") "Cache should have prompts key")
              (is (contains? cache "resources") "Cache should have resources key")
              (log/info "Cache keys initialized:" (keys cache)))

            (testing "cache population with data"
              ;; Cache should be populated with actual data (not nil)
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
              ;; Each tool should have expected keys
              (let [tools (get cache "tools")]
                (is (every? #(contains? % "name") tools) "Each tool should have a name")
                (is (every? #(contains? % "description") tools) "Each tool should have a description")
                (is (every? #(contains? % "inputSchema") tools) "Each tool should have an inputSchema"))

              ;; Each prompt should have expected keys
              (let [prompts (get cache "prompts")]
                (is (every? #(contains? % "name") prompts) "Each prompt should have a name")
                (is (every? #(contains? % "description") prompts) "Each prompt should have a description"))

              ;; Each resource should have expected keys
              (let [resources (get cache "resources")]
                (is (every? #(contains? % "uri") resources) "Each resource should have a uri")
                (is (every? #(contains? % "name") resources) "Each resource should have a name")
                (is (every? #(contains? % "mimeType") resources) "Each resource should have a mimeType")))))))))

(deftest ^:integration mcp-fsm-tool-call-test
  (testing "MCP FSM can make tool calls via llm↔servicing loop"
    ;; This test requires real LLM to test the tool calling loop
    (binding [*use-real-llm* true]
      (let [[counter wrapper-fn] (make-tracker)
            tracked-actions (wrap-actions (get-test-actions) wrapper-fn)
            context {:id->action tracked-actions
                     "state" {}}
            [submit await _stop] (start-fsm context mcp-fsm)]

        (submit {"id" ["start" "starting"]
                 "document" "Please use the bash tool to run 'hostname' and return the result"})

        (let [result (await 180000)]
          (is (not= result :timeout) "FSM should complete within 3 minutes")

          (when (not= result :timeout)
            (let [[_final-context trail] result
                  counts @counter
                  final-event (claij.fsm/last-event trail)]

              (testing "llm action called multiple times (entry + tool response)"
                ;; llm should be called at least twice:
                ;; 1. Initial entry (makes tool call)
                ;; 2. Tool response (completes)
                (is (>= (get counts "llm" 0) 2)
                    "llm action should be called at least twice for tool call loop"))

              (testing "service action called for tool routing"
                ;; service should be called multiple times:
                ;; 1. During init (sends initialized notification)
                ;; 2. Tool call from llm
                (is (>= (get counts "service" 0) 2)
                    "service action should handle init and tool call"))

              (testing "tool call result captured"
                ;; Final event should contain the tool result
                (is (map? final-event) "Final event should be a map")
                (let [tool-result (get final-event "result")]
                  (is (some? tool-result) "Final event should have result from tool call")
                  (when tool-result
                    ;; The result should be in MCP content format and contain a hostname
                    (let [content (get tool-result "content")
                          text (get-in content [0 "text"])]
                      (is (some? text) "Result should have text content")
                      ;; Hostname should be a non-empty string (not a made-up number)
                      (is (re-find #"[a-zA-Z]" (str text)) "Result should contain letters (hostname)")
                      (log/info "Tool call result:" text)))))

              (log/info "Action counts:" counts))))))))


