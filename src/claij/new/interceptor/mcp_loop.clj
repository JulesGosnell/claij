(ns claij.new.interceptor.mcp-loop
  "MCP interceptor with multi-turn loop support.
  
  Enables LLMs to call MCP tools and incorporate results in the same user turn
  by running multiple LLM inference cycles."
  (:require [claij.new.schema :refer [add-property]]
            [claij.dsl.mcp.introspect :as introspect]
            [clojure.string :as str]))

;; =============================================================================
;; MCP Execution
;; =============================================================================

(defn- execute-mcp-code
  "Execute a single piece of MCP DSL code."
  [code-str]
  (try
    (let [result (load-string code-str)]
      {:success true
       :result result})
    (catch Exception e
      {:success false
       :error (.getMessage e)
       :code code-str})))

(defn- execute-mcp-codes
  "Execute multiple MCP DSL code snippets."
  [codes]
  (mapv execute-mcp-code codes))

;; =============================================================================
;; Prompt Formatting
;; =============================================================================

(defn- format-mcp-prompt
  "Generate prompt explaining MCP capabilities."
  []
  (let [api-docs (introspect/format-all-mcp-apis)]
    (str "MCP TOOL ACCESS:\n\n"
         "You have access to external tools via MCP (Model Context Protocol).\n"
         "To call MCP tools, place Clojure code in the 'mcp' field of your response.\n\n"
         "Available MCP Services:\n"
         api-docs
         "\n\n"
         "How to use:\n"
         "1. Set state=\"thinking\" when you need to call tools\n"
         "2. Add Clojure function calls to the 'mcp' array\n"
         "3. Results will be provided immediately and you'll be called again\n"
         "4. Use results to formulate your final answer\n"
         "5. Set state=\"ready\" when you have the final answer\n\n"
         "Example workflow:\n"
         "Turn 1 (you):\n"
         "{\n"
         "  \"answer\": \"I'll call the tool to get that information\",\n"
         "  \"state\": \"thinking\",\n"
         "  \"mcp\": [\"(mock-server/echo \\\"Hello\\\")\"]\n"
         "}\n\n"
         "Turn 1 (system shows you results):\n"
         "[0] SUCCESS: {:result \"Hello\"}\n\n"
         "Turn 2 (you):\n"
         "{\n"
         "  \"answer\": \"The tool returned 'Hello'\",\n"
         "  \"state\": \"ready\"\n"
         "}\n\n"
         "IMPORTANT:\n"
         "- Use state=\"thinking\" when calling MCP tools\n"
         "- Use state=\"ready\" when you have the final answer\n"
         "- You'll be called again after MCP execution to see results\n")))

(defn- format-mcp-results
  "Format MCP execution results for inclusion in prompt."
  [results]
  (when (seq results)
    (str "\n\nMCP TOOL RESULTS FROM YOUR PREVIOUS TURN:\n"
         (str/join "\n"
                   (map-indexed
                    (fn [idx result]
                      (if (:success result)
                        (str "[" idx "] SUCCESS: " (pr-str (:result result)))
                        (str "[" idx "] ERROR: " (:error result) 
                             "\n    Code: " (:code result))))
                    results))
         "\n\n"
         "Please incorporate these results into your final answer.\n")))

;; =============================================================================
;; Interceptor Definition
;; =============================================================================

(def mcp-loop-interceptor
  "MCP interceptor with multi-turn loop support.
  
  Runs multiple LLM inference cycles within a single user turn:
  1. LLM generates response with mcp calls + state='thinking'
  2. Execute MCP code
  3. Call LLM again with results
  4. LLM generates final response with state='ready'
  
  This happens transparently - user only sees final answer."
  
  {:name "mcp-loop-interceptor"
   
   ;; Add mcp field to schema
   :pre-schema
   (fn [schema ctx]
     (let [updated-schema (-> schema
                              (add-property :mcp
                                            {:type "array"
                                             :items {:type "string"}
                                             :description "Clojure DSL code for MCP tool calls"}))]
       [updated-schema ctx]))
   
   ;; Add MCP explanation to prompt
   :pre-prompt
   (fn [prompts ctx]
     (let [mcp-explanation (format-mcp-prompt)
           mcp-results (get ctx :mcp-results)
           results-text (when mcp-results
                          (format-mcp-results mcp-results))
           updated-system (str (:system prompts)
                               "\n\n"
                               mcp-explanation
                               (or results-text ""))]
       [{:system updated-system
         :user (:user prompts)}
        ctx]))
   
   ;; Loop logic: if state="thinking" and mcp code, execute and loop
   :post-response
   (fn [response ctx]
     (let [state (:state response)
           mcp-codes (:mcp response)]
       
       ;; If thinking state with MCP calls, execute and signal for loop
       (if (and (= "thinking" state) (seq mcp-codes))
         (let [results (execute-mcp-codes mcp-codes)]
           (-> ctx
               (assoc :mcp-results results)
               (assoc :continue-loop true))) ; Signal to call LLM again
         
         ;; Otherwise, clear results and don't loop
         (-> ctx
             (dissoc :mcp-results)
             (dissoc :continue-loop)))))})

;; =============================================================================
;; Loop Runner
;; =============================================================================

(defn should-continue-loop?
  "Check if we should continue the MCP loop."
  [ctx]
  (:continue-loop ctx))

(defn run-with-mcp-loop
  "Run LLM with MCP loop support.
  
  Repeatedly calls LLM until state='ready' or max iterations reached.
  
  Parameters:
  - llm-fn: Function that takes prompts and returns response
  - user-message: User's message
  - interceptors: Vector of interceptors (should include mcp-loop-interceptor)
  - initial-ctx: Initial context map
  - opts: Options map with:
    - :max-iterations (default 5) - Max MCP loop iterations
    - :system-prompt - System prompt to use
  
  Returns:
  - Final response map with all MCP interactions hidden from user"
  [llm-fn user-message interceptors initial-ctx opts]
  (let [max-iterations (get opts :max-iterations 5)
        system-prompt (get opts :system-prompt "You are a helpful assistant.")]
    
    (loop [iteration 0
           ctx initial-ctx
           prompts {:system system-prompt
                    :user user-message}]
      
      (if (>= iteration max-iterations)
        ;; Max iterations reached, return last response
        (do
          (println "WARNING: Max MCP iterations reached")
          {:answer "I encountered an issue processing your request."
           :state "error"
           :error "Max MCP iterations reached"})
        
        ;; Run one iteration
        (let [;; Apply pre-schema interceptors
              [schema ctx] (reduce
                            (fn [[s c] interceptor]
                              (if-let [pre-schema (:pre-schema interceptor)]
                                (pre-schema s c)
                                [s c]))
                            [{} ctx]
                            interceptors)
              
              ;; Apply pre-prompt interceptors
              [prompts ctx] (reduce
                             (fn [[p c] interceptor]
                               (if-let [pre-prompt (:pre-prompt interceptor)]
                                 (pre-prompt p c)
                                 [p c]))
                             [prompts ctx]
                             interceptors)
              
              ;; Call LLM
              response (llm-fn prompts)
              
              ;; Apply post-response interceptors
              ctx (reduce
                   (fn [c interceptor]
                     (if-let [post-response (:post-response interceptor)]
                       (post-response response c)
                       c))
                   ctx
                   interceptors)]
          
          ;; Check if we should continue looping
          (if (should-continue-loop? ctx)
            (do
              (println (str "MCP iteration " (inc iteration) ": Executed "
                           (count (:mcp response)) " tool calls, continuing..."))
              (recur (inc iteration)
                     (dissoc ctx :continue-loop) ; Clear flag for next iteration
                     prompts)) ; Keep same user message
            
            ;; Done looping, return final response
            response))))))

(comment
  ;; Example usage
  (require '[claij.dsl.mcp.api :as mcp-api])
  
  ;; Initialize MCP bridge
  (mcp-api/initialize-bridge-with-dsl
   {:command "clojure"
    :args ["-M" "-m" "claij.mcp.mock-server"]
    :transport "stdio"}
   'mock-server)
  
  ;; Mock LLM that uses MCP
  (defn mock-llm [prompts]
    (if (re-find #"MCP TOOL RESULTS" (:system prompts))
      ;; Second call - we have results
      {:answer "The result was 42!"
       :state "ready"}
      ;; First call - make MCP request
      {:answer "Let me calculate that..."
       :state "thinking"
       :mcp ["(mock-server/add 20 22)"]}))
  
  ;; Run with loop
  (run-with-mcp-loop
   mock-llm
   "What is 20 + 22?"
   [mcp-loop-interceptor]
   {}
   {:system-prompt "You are a calculator assistant."}))
