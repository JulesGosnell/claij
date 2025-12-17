(ns claij.hat.mcp
  "MCP Hat - gives LLM states access to MCP tools.
   
   Usage in FSM definition:
   {\"id\" \"mc\"
    \"action\" \"llm\"
    \"hats\" [\"mcp\"]}
   
   Or with config:
   {\"hats\" [{\"mcp\" {:services [\"my-server\"]}}]}"
  (:require
   [clojure.tools.logging :as log]
   [claij.hat :as hat]
   [claij.mcp.bridge :as bridge]))

;;==============================================================================
;; Tool Prompt Generation
;;==============================================================================

(defn format-tool-schema
  "Format a single tool's schema for LLM consumption."
  [{:strs [name description inputSchema]}]
  (str "- " name ": " (or description "No description")
       (when inputSchema
         (str "\n  Input: " (pr-str inputSchema)))))

(defn format-tools-prompt
  "Generate prompt text describing available MCP tools."
  [tools]
  (if (seq tools)
    (str "You have access to the following MCP tools:\n\n"
         (clojure.string/join "\n\n" (map format-tool-schema tools))
         "\n\nTo call a tool, output a JSON object with:\n"
         "  {\"tool_calls\": [{\"name\": \"<tool>\", \"arguments\": {...}}]}")
    "No MCP tools available."))

;;==============================================================================
;; MCP Hat Maker
;;==============================================================================

(defn mcp-hat-maker
  "Hat-maker for MCP tool access.
   
   Creates a dynamic hat that:
   - Starts MCP bridge on first use (or reuses existing)
   - Generates service state for tool calls
   - Adds stop-hook for bridge cleanup
   
   Config options:
   - :config - MCP server config (defaults to claij tools server)
   - :timeout-ms - Initialization timeout (default 30000)"
  [state-id config]
  (let [service-id (str state-id "-mcp")
        mcp-config (or (:config config) bridge/default-mcp-config)
        timeout-ms (or (:timeout-ms config) 30000)]
    (fn [context]
      ;; Check for existing bridge
      (if-let [existing-bridge (get-in context [:hats :mcp :bridge])]
        ;; Reuse existing bridge
        (do
          (log/info "MCP hat: reusing existing bridge for" state-id)
          (let [cache (get-in context [:hats :mcp :cache])
                tools (get cache "tools" [])]
            [context
             {"states" [{"id" service-id
                         "action" "mcp-service"}]
              "xitions" [{"id" [state-id service-id]
                          "schema" "mcp-request-xition"}
                         {"id" [service-id state-id]
                          "schema" "mcp-response-xition"}]
              "prompts" [(format-tools-prompt tools)]}]))

        ;; Initialize new bridge
        (do
          (log/info "MCP hat: initializing bridge for" state-id)
          (let [{:keys [bridge cache]} (bridge/init-bridge mcp-config {:timeout-ms timeout-ms})
                tools (get cache "tools" [])
                ;; Add bridge and cache to context
                ctx' (-> context
                         (assoc-in [:hats :mcp :bridge] bridge)
                         (assoc-in [:hats :mcp :cache] cache)
                         ;; Add stop hook for cleanup
                         (hat/add-stop-hook
                          (fn [ctx]
                            (log/info "MCP hat: stopping bridge")
                            (bridge/stop-bridge (get-in ctx [:hats :mcp :bridge]))
                            (update ctx :hats dissoc :mcp))))]
            [ctx'
             {"states" [{"id" service-id
                         "action" "mcp-service"}]
              "xitions" [{"id" [state-id service-id]
                          "schema" "mcp-request-xition"}
                         {"id" [service-id state-id]
                          "schema" "mcp-response-xition"}]
              "prompts" [(format-tools-prompt tools)]}]))))))

;;==============================================================================
;; MCP Service Action
;;==============================================================================

(defn mcp-service-action
  "Action for MCP service state. Routes tool calls to bridge.
   
   Expects bridge at [:hats :mcp :bridge] in context.
   Handles single or batched tool calls."
  [_config _fsm _ix _state]
  (fn [context event _trail handler]
    (let [bridge (get-in context [:hats :mcp :bridge])
          message (get event "message")
          ;; Normalize to batch format
          requests (if (vector? message) message [message])
          ;; Execute batch
          responses (bridge/send-and-await bridge requests 30000)
          ;; If single request, unwrap the response
          result (if (vector? message) responses (first responses))]
      ;; Drain notifications
      (bridge/drain-notifications bridge)
      ;; Return to calling state
      (let [[from _to] (get-in event ["id"])
            response-event {"id" [(get-in _state ["id"]) from]
                            "message" result}]
        (handler context response-event)))))
