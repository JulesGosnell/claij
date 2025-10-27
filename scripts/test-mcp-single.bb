#!/usr/bin/env bb
;; Single E2E test - Fill in the blank with addition

(require '[babashka.process :refer [shell]]
         '[clojure.string :as str])

;; Load .env if exists
(when (.exists (clojure.java.io/file ".env"))
  (doseq [line (line-seq (clojure.java.io/reader ".env"))]
    (when-let [[_ k v] (re-matches #"([^=]+)=(.*)" (str/trim line))]
      (when-not (str/blank? k)
        (println "Loading" k)))))

;; Run the test
(def result
  (shell {:dir "/home/jules/src/claij"
          :out :string
          :err :string}
         "clojure" "-M" "-e"
         "(do
           (require '[claij.dsl.mcp.api :as mcp-api])
           (require '[claij.new.interceptor.mcp-loop :as loop])
           (require '[claij.test.openrouter-simple :as or])
           (require '[clojure.data.json :as json])
           
           ;; Setup mock server
           (println \"Starting mock MCP server...\")
           (def bridge (mcp-api/initialize-bridge-with-dsl
                         {:command \"clojure\"
                          :args [\"-M\" \"-m\" \"claij.mcp.mock-server\"]
                          :transport \"stdio\"}
                         'mock-server))
           (println \"✓ Server started:\")
           (println \"  Tools:\" (mapv :name (:tools bridge)))
           
           ;; Create LLM function
           (def api-key (or (System/getenv \"OPENROUTER_API_KEY\")
                           (throw (ex-info \"OPENROUTER_API_KEY not set\" {}))))
           
           (defn llm-fn [prompts]
             (println \"\\n=== LLM Call ===\")
             (let [resp (or/call-openrouter
                          api-key
                          \"anthropic/claude-3.5-sonnet\"
                          (:system prompts)
                          (:user prompts)
                          {:response_format
                           {:type \"json_schema\"
                            :json_schema
                            {:name \"response\"
                             :schema
                             {:type \"object\"
                              :properties
                              {:answer {:type \"string\"}
                               :state {:type \"string\" :enum [\"thinking\" \"ready\"]}
                               :mcp {:type \"array\" :items {:type \"string\"}}}
                              :required [\"answer\" \"state\"]}}}})
                   parsed (json/read-str (:content resp) :key-fn keyword)]
               (println \"State:\" (:state parsed))
               (when (:mcp parsed) (println \"MCP:\" (:mcp parsed)))
               parsed))
           
           ;; Run test
           (println \"\\n=== Running Test ===\")
           (def result (loop/run-with-mcp-loop
                        llm-fn
                        \"Use the mock-server/add tool to calculate 15 + 27. Fill in the blank: The answer is ___.\"
                        [loop/mcp-loop-interceptor]
                        {}
                        {:system-prompt \"You are a helpful assistant.\"
                         :max-iterations 5}))
           
           (println \"\\n=== RESULT ===\")
           (println \"Answer:\" (:answer result))
           (println \"State:\" (:state result))
           
           ;; Verify
           (assert (= \"ready\" (:state result)))
           (assert (re-find #\"42\" (:answer result)))
           
           (println \"\\n✓ TEST PASSED!\")
           (System/exit 0))"))

(println (:out result))
(when (not= 0 (:exit result))
  (println "STDERR:" (:err result))
  (System/exit 1))
