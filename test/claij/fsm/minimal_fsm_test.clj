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
  "Exact same schemas as POC, with string registry keys"
  {"input" [:map {:closed true}
            [:question :string]]
   
   "output-agree" [:map {:closed true}
                   [:id [:= ["user" "agree"]]]
                   [:message :string]]
   
   "output-disagree" [:map {:closed true}
                      [:id [:= ["user" "disagree"]]]
                      [:reason :string]]
   
   "output" [:or [:ref "output-agree"] [:ref "output-disagree"]]})

(def minimal-registry
  (mr/composite-registry base-registry minimal-schemas))

;;------------------------------------------------------------------------------
;; FSM Definition - Simplest possible: start -> responder -> end

(def minimal-fsm
  {:id "minimal-test"
   :schemas minimal-schemas
   :prompts []  ;; No extra FSM-level prompts
   
   :states
   [{:id "responder"
     :action "llm"
     :prompts []}  ;; No extra state-level prompts - rely on system prompt
    {:id "end"
     :action "end"}]
   
   :xitions
   [{:id ["start" "responder"]
     :schema [:ref "input"]}
    {:id ["responder" "end"]
     :schema [:ref "output"]}]})

;;------------------------------------------------------------------------------
;; End action

(defn end-action [context _fsm _ix _state event trail _handler]
  (when-let [p (:fsm/completion-promise context)]
    (deliver p [context (conj trail event)])))

;;------------------------------------------------------------------------------
;; Test

(defn test-minimal-fsm
  "Test the minimal FSM with a simple question."
  []
  (let [actions {"llm" fsm/llm-action "end" end-action}
        context {:id->action actions
                 :llm/provider "anthropic"
                 :llm/model "claude-sonnet-4.5"}
        input {:question "Is 2 + 2 = 4?"}
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
        (is (= ["user" "agree"] (:id response))
            "Should agree that 2+2=4")))))

;; REPL helper
;; (require '[claij.fsm.minimal-fsm-test :refer [test-minimal-fsm]] :reload)
;; (test-minimal-fsm)
