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
  (testing "MCP FSM action tracking works"
    (let [[counter wrapper-fn] (make-tracker)
          tracked-actions (wrap-actions mcp-actions wrapper-fn)
          context {:id->action tracked-actions}
          [submit await _stop] (start-fsm context mcp-fsm)]

      (submit {"id" ["start" "starting"]
               "document" "test action flow"})

      ;; Wait for FSM completion with timeout
      (let [result (await 10000)]
        (is (not= result :timeout) "FSM should complete within 10 seconds")

        (when (not= result :timeout)
          (let [[final-context trail] result
                counts @counter]
            (log/info "Final action call counts:" counts)

            ;; At minimum, start and end should have been called
            (is (>= (get counts "start" 0) 1) "start action should be called")
            (is (>= (get counts "end" 0) 1) "end action should be called")

            ;; Log what we observed for analysis
            (log/info "Actions called:" (keys counts))
            (log/info "Total action invocations:" (apply + (vals counts)))))))))
