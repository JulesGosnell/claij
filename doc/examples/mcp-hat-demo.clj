;; Minimal MCP Hat Demo for REPL
;; 
;; This demonstrates Grok (or any LLM) using MCP tools via the hat system.
;; Copy and paste sections into your REPL.
;;
;; The FSM has a single LLM state with an MCP hat that provides tool access.
;; When you ask a question requiring system info, the LLM will:
;; 1. Call the bash tool via MCP
;; 2. Get the result back
;; 3. Return the final answer

;;==============================================================================
;; 1. Setup - Load namespaces
;;==============================================================================

(require '[claij.fsm :refer [start-fsm]]
         '[claij.hat :as hat]
         '[claij.hat.mcp :as mcp-hat])

;; Import the var (not the value) so metadata is preserved
(def llm-action (var claij.fsm/llm-action))

;;==============================================================================
;; 2. Define a minimal FSM
;;==============================================================================

(def hostname-fsm
  {"id" "hostname-demo"
   "states" [{"id" "ask"
              "action" "llm"
              "hats" ["mcp"]
              "prompts" ["You are a helpful assistant with access to MCP tools.
When asked a question that requires system information, use the bash tool.
When you have the final answer, respond with id=[\"ask\" \"end\"] and include your answer in the result field."]}
             {"id" "end"}]
   "xitions" [{"id" ["start" "ask"]
               "omit" true}
              {"id" ["ask" "end"]
               "schema" [:map
                         ["id" [:= ["ask" "end"]]]
                         ["result" :string]]}]})

;;==============================================================================
;; 3. Create context with hat registry
;;
;; Change :llm/provider and :llm/model to test different LLMs:
;;   - "x-ai" / "grok-code-fast-1"
;;   - "anthropic" / "claude-sonnet-4-20250514"
;;   - "google" / "gemini-2.0-flash"
;;   - "openai" / "gpt-4o-mini"
;;==============================================================================

(def context
  {:id->action {"llm" llm-action}
   :llm/provider "x-ai"
   :llm/model "grok-code-fast-1"
   :hats {:registry (-> (hat/make-hat-registry)
                        (hat/register-hat "mcp" mcp-hat/mcp-hat-maker))}})

;;==============================================================================
;; 4. Run the FSM
;;==============================================================================

(println "Starting FSM...")
(def fsm-instance (start-fsm context hostname-fsm))

;; Submit a question
((:submit fsm-instance) {"question" "What is my hostname?"})

;; Wait for result (up to 120 seconds for tool calls)
(println "Waiting for result...")
(def result ((:await fsm-instance) 120000))

(println "\n=== RESULT ===")
(if (= result :timeout)
  (println "FSM timed out")
  (println result))

;; Clean up
((:stop fsm-instance))
(println "Done.")

;;==============================================================================
;; Alternative questions to try:
;;   {"question" "What is today's date?"}
;;   {"question" "List the files in /tmp"}
;;   {"question" "What Clojure version is installed?"}
;;==============================================================================
