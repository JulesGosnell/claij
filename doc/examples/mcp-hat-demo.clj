(ns mcp-hat-demo
  "Demonstration of MCP Hat with Grok for tool calling.
   
   This example shows:
   - Minimal FSM with LLM state wearing MCP hat
   - Grok (x-ai) provider for tool-calling
   - MCP bridge initialization and tool execution
   
   Usage: Evaluate forms in order in your REPL.")

;;------------------------------------------------------------------------------
;; Setup - reload all relevant namespaces
;;------------------------------------------------------------------------------

(comment
  ;; Reload namespaces
  (require '[claij.fsm :refer [start-fsm]] :reload-all)
  (require '[claij.hat :as hat] :reload)
  (require '[claij.hat.mcp :as mcp-hat] :reload)
  (require '[claij.mcp.schema] :reload)
  (require '[claij.actions :refer [end-action]]))

;;------------------------------------------------------------------------------
;; Action vars (preserve metadata for curried action detection)
;;------------------------------------------------------------------------------

(def llm-action (var claij.fsm/llm-action))

;;------------------------------------------------------------------------------
;; FSM Definition
;;------------------------------------------------------------------------------

(def hostname-fsm
  {"id" "hostname-demo"
   "states" [{"id" "ask"
              "action" "llm"
              "hats" ["mcp"]
              "prompts" ["You are a helpful assistant with access to MCP tools.
When asked a question that requires system information, use the bash tool.
When you have the final answer, respond with id=[\"ask\", \"end\"] and include your answer in the result field."]}
             {"id" "end"
              "action" "end"}]
   "xitions" [{"id" ["start" "ask"] "omit" true}
              {"id" ["ask" "end"]
               "schema" [:map
                         ["id" [:= ["ask" "end"]]]
                         ["result" :string]]}]})

;;------------------------------------------------------------------------------
;; Context with Grok provider and MCP hat
;;------------------------------------------------------------------------------

(def context
  {:id->action {"llm" llm-action
                "end" (var claij.actions/end-action)}
   :llm/provider "x-ai"
   :llm/model "grok-code-fast-1"
   :hats {:registry (-> (claij.hat/make-hat-registry)
                        (claij.hat/register-hat "mcp" claij.hat.mcp/mcp-hat-maker))}})

;;------------------------------------------------------------------------------
;; Run the demo
;;------------------------------------------------------------------------------

(comment
  ;; Start FSM
  (def fsm (start-fsm context hostname-fsm))

  ;; Submit question
  ((:submit fsm) {"question" "What is my hostname?"})

  ;; Wait for result (2 minute timeout)
  (def result ((:await fsm) 120000))

  ;; Print result
  (println "Result:" result)

  ;; Clean up
  ((:stop fsm)))
