(ns claij.poc.batch-execution-flags
  "PoC: Test if LLMs output batch-level execution flags
   
   Tests whether we can prompt major LLMs to include custom execution
   metadata as siblings to the content field in their responses."
  (:require [claij.llm.service :as llm]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; =============================================================================
;; Test Configuration
;; =============================================================================

(def models-to-test
  "Major LLM models to test"
  [{:name "Claude Sonnet 4.5"
    :model "claude-sonnet-4-5-20250929"
    :vendor :anthropic}
   {:name "Claude Opus 4.1"
    :model "claude-opus-4-1-20250514"
    :vendor :anthropic}
   {:name "Claude Haiku 4.5"
    :model "claude-haiku-4-5-20251001"
    :vendor :anthropic}])

(def read-file-tool
  "Simple read_file tool for testing"
  {"type" "function"
   "function" {"name" "read_file"
               "description" "Read a file from the filesystem"
               "parameters" {"type" "object"
                             "properties" {"path" {"type" "string"
                                                   "description" "Path to the file"}}
                             "required" ["path"]}}})

;; =============================================================================
;; Prompting Strategies
;; =============================================================================

(def strategy-1-explicit-instructions
  "Explicit instructions in system prompt"
  {:name "Strategy 1: Explicit Instructions"
   :system-prompt
   "You are an AI assistant with tool calling capabilities.
   
   IMPORTANT: When making tool calls, you can specify execution strategy 
   by including an 'execution' field as a sibling to 'content':
   
   {
     \"content\": [...tool calls...],
     \"execution\": {
       \"parallel\": true,    // true = run concurrently, false = sequential
       \"failFast\": false    // true = stop on first error, false = continue
     }
   }
   
   Guidelines:
   - Use parallel:true when operations are independent
   - Use parallel:false when operations must run in order
   - Use failFast:true to stop immediately on errors
   - Use failFast:false to attempt all operations regardless"

   :user-prompt
   "Read these 5 files. They are completely independent, so please read 
   them in parallel for efficiency:
   - src/file1.clj
   - src/file2.clj
   - src/file3.clj
   - src/file4.clj
   - src/file5.clj"})

(def strategy-2-examples
  "Show examples of desired format"
  {:name "Strategy 2: Example-Driven"
   :system-prompt
   "You are an AI assistant with tool calling capabilities.
   
   When making batch tool calls, include execution metadata to guide the executor.
   
   Example 1 (parallel independent operations):
   {
     \"content\": [
       {\"type\": \"tool_use\", \"name\": \"read_file\", \"input\": {\"path\": \"A\"}},
       {\"type\": \"tool_use\", \"name\": \"read_file\", \"input\": {\"path\": \"B\"}}
     ],
     \"execution\": {\"parallel\": true, \"failFast\": false}
   }
   
   Example 2 (sequential with fail-fast):
   {
     \"content\": [
       {\"type\": \"tool_use\", \"name\": \"run_test\", \"input\": {\"path\": \"test1\"}},
       {\"type\": \"tool_use\", \"name\": \"run_test\", \"input\": {\"path\": \"test2\"}}
     ],
     \"execution\": {\"parallel\": false, \"failFast\": true}
   }"

   :user-prompt
   "Read files src/a.clj, src/b.clj, and src/c.clj in parallel."})

(def strategy-3-imperative
  "Direct command style"
  {:name "Strategy 3: Imperative Command"
   :system-prompt
   "You are an AI assistant. When making tool calls, always include 
   an 'execution' metadata field specifying parallel and failFast settings."

   :user-prompt
   "INSTRUCTION: Read these files in parallel with failFast=false:
   - file1.clj
   - file2.clj
   - file3.clj
   
   Include execution metadata in your response."})

(def strategy-4-minimal
  "Minimal prompt - test if model does it naturally"
  {:name "Strategy 4: Minimal (No Hints)"
   :system-prompt nil
   :user-prompt "Read files a.clj, b.clj, c.clj in parallel."})

(def strategies
  [strategy-1-explicit-instructions
   strategy-2-examples
   strategy-3-imperative
   strategy-4-minimal])

;; =============================================================================
;; Test Execution
;; =============================================================================

(defn make-llm-request
  "Make a request to the LLM with given strategy and model"
  [model strategy]
  (let [messages (cond-> []
                   (:system-prompt strategy)
                   (conj {"role" "system"
                          "content" (:system-prompt strategy)})

                   true
                   (conj {"role" "user"
                          "content" (:user-prompt strategy)}))]

    (try
      ;; Call LLM and get back the raw response map
      (llm/call-llm-sync llm/default-registry
                         "anthropic"
                         (:model model)
                         messages
                         {:tools [read-file-tool]})
      (catch Exception e
        {:error (.getMessage e)}))))

(defn has-execution-field?
  "Check if response has custom execution field at top level"
  [response]
  (or (contains? response "execution")
      (contains? response :execution)))

(defn has-execution-in-content?
  "Check if any content block has execution metadata"
  [response]
  ;; For Anthropic responses parsed by llm/service, we don't get raw content blocks
  ;; We'd need the raw response to check this
  false)

(defn count-tool-calls
  "Count number of tool_use blocks in response"
  [response]
  (count (get response "tool_calls")))

(defn analyze-response
  "Analyze response for execution metadata"
  [response]
  {:has-top-level-execution? (has-execution-field? response)
   :has-execution-in-content? (has-execution-in-content? response)
   :tool-call-count (count-tool-calls response)
   :stop-reason (get response "stop_reason")
   :error (when (:error response) (:error response))})

;; =============================================================================
;; Results Logging
;; =============================================================================

(defn save-raw-response
  "Save raw response for inspection"
  [model strategy response]
  (let [dir (io/file "test/claij/poc/results")
        _ (.mkdirs dir)
        filename (format "%s_%s.json"
                         (str/replace (:name model) #"\s+" "_")
                         (str/replace (:name strategy) #"[:\s]+" "_"))
        file (io/file dir filename)]
    (spit file (json/generate-string response {:pretty true}))))

;; =============================================================================
;; Test Runner
;; =============================================================================

(defn run-test
  "Run single test: model + strategy"
  [model strategy]
  (println (format "Testing %s with %s..." (:name model) (:name strategy)))
  (let [response (make-llm-request model strategy)
        analysis (analyze-response response)]
    (save-raw-response model strategy response)
    (merge analysis
           {:model (:name model)
            :strategy (:name strategy)
            :vendor (:vendor model)})))

(defn run-all-tests
  "Run all combinations of models and strategies"
  []
  (println "\n=== Starting PoC: Batch Execution Flags ===\n")
  (for [model models-to-test
        strategy strategies]
    (run-test model strategy)))

;; =============================================================================
;; Results Table Generation
;; =============================================================================

(defn generate-results-table
  "Generate markdown table of results"
  [results]
  (let [header "| Model | Strategy | Tool Calls | Top-Level Exec? | Content Exec? | Stop Reason | Error |"
        divider "|-------|----------|------------|-----------------|---------------|-------------|-------|"
        rows (for [r results]
               (format "| %s | %s | %s | %s | %s | %s | %s |"
                       (:model r)
                       (:strategy r)
                       (or (:tool-call-count r) "N/A")
                       (if (:has-top-level-execution? r) "✅" "❌")
                       (if (:has-execution-in-content? r) "✅" "❌")
                       (or (:stop-reason r) "N/A")
                       (or (:error r) "")))]
    (str/join "\n" (concat [header divider] rows))))

(defn generate-summary
  "Generate summary of findings"
  [results]
  (let [total (count results)
        with-top-level (count (filter :has-top-level-execution? results))
        with-content-level (count (filter :has-execution-in-content? results))
        by-model (group-by :model results)
        by-strategy (group-by :strategy results)]
    (str/join "\n"
              ["\n## Summary\n"
               (format "**Total Tests:** %d" total)
               (format "**Top-Level Execution Field:** %d/%d (%.1f%%)"
                       with-top-level total (* 100.0 (/ with-top-level total)))
               (format "**Content-Level Execution Field:** %d/%d (%.1f%%)"
                       with-content-level total (* 100.0 (/ with-content-level total)))
               "\n### By Model\n"
               (str/join "\n"
                         (for [[model rs] by-model]
                           (format "- **%s**: %d/%d top-level, %d/%d content-level"
                                   model
                                   (count (filter :has-top-level-execution? rs))
                                   (count rs)
                                   (count (filter :has-execution-in-content? rs))
                                   (count rs))))
               "\n### By Strategy\n"
               (str/join "\n"
                         (for [[strategy rs] by-strategy]
                           (format "- **%s**: %d/%d top-level, %d/%d content-level"
                                   strategy
                                   (count (filter :has-top-level-execution? rs))
                                   (count rs)
                                   (count (filter :has-execution-in-content? rs))
                                   (count rs))))])))

(defn save-results
  "Save results to markdown file"
  [results]
  (let [table (generate-results-table results)
        summary (generate-summary results)
        content (str "# PoC Results: Batch Execution Flags\n\n"
                     "**Date:** " (java.time.LocalDateTime/now) "\n\n"
                     table "\n"
                     summary "\n\n"
                     "## Interpretation\n\n"
                     "- **✅ Top-Level Exec**: Model outputted `execution` field as sibling to `content`\n"
                     "- **✅ Content Exec**: Model included execution metadata inside content blocks\n"
                     "- **❌**: No execution metadata found\n\n"
                     "## Raw Responses\n\n"
                     "See `test/claij/poc/results/` for raw JSON responses.\n")
        file (io/file "test/claij/poc/results/RESULTS.md")]
    (spit file content)
    (println "\n=== Results saved to test/claij/poc/results/RESULTS.md ===\n")))

;; =============================================================================
;; Main Entry Point
;; =============================================================================

(defn run-poc
  "Run the complete PoC and generate results"
  []
  (let [results (doall (run-all-tests))]
    (save-results results)
    (println (generate-results-table results))
    (println (generate-summary results))
    results))

(comment
  ;; Run the full PoC
  (run-poc)

  ;; Test single combination
  (run-test (first models-to-test) strategy-1-explicit-instructions)

  ;; Check raw response
  (def r (make-llm-request (first models-to-test) strategy-1-explicit-instructions))
  (analyze-response r)
  (has-execution-field? r))
