(ns claij.mcp.hat
  "MCP Hat - gives LLM states access to MCP tools.
   
   Usage in FSM definition:
   {\"id\" \"mc\"
    \"action\" \"llm\"
    \"hats\" [\"mcp\"]}
   
   The hat generates:
   - A service state for tool execution
   - Loopback transitions with tool schemas
   - Prompts describing available tools"
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [claij.hat :as hat]
   [claij.mcp.bridge :as bridge]
   [claij.mcp.client :as client]))

;;------------------------------------------------------------------------------
;; Fragment Generation (private)
;;------------------------------------------------------------------------------

(defn- format-tools-list
  "Format tools list for LLM prompt."
  [tools]
  (str "Available tools:\n"
       (->> tools
            (map (fn [{n "name" d "description"}]
                   (str "- " n ": " (or d "No description"))))
            (str/join "\n"))))

(defn- generate-tool-prompts
  "Generate prompts describing available MCP tools."
  [tools]
  (if (empty? tools)
    ["No MCP tools available."]
    [(str "You have access to " (count tools) " MCP tools. "
          "To call a tool, emit a tool_calls array.")
     (format-tools-list tools)]))

(defn- generate-fragment
  "Generate FSM fragment for MCP hat.
   
   Creates:
   - Service state for tool execution
   - Loopback transitions (caller -> service -> caller)
   - Prompts listing available tools"
  [state-id service-id cache]
  (let [tools (get cache "tools" [])]
    {"states" [{"id" service-id
                "action" "mcp-service"}]
     "xitions" [{"id" [state-id service-id]
                 "schema" "mcp-request-schema"}
                {"id" [service-id state-id]
                 "schema" "mcp-response-schema"}]
     "prompts" (generate-tool-prompts tools)}))

;;------------------------------------------------------------------------------
;; MCP Hat Maker (public)
;;------------------------------------------------------------------------------

(defn mcp-hat-maker
  "Hat-maker for MCP tool access.
   
   Dynamic hat that:
   - First call: initializes bridge, populates cache, stores at [:hats :mcp]
   - Subsequent: reuses existing bridge
   
   Config options:
   - :config - MCP server config (defaults to bridge/default-mcp-config)
   - :timeout-ms - Init timeout (default 30000)"
  [state-id config]
  (let [service-id (str state-id "-mcp")]
    (fn [context]
      ;; Check if MCP already initialized
      (if-let [existing (get-in context [:hats :mcp])]
        ;; Reuse existing bridge - just generate fragment
        (do
          (log/info "mcp-hat: reusing existing bridge for state" state-id)
          [context
           (generate-fragment state-id service-id (:cache existing))])

        ;; First time - initialize bridge and cache
        (do
          (log/info "mcp-hat: initializing bridge for state" state-id)
          (let [mcp-config (or (:config config) bridge/default-mcp-config)
                timeout-ms (or (:timeout-ms config) 30000)
                {:keys [bridge cache]} (bridge/init-bridge mcp-config {:timeout-ms timeout-ms})
                ;; Store in context and add stop hook
                ctx' (-> context
                         (assoc-in [:hats :mcp] {:bridge bridge :cache cache})
                         (hat/add-stop-hook (fn [ctx]
                                              (log/info "mcp-hat: stopping bridge")
                                              (bridge/stop-bridge (get-in ctx [:hats :mcp :bridge]))
                                              ctx)))]
            [ctx' (generate-fragment state-id service-id cache)]))))))

;;------------------------------------------------------------------------------
;; MCP Service Action
;;------------------------------------------------------------------------------

(defn mcp-service-action
  "Action that routes tool calls to MCP bridge.
   
   Simplified version for hat usage - bridge already initialized at [:hats :mcp :bridge].
   
   Supports batched tool calls:
   - Single: {\"jsonrpc\" \"2.0\" ...}
   - Batch: [{...} {...}]
   
   Returns to caller state with response(s)."
  [_config _fsm ix _state]
  (fn [context event _trail handler]
    (let [{[from to] "id"} ix
          bridge (get-in context [:hats :mcp :bridge])
          ;; Event is the tool call request(s)
          requests (if (vector? event) event [event])
          ;; Execute via client batch API
          responses (client/call-batch bridge requests {:timeout-ms 30000})
          ;; Unwrap if single request
          result (if (vector? event) responses (first responses))]
      (log/info "mcp-service-action:" (count requests) "tool calls from" from)
      ;; Drain any notifications
      (bridge/drain-notifications bridge)
      ;; Return to caller state
      (handler context {"id" [to from] "message" result}))))
