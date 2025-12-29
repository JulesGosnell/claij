(ns mcp-hat-demo
  "Demonstration of MCP Hat with Grok for tool calling.
   
   This example shows:
   - Minimal FSM with LLM state wearing MCP hat
   - Grok (x-ai) provider for tool-calling
   - MCP bridge initialization and tool execution
   - Multiple MCP server configurations (claij-tools, GitHub)
   - Multi-server support: using multiple MCP servers in a single state
   
   Examples:
   1. Hostname demo - single server (claij-tools default)
   2. GitHub demo - single server (GitHub MCP)
   3. Multi-server demo - GitHub + claij-tools in one state
   
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
  (require '[claij.actions :refer [end-action]])
  (require '[claij.model :as model]))

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
   :llm/model (model/direct-model :xai)
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
   :llm/model (model/direct-model :xai)
   :hats {:registry (-> (claij.hat/make-hat-registry)
                        (claij.hat/register-hat "mcp" claij.hat.mcp/mcp-hat-maker))}})

;;------------------------------------------------------------------------------
;; Example 3: Multi-server demo (GitHub + claij-tools together)
;;------------------------------------------------------------------------------

;; claij-tools default config
(def claij-tools-config
  claij.mcp.bridge/default-mcp-config)

(def multi-server-fsm
  {"id" "multi-server-demo"
   "states" [{"id" "ask"
              "action" "llm"
              ;; Multiple servers in one hat - enables cross-server batching!
              "hats" [{"mcp" {:servers {"github" {:config github-mcp-config}
                                        "tools" {:config claij-tools-config}}}}]
              "prompts" ["You are a helpful assistant with access to multiple MCP servers:
- 'github': GitHub API tools (list_issues, create_pull_request, etc.)
- 'tools': Local system tools (bash, read_file, clojure_eval, etc.)

You can call tools from multiple servers in a SINGLE request using the 'calls' format.
Group your tool calls by server - this is more efficient than separate requests.
When you have the final answer, respond with id=[\"ask\", \"end\"]."]}
             {"id" "end"
              "action" "end"}]
   "xitions" [{"id" ["start" "ask"] "omit" true}
              {"id" ["ask" "end"]
               "schema" [:map
                         ["id" [:= ["ask" "end"]]]
                         ["result" :string]]}]})

(def multi-server-context
  {:id->action {"llm" llm-action
                "end" (var claij.actions/end-action)}
   :llm/provider "x-ai"
   :llm/model (model/direct-model :xai)
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
  ((:stop fsm))

  ;; ============================================================
  ;; Example 3: Multi-server (GitHub + tools in one state)
  ;; ============================================================
  ;; This demonstrates using multiple MCP servers simultaneously.
  ;; The LLM can use GitHub tools AND local bash/file tools in the same conversation.

  (def fsm (claij.fsm/start-fsm multi-server-context multi-server-fsm))
  ((:submit fsm) {"question" "List the open issues in JulesGosnell/claij and tell me my hostname"})
  (def result ((:await fsm) 180000))
  (println "Result:" result)
  ((:stop fsm)))
