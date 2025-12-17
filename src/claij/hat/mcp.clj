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
   [claij.mcp.bridge :as bridge]
   [claij.mcp.schema :as mcp-schema]))

;; Forward declaration for mcp-hat-maker to reference
(declare mcp-service-action)

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
;; Schema Functions (hat-aware)
;;==============================================================================

(defn hat-mcp-request-schema-fn
  "Schema function for hat-based MCP requests.
   Looks up cache at [:hats :mcp :cache]."
  [context {xid "id" :as _xition}]
  (let [cache (get-in context [:hats :mcp :cache])
        single-request-schema (mcp-schema/mcp-cache->request-schema cache)
        batch-request-schema [:vector single-request-schema]]
    [:map {:closed true}
     ["id" [:= xid]]
     ["message" [:or single-request-schema batch-request-schema]]]))

(defn hat-mcp-response-schema-fn
  "Schema function for hat-based MCP responses.
   Looks up cache at [:hats :mcp :cache]."
  [context {xid "id" :as _xition}]
  (let [cache (get-in context [:hats :mcp :cache])
        single-response-schema (mcp-schema/mcp-cache->response-schema cache)
        batch-response-schema [:vector single-response-schema]]
    [:map {:closed true}
     ["id" [:= xid]]
     ["document" {:optional true} :string]
     ["message" {:optional true} [:or single-response-schema batch-response-schema]]]))

;;==============================================================================
;; MCP Hat Maker
;;==============================================================================

(defn mcp-hat-maker
  "Hat-maker for MCP tool access.
   
   Creates a dynamic hat that:
   - Starts MCP bridge on first use (or reuses existing)
   - Generates service state for tool calls
   - Registers mcp-service-action in :id->action
   - Adds stop-hook for bridge cleanup
   - Registers schema functions for request/response validation
   
   Config options:
   - :config - MCP server config (defaults to claij tools server)
   - :timeout-ms - Initialization timeout (default 30000)"
  [state-id config]
  (let [service-id (str state-id "-mcp")
        mcp-config (or (:config config) bridge/default-mcp-config)
        timeout-ms (or (:timeout-ms config) 30000)
        ;; Schema IDs are state-specific to avoid conflicts
        request-schema-id (str state-id "-mcp-request")
        response-schema-id (str state-id "-mcp-response")]
    (fn [context]
      ;; Check for existing bridge
      (if-let [existing-bridge (get-in context [:hats :mcp :bridge])]
        ;; Reuse existing bridge
        (do
          (log/info "MCP hat: reusing existing bridge for" state-id)
          (let [cache (get-in context [:hats :mcp :cache])
                tools (get cache "tools" [])
                ;; Register schema functions and action for this state
                ctx' (-> context
                         (update :id->schema merge
                                 {request-schema-id hat-mcp-request-schema-fn
                                  response-schema-id hat-mcp-response-schema-fn})
                         (update :id->action assoc "mcp-service" mcp-service-action))]
            [ctx'
             {"states" [{"id" service-id
                         "action" "mcp-service"}]
              "xitions" [{"id" [state-id service-id]
                          "schema" request-schema-id}
                         {"id" [service-id state-id]
                          "schema" response-schema-id}]
              "prompts" [(format-tools-prompt tools)]}]))

        ;; Initialize new bridge
        (do
          (log/info "MCP hat: initializing bridge for" state-id)
          (let [{:keys [bridge cache]} (bridge/init-bridge mcp-config {:timeout-ms timeout-ms})
                tools (get cache "tools" [])
                ;; Add bridge, cache, action, and schema functions to context
                ctx' (-> context
                         (assoc-in [:hats :mcp :bridge] bridge)
                         (assoc-in [:hats :mcp :cache] cache)
                         ;; Register schema functions
                         (update :id->schema merge
                                 {request-schema-id hat-mcp-request-schema-fn
                                  response-schema-id hat-mcp-response-schema-fn})
                         ;; Register the mcp-service action
                         (update :id->action assoc "mcp-service" mcp-service-action)
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
                          "schema" request-schema-id}
                         {"id" [service-id state-id]
                          "schema" response-schema-id}]
              "prompts" [(format-tools-prompt tools)]}]))))))

;;==============================================================================
;; MCP Service Action
;;==============================================================================

(defn mcp-service-action
  "Action for MCP service state. Routes tool calls to bridge.
   
   Expects bridge at [:hats :mcp :bridge] in context.
   Handles single or batched tool calls.
   
   This is a curried action: call with config to get the action function."
  [_config _fsm _ix {state-id "id" :as _state}]
  (fn [context event _trail handler]
    (let [bridge (get-in context [:hats :mcp :bridge])
          [from _to] (get event "id")
          message (get event "message")]
      (log/info "mcp-service-action:" state-id "from" from "message:" (pr-str message))
      (if-not bridge
        ;; No bridge - error
        (do
          (log/error "mcp-service-action: no bridge in context")
          (handler context {"id" [state-id from]
                            "message" {"error" "No MCP bridge"}}))
        ;; Execute tool calls
        (let [;; Normalize to batch format
              requests (if (vector? message) message [message])
              ;; Execute batch
              responses (bridge/send-and-await bridge requests 30000)
              ;; If single request, unwrap the response
              result (if (vector? message) responses (first responses))]
          ;; Drain notifications
          (bridge/drain-notifications bridge)
          ;; Return to calling state
          (handler context {"id" [state-id from]
                            "message" result}))))))
