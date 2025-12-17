(ns claij.mcp.hat
  "MCP hat - gives LLM states access to MCP tools.
   
   Usage in FSM:
   {\"id\" \"mc\"
    \"action\" \"llm\"
    \"hats\" [\"mcp\"]}
   
   The hat:
   1. Initializes MCP bridge (if not already present)
   2. Populates tool cache
   3. Generates loopback states for tool calls
   4. Registers stop-hook for cleanup"
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [claij.hat :as hat]
   [claij.mcp.bridge :as bridge]))

;;------------------------------------------------------------------------------
;; Fragment Generation (private)
;;------------------------------------------------------------------------------

(defn- make-tool-prompts
  "Generate prompts describing available MCP tools."
  [tools]
  (if (empty? tools)
    ["No MCP tools available."]
    [(str "You have access to " (count tools) " MCP tools. "
          "To call a tool, respond with a tool_calls array.")
     (str "Available tools: "
          (->> tools
               (map #(get % "name"))
               (str/join ", ")))]))

(defn- make-fragment
  "Generate FSM fragment for MCP hat.
   
   Creates:
   - Service state for tool execution
   - Loopback transitions with schemas
   - Prompts describing available tools"
  [state-id service-id {:keys [cache]}]
  (let [tools (get cache "tools" [])]
    {"states" [{"id" service-id
                "action" "mcp-service"}]
     "xitions" [{"id" [state-id service-id]
                 "schema" "mcp-request-schema"}
                {"id" [service-id state-id]
                 "schema" "mcp-response-schema"}]
     "prompts" (make-tool-prompts tools)}))

;;------------------------------------------------------------------------------
;; MCP Hat Maker (public)
;;------------------------------------------------------------------------------

(defn mcp-hat-maker
  "Creates an MCP hat for a given state.
   
   Hat-maker signature: (fn [state-id config]) -> hat-fn
   Hat-fn signature: (fn [context]) -> [context' fragment]
   
   Config options:
   - :config - MCP server config (defaults to bridge/default-mcp-config)
   - :timeout-ms - Init timeout (default 30000)
   
   The hat is dynamic:
   - First invocation: initializes bridge, populates cache, adds stop-hook
   - Subsequent: reuses existing bridge from [:hats :mcp]"
  [state-id config]
  (let [service-id (str state-id "-mcp")
        mcp-config (or (:config config) bridge/default-mcp-config)
        timeout-ms (or (:timeout-ms config) 30000)]
    (fn [context]
      (if-let [existing (get-in context [:hats :mcp])]
        ;; Reuse existing bridge
        (do
          (log/info "MCP hat: reusing existing bridge for state" state-id)
          [context
           (make-fragment state-id service-id existing)])
        ;; Initialize new bridge
        (do
          (log/info "MCP hat: initializing bridge for state" state-id)
          (let [{:keys [bridge cache]} (bridge/init-bridge mcp-config {:timeout-ms timeout-ms})
                mcp-data {:bridge bridge :cache cache}
                ctx' (-> context
                         (assoc-in [:hats :mcp] mcp-data)
                         (hat/add-stop-hook
                          (fn [ctx]
                            (log/info "MCP hat: stopping bridge")
                            (bridge/stop-bridge (get-in ctx [:hats :mcp :bridge]))
                            ctx)))]
            [ctx' (make-fragment state-id service-id mcp-data)]))))))

;;------------------------------------------------------------------------------
;; MCP Service Action
;;------------------------------------------------------------------------------

(defn mcp-service-action
  "Action that routes tool calls to MCP bridge.
   
   Bridge already initialized at [:hats :mcp :bridge] by mcp-hat-maker.
   
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
          ;; Execute via bridge batch API
          responses (bridge/send-and-await bridge requests 30000)
          ;; Unwrap if single request
          result (if (vector? event) responses (first responses))]
      (log/info "mcp-service-action:" (count requests) "tool calls from" from)
      ;; Drain any notifications
      (bridge/drain-notifications bridge)
      ;; Return to caller state
      (handler context {"id" [to from] "message" result}))))
