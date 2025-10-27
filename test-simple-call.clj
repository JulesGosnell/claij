(require '[claij.new.core :as core]
         '[clojure.data.json :as json])

(def llm-fn (fn [prompts]
              (println "LLM called with prompts:" prompts)
              (let [response (json/write-str {:answer "Hello" :state "ready"})]
                (println "LLM returning:" response)
                response)))

(println "Calling call-llm...")
(def result (core/call-llm llm-fn "Hi there" []))

(println "\nResult:")
(prn result)

(println "\nResponse data:")
(prn (:response result))

(when (:response result)
  (println "\nAnswer:" (:answer (:response result)))
  (println "State:" (:state (:response result))))
