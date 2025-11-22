(ns claij.fsm.mcp-fsm-test
  "Tests for MCP Protocol FSM - verifies cache building and threading"
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.tools.logging :as log]
   [claij.fsm :refer [start-fsm]]
   [claij.fsm.mcp-fsm :refer [mcp-actions mcp-fsm]]))

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
    (let [context {:id->action mcp-actions}
          result (claij.fsm/run-sync mcp-fsm context
                                     {"id" ["start" "starting"]
                                      "document" "smoke test"}
                                     15000)] ; 15 second timeout (first test takes 9s)

      (is (not= result :timeout) "FSM should complete within timeout")
      (when (not= result :timeout)
        (let [[final-context trail] result
              final-event (claij.fsm/last-event trail)]
          (is (map? final-event) "FSM should return final event")
          (log/info "FSM completed successfully with event:" final-event))))))

(deftest ^:integration mcp-fsm-flow-test
  (testing "MCP FSM action tracking and cache construction"
    (let [[counter wrapper-fn] (make-tracker)
          tracked-actions (wrap-actions mcp-actions wrapper-fn)
          context {:id->action tracked-actions
                   "state" {}} ;; Initialize with empty state for cache
          [submit await _stop] (start-fsm context mcp-fsm)]

      (submit {"id" ["start" "starting"]
               "document" "test action flow"})

      ;; Wait for FSM completion with timeout
      (let [result (await 10000)]
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
                (is (every? #(contains? % "mimeType") resources) "Each resource should have a mimeType")))

            (testing "resources text content merged"
              ;; Resources should have text content merged from read-resource responses
              (let [resources (get cache "resources")]
                ;; At least some resources should have text content
                ;; (depending on which resources were read during FSM execution)
                (is (some #(contains? % "text") resources)
                    "At least some resources should have text content merged")
                (log/info "Resources with text:"
                          (count (filter #(contains? % "text") resources))
                          "of" (count resources))))))))))

