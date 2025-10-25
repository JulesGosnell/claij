(ns claij.test-gpt4
  "Simple test of GPT-4 integration"
  (:require [claij.new.backend.openrouter :refer [gpt-4]]
            [claij.new.core :refer [call-llm]]
            [claij.new.interceptor :refer [memory-interceptor]]
            [clojure.java.io :refer [reader]]))

(defn load-env []
  "Load .env file"
  (doseq [line (line-seq (reader ".env"))
          :let [line (clojure.string/trim line)]
          :when (and (not (clojure.string/blank? line))
                     (clojure.string/starts-with? line "export"))]
    (when-let [[_ key value] (re-matches #"export\s+([^=]+)=\"([^\"]*)\"" line)]
      (System/setProperty key value))))

(defn -main []
  (try
    (println "Loading .env...")
    (load-env)

    (println "Creating GPT-4 function...")
    (def llm-fn (gpt-4 {:temperature 0.5 :max-tokens 300}))

    (println "Testing simple call...")
    (def result (call-llm
                 llm-fn
                 "My name is Alice. My favorite color is blue."
                 [memory-interceptor]
                 {}))

    (println "\n=== RESULT ===")
    (clojure.pprint/pprint result)

    (if (:success result)
      (do
        (println "\n✓ SUCCESS!")
        (println "Answer:" (:answer (:response result)))
        (println "Summary:" (:summary (:response result)))
        (println "Memory:" (:memory (:ctx result))))
      (do
        (println "\n✗ FAILED!")
        (println "Error:" (:error result))))

    (catch Exception e
      (println "\n✗ EXCEPTION!")
      (println (.getMessage e))
      (.printStackTrace e))))
