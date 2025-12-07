(ns claij.fsm.minimal-fsm-test
  "Minimal FSM test - mirrors the POC exactly but through FSM machinery.
   
   If this works, we know the FSM infrastructure is correct.
   If this fails, the problem is in the FSM machinery, not the prompts."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.edn :as edn]
   [malli.core :as m]
   [malli.registry :as mr]
   [claij.malli :refer [base-registry]]
   [claij.fsm :as fsm]))

;;------------------------------------------------------------------------------
;; Schemas - IDENTICAL to POC

(def minimal-schemas
  "Schemas with IDs that match FSM transition IDs.
   The 'id' field must be the actual transition [from to] for FSM routing."
  {"input" [:map {:closed true}
            ["question" :string]]
   
   ;; Single output schema - id must match transition ["responder" "end"]
   "output" [:map {:closed true}
             ["id" [:= ["responder" "end"]]]
             ["answer" :string]
             ["agree" :boolean]]})

(def minimal-registry
  (mr/composite-registry base-registry minimal-schemas))

;;------------------------------------------------------------------------------
;; FSM Definition - Simplest possible: start -> responder -> end

(def minimal-fsm
  {"id" "minimal-test"
   "schemas" minimal-schemas
   "prompts" []  ;; No extra FSM-level prompts
   
   "states"
   [{"id" "responder"
     "action" "llm"
     "prompts" []}  ;; No extra state-level prompts - rely on system prompt
    {"id" "end"
     "action" "end"}]
   
   "xitions"
   [{"id" ["start" "responder"]
     "schema" [:ref "input"]}
    {"id" ["responder" "end"]
     "schema" [:ref "output"]}]})

;;------------------------------------------------------------------------------
;; End action

(defn end-action [context _fsm _ix _state _event trail _handler]
  ;; Trail already includes current event (added by xform)
  ;; Remove promise from context to avoid circular refs
  (when-let [p (:fsm/completion-promise context)]
    (deliver p [(dissoc context :fsm/completion-promise) trail])))

;;------------------------------------------------------------------------------
;; Test

(defn test-minimal-fsm
  "Test the minimal FSM with a simple question."
  []
  (let [actions {"llm" fsm/llm-action "end" end-action}
        context {:id->action actions
                 :llm/provider "anthropic"
                 :llm/model "claude-sonnet-4.5"}
        ;; Use string keys for input - matches schema
        input {"question" "Is 2 + 2 = 4?"}
        result (fsm/run-sync minimal-fsm context input 60000)]
    (if (= result :timeout)
      {:success false :error "Timeout"}
      (let [[_ctx trail] result
            last-evt (fsm/last-event trail)]
        (if (m/validate [:ref "output"] last-evt {:registry minimal-registry})
          {:success true :response last-evt}
          {:success false :response last-evt :error "Validation failed"})))))

(deftest ^:integration minimal-fsm-test
  (testing "Minimal FSM works with POC-identical schemas"
    (let [{:keys [success response error]} (test-minimal-fsm)]
      (is success (str "Should succeed. Error: " error " Response: " response))
      (when success
        (is (= ["responder" "end"] (get response "id"))
            "Should have correct transition id")
        (is (true? (get response "agree"))
            "Should agree that 2+2=4")))))

;; REPL helper
;; (require '[claij.fsm.minimal-fsm-test :refer [test-minimal-fsm]] :reload)
;; (test-minimal-fsm)
