(ns claij.fsm.ollama-tuple3-test
  "Test Ollama models with tuple-3 FSM prompts.
   
   Run from REPL:
     (require '[claij.fsm.ollama-tuple3-test :as ot] :reload)
     (ot/test-model \"mistral:7b\")
     (ot/test-model \"qwen2.5-coder:7b\")
   
   Or test all available models:
     (ot/test-all-models)"
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.tools.logging :as log]
   [claij.schema :as schema]
   [claij.fsm :as fsm :refer [start-fsm run-sync build-fsm-registry]]
   [claij.actions :as actions]
   [claij.fsm.code-review-fsm :refer [code-review-fsm]]))

;; Simple code snippet for testing
(def test-code "(defn add [a b] (+ a b))")

;; Minimal concerns for fast test
(def test-concerns ["Is this idiomatic Clojure?"])

;; Models to test (should fit in ~9GB VRAM)
(def candidate-models
  ["mistral:7b"
   "qwen2.5-coder:7b"
   "qwen2.5:7b" ;; need to pull
   "gemma2:9b" ;; need to pull
   "llama3.2:8b" ;; need to pull
   "phi3.5:3.8b" ;; need to pull
   "qwen2.5-coder:14b" ;; larger, might be slow
   "deepseek-coder-v2:16b"])

(defn test-model
  "Test a single Ollama model with the code-review FSM.
   Returns {:model model :success? bool :time-ms n :error err :final-event event}"
  ([model] (test-model model (* 2 60 1000))) ;; 2 min default timeout
  ([model timeout-ms]
   (log/info (str "\n========== Testing model: " model " =========="))
   (let [start-time (System/currentTimeMillis)

         ;; Use ollama:local service with specified model
         context {:id->action {"llm" #'fsm/llm-action "end" #'actions/end-action}
                  :llm/service "ollama:local"
                  :llm/model model}

         ;; Minimal entry message - just one LLM, one concern
         entry-msg {"id" ["start" "chairman"]
                    "document" (str "Review this Clojure code: " test-code)
                    "llms" [{"service" "ollama:local" "model" model}]
                    "concerns" test-concerns}

         result (try
                  (run-sync code-review-fsm context entry-msg timeout-ms)
                  (catch Exception e
                    {:error e}))]

     (let [elapsed (- (System/currentTimeMillis) start-time)]

       (cond
         (= result :timeout)
         (do
           (log/warn (str "TIMEOUT after " elapsed "ms for model: " model))
           {:model model :success? false :time-ms elapsed :error :timeout})

         (contains? result :error)
         (do
           (log/error (str "ERROR for model " model ": " (:error result)))
           {:model model :success? false :time-ms elapsed :error (:error result)})

         :else
         (let [[_context trail] result
               final-event (fsm/last-event trail)
               bail-out? (get final-event "bail_out")
               valid? (and (= ["chairman" "end"] (get final-event "id"))
                           (not bail-out?))]
           (cond
             bail-out?
             (do
               (log/warn (str "BAIL-OUT for model " model " in " elapsed "ms"))
               (log/warn (str "Error: " (get final-event "error")))
               {:model model
                :success? false
                :time-ms elapsed
                :error :bail-out
                :bail-out-reason (get-in final-event ["error" :reason])
                :final-event final-event
                :trail-count (count trail)})

             valid?
             (do
               (log/info (str "SUCCESS for model " model " in " elapsed "ms"))
               {:model model
                :success? true
                :time-ms elapsed
                :final-event final-event
                :trail-count (count trail)})

             :else
             (do
               (log/warn (str "PARTIAL for model " model " - unexpected end state"))
               (log/warn (str "Final event id: " (get final-event "id")))
               {:model model
                :success? false
                :time-ms elapsed
                :error :unexpected-state
                :final-event final-event
                :trail-count (count trail)}))))))))

(defn test-all-models
  "Test all candidate models and return summary."
  []
  (let [results (for [model candidate-models]
                  (try
                    (test-model model)
                    (catch Exception e
                      {:model model :success? false :error (.getMessage e)})))]

    (log/info "\n\n========== SUMMARY ==========")
    (doseq [{:keys [model success? time-ms error]} results]
      (log/info (format "%-30s %s %s"
                        model
                        (if success? "✓ PASS" "✗ FAIL")
                        (if error (str "(" error ")") (str "(" time-ms "ms)")))))

    (let [passed (filter :success? results)]
      (log/info (format "\nPassed: %d/%d models" (count passed) (count results))))

    results))

(defn quick-test
  "Quick test with very short timeout - just check if model can start."
  [model]
  (test-model model 30000)) ;; 30 second timeout

;; Integration test that can be run with lein test
(deftest ^:integration ^:long-running ollama-mistral-test
  (testing "Mistral 7b can complete code-review FSM"
    (let [result (test-model "mistral:7b" (* 3 60 1000))]
      (is (:success? result) (str "Mistral should succeed: " (:error result))))))
