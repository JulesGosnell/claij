(ns claij.examples.memory-demo
  "Demo of memory interceptor with simple verifiable facts.
  
  This demo proves the interceptor architecture works end-to-end
  and helps develop a universal prompt that works across all LLMs.
  
  Usage:
    ;; With mock LLM (no API calls)
    (demo-with-mock)
    
    ;; With real GPT-4 (requires OPENROUTER_API_KEY env var)
    (demo-with-gpt4)
    
    ;; With real Claude
    (demo-with-claude)"
  (:require [claij.new.core :refer [call-llm]]
            [claij.new.interceptor :refer [memory-interceptor]]
            [claij.new.backend.openrouter :refer [model-registry]]
            [clojure.data.json :as json]
            [clojure.string :refer [includes? trim lower-case]]))

;; =============================================================================
;; Mock LLM for testing without API calls
;; =============================================================================

(defn mock-llm
  "Mock LLM that returns responses matching the tightened prompts.
  
  Simulates what we want GPT-4 to do: return single words/numerals only."
  [prompts]
  (let [user-msg (:user prompts)
        has-memory? (includes? (:system prompts) "Previous context:")]

    (cond
      ;; First exchange - store facts
      (includes? user-msg "Store these facts")
      (json/write-str
       {:answer "I've stored those facts about you, Alice!"
        :state "ready"
        :summary "User's name is Alice. Favorite color is blue. Has 2 cats."})

      ;; Second exchange - fill in the blank for color (just the word)
      (and has-memory? (includes? user-msg "Alice's favorite color is"))
      (json/write-str
       {:answer "blue"
        :state "ready"
        :summary "Confirmed: user's favorite color is blue."})

      ;; Third exchange - fill in the blank for number (just the numeral)
      (and has-memory? (includes? user-msg "Alice has"))
      (json/write-str
       {:answer "2"
        :state "ready"
        :summary "Confirmed: user has 2 cats."})

      ;; Default
      :else
      (json/write-str
       {:answer "I understand."
        :state "ready"
        :summary "General acknowledgment."}))))

;; =============================================================================
;; Validation helpers
;; =============================================================================

(defn check-summary-contains
  "Check if summary contains expected facts."
  [summary & facts]
  (let [missing (remove #(re-find (re-pattern %) summary) facts)]
    (if (seq missing)
      {:valid? false
       :missing missing
       :summary summary}
      {:valid? true
       :summary summary})))

(defn validate-exchange
  "Validate an LLM exchange meets expectations."
  [result expected-answer-contains expected-summary-facts]
  (let [response (:response result)
        answer (:answer response)
        summary (:summary response)]

    {:exchange-valid? (:success result)
     :answer answer
     :answer-check (if expected-answer-contains
                     (re-find (re-pattern expected-answer-contains) (str answer))
                     true)
     :summary summary
     :summary-check (if (seq expected-summary-facts)
                      (apply check-summary-contains (str summary) expected-summary-facts)
                      {:valid? true :summary summary})
     :memory (:memory (:ctx result))}))

;; =============================================================================
;; Demo scenarios
;; =============================================================================

(defn run-demo
  "Run the memory demo with given LLM function.
  
  Tests three exchanges with ultra-tight fill-in-the-blank prompts that
  constrain responses to single words/numerals for unambiguous validation:
  1. Introduce facts (name, color, cats) 
  2. Fill blank for color (expects exactly 'blue')
  3. Fill blank for number (expects exactly '2')"
  [llm-fn llm-name]
  (println "\n========================================")
  (println "Memory Interceptor Demo with" llm-name)
  (println "========================================\n")

  ;; Exchange 1: Introduce facts
  (println "Exchange 1: Introducing facts...")
  (let [result1 (call-llm
                 llm-fn
                 "Store these facts: My name is Alice. My favorite color is blue. I have 2 cats."
                 [memory-interceptor]
                 {})
        validation1 (validate-exchange
                     result1
                     "Alice"
                     ["Alice" "blue" "2.*cats?"])]

    (println "Answer:" (:answer validation1))
    (println "Summary:" (:summary validation1))
    (println "Summary contains all facts?"
             (if (:valid? (:summary-check validation1))
               "YES"
               (str "NO - Missing: " (:missing (:summary-check validation1)))))
    (println)

    ;; Exchange 2: Ultra-tight fill-in-the-blank for color
    (println "Exchange 2: Testing recall with constrained blank...")
    (let [ctx2 (:ctx result1)
          result2 (call-llm
                   llm-fn
                   "Answer with just the one word that goes in the blank: Alice's favorite color is ____."
                   [memory-interceptor]
                   ctx2)
          validation2 (validate-exchange
                       result2
                       "blue"
                       [])]

      (println "Answer:" (:answer validation2))
      (println "Answer is exactly 'blue'?"
               (if (= "blue" (trim (lower-case (str (:answer validation2)))))
                 "YES"
                 "NO"))
      (println)

      ;; Exchange 3: Ultra-tight fill-in-the-blank for number
      (println "Exchange 3: Testing recall with numeric blank...")
      (let [ctx3 (:ctx result2)
            result3 (call-llm
                     llm-fn
                     "Answer with just the one numeral that goes in the blank: Alice has ____ cats."
                     [memory-interceptor]
                     ctx3)
            validation3 (validate-exchange
                         result3
                         "2"
                         [])]

        (println "Answer:" (:answer validation3))
        (println "Answer is exactly '2'?"
                 (if (= "2" (trim (str (:answer validation3))))
                   "YES"
                   "NO"))
        (println)

        ;; Final summary
        (println "========================================")
        (println "Demo Complete!")
        (println "========================================")
        (let [answer2-clean (trim (lower-case (str (:answer validation2))))
              answer3-clean (trim (str (:answer validation3)))
              all-valid? (and (:valid? (:summary-check validation1))
                              (= "blue" answer2-clean)
                              (= "2" answer3-clean))]
          (println "Overall result:"
                   (if all-valid?
                     "PASS - Memory working correctly!"
                     "FAIL - Memory issues detected"))
          (when-not all-valid?
            (println "\nDetails:")
            (when-not (= "blue" answer2-clean)
              (println "  - Expected 'blue', got:" answer2-clean))
            (when-not (= "2" answer3-clean)
              (println "  - Expected '2', got:" answer3-clean)))
          (println)
          all-valid?)))))

(defn demo-with-mock
  "Run demo with mock LLM (no API calls)."
  []
  (run-demo mock-llm "Mock LLM"))

(defn demo-with-model
  "Run demo with a specific model from the registry.
  
  Parameters:
  - model-key: Keyword from openrouter/model-registry (e.g., :gpt-4, :claude)
  - opts: Optional map passed to model constructor (temperature, max-tokens, etc.)"
  ([model-key] (demo-with-model model-key {}))
  ([model-key opts]
   (if-let [model-info (get model-registry model-key)]
     (let [{:keys [display-name constructor]} model-info
           llm-fn (constructor opts)]
       (run-demo llm-fn (str display-name " (OpenRouter)")))
     (throw (ex-info (str "Unknown model: " model-key
                          ". Available: " (keys model-registry))
                     {:model-key model-key
                      :available (keys model-registry)})))))

(defn demo-all-models
  "Run memory integration tests across all registered models.
  
  Uses doseq for side effects (printing results for each model).
  
  Parameters:
  - opts: Optional map passed to all model constructors (default: {:temperature 0.3 :max-tokens 300})"
  ([] (demo-all-models {:temperature 0.3 :max-tokens 300}))
  ([opts]
   (println "\n" (str (apply str (repeat 60 "="))))
   (println " MEMORY INTERCEPTOR INTEGRATION TEST SUITE")
   (println (str (apply str (repeat 60 "="))) "\n")
   (println "Testing memory persistence across all registered LLMs...")
   (println "Using ultra-tight fill-in-the-blank prompts for predictable responses.\n")

   (let [results (atom [])]
     (doseq [[model-key model-info] model-registry]
       (println "\n" (str (apply str (repeat 60 "-"))))
       (try
         (let [pass? (demo-with-model model-key opts)]
           (swap! results conj {:model model-key
                                :display-name (:display-name model-info)
                                :pass? pass?}))
         (catch Exception e
           (println "ERROR:" (.getMessage e))
           (swap! results conj {:model model-key
                                :display-name (:display-name model-info)
                                :pass? false
                                :error (.getMessage e)}))))

     ;; Print summary
     (println "\n" (str (apply str (repeat 60 "="))))
     (println " TEST SUITE SUMMARY")
     (println (str (apply str (repeat 60 "="))))
     (doseq [{:keys [model display-name pass? error]} @results]
       (println (format "%-20s %s %s"
                        display-name
                        (if pass? "PASS" "FAIL")
                        (if error (str "(" error ")") ""))))
     (println (str (apply str (repeat 60 "="))))

     ;; Return overall pass/fail
     (let [all-pass? (every? :pass? @results)]
       (println "\nOverall:" (if all-pass? "ALL TESTS PASSED" "SOME TESTS FAILED"))
       all-pass?))))

;; =============================================================================
;; Real LLM demos (require backend adapter)
;; =============================================================================

(defn demo-with-gpt4
  "Run demo with real GPT-4 via OpenRouter.
  
  Requires OPENROUTER_API_KEY environment variable."
  []
  (println "[WARNING]  This will make real API calls and cost money!")
  (println "Press ENTER to continue or Ctrl-C to abort...")
  (read-line)
  (demo-with-model :gpt-4 {:temperature 0.3 :max-tokens 300}))

(defn demo-with-claude
  "Run demo with real Claude via OpenRouter.
  
  Requires OPENROUTER_API_KEY environment variable."
  []
  (println "[WARNING]  This will make real API calls and cost money!")
  (println "Press ENTER to continue or Ctrl-C to abort...")
  (read-line)
  (demo-with-model :claude {:temperature 0.3 :max-tokens 300}))

(comment
  ;; Try the demo!
  (demo-with-mock)

  ;; Test individual models
  (demo-with-model :gpt-4)
  (demo-with-model :claude)

  ;; Run full integration test suite across all models
  ;; WARNING: This makes API calls for each registered model!
  (demo-all-models))
