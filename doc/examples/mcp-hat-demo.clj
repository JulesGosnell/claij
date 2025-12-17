(ns mcp-hat-demo
  "Demonstration of MCP Hat with Grok for tool calling.
   
   This example shows:
   - Minimal FSM with LLM state wearing MCP hat
   - Grok (x-ai) provider for tool-calling
   - MCP bridge initialization and tool execution
   - Multiple MCP server configurations (claij-tools, GitHub)
   
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
;; MCP Server Configurations
;;------------------------------------------------------------------------------

;; Default: claij-clojure-tools (bash, file operations, clojure eval, etc.)
;; No config needed - uses hardcoded default in mcp-hat-maker

;; GitHub MCP server - access GitHub API (issues, PRs, repos, etc.)
(def github-mcp-config
  {"command" "npx"
   "args" ["-y" "@modelcontextprotocol/server-github"]
   "transport" "stdio"
   "env" {"GITHUB_PERSONAL_ACCESS_TOKEN" (System/getenv "GITHUB_PERSONAL_ACCESS_TOKEN")}})

;;------------------------------------------------------------------------------
;; Example 1: Hostname demo (claij-tools default)
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

(def hostname-context
  {:id->action {"llm" llm-action
                "end" (var claij.actions/end-action)}
   :llm/provider "x-ai"
   :llm/model "grok-code-fast-1"
   :hats {:registry (-> (claij.hat/make-hat-registry)
                        (claij.hat/register-hat "mcp" claij.hat.mcp/mcp-hat-maker))}})

;;------------------------------------------------------------------------------
;; Example 2: GitHub demo (GitHub MCP server)
;;------------------------------------------------------------------------------

(def github-fsm
  {"id" "github-demo"
   "states" [{"id" "ask"
              "action" "llm"
              "hats" [{"mcp" {:config github-mcp-config}}]
              "prompts" ["You are a helpful assistant with access to GitHub via MCP tools.
Use the available GitHub tools to answer questions about repositories.
When you have the final answer, respond with id=[\"ask\", \"end\"] and include your answer in the result field."]}
             {"id" "end"
              "action" "end"}]
   "xitions" [{"id" ["start" "ask"] "omit" true}
              {"id" ["ask" "end"]
               "schema" [:map
                         ["id" [:= ["ask" "end"]]]
                         ["result" :string]]}]})

(def github-context
  {:id->action {"llm" llm-action
                "end" (var claij.actions/end-action)}
   :llm/provider "x-ai"
   :llm/model "grok-code-fast-1"
   :hats {:registry (-> (claij.hat/make-hat-registry)
                        (claij.hat/register-hat "mcp" claij.hat.mcp/mcp-hat-maker))}})

;;------------------------------------------------------------------------------
;; Run the demos
;;------------------------------------------------------------------------------

(comment
  ;; ============================================================
  ;; Example 1: Hostname (claij-tools)
  ;; ============================================================

  (def fsm (claij.fsm/start-fsm hostname-context hostname-fsm))
  ((:submit fsm) {"question" "What is my hostname?"})
  (def result ((:await fsm) 120000))
  (println "Result:" result)
  ((:stop fsm))

  ;; ============================================================
  ;; Example 2: GitHub issues count
  ;; ============================================================

  (def fsm (claij.fsm/start-fsm github-context github-fsm))
  ((:submit fsm) {"question" "How many open issues are in the JulesGosnell/claij repository?"})
  (def result ((:await fsm) 120000))
  (println "Result:" result)
  ((:stop fsm)))
