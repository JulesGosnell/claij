(ns claij.hat.mcp
  "MCP Hat - gives LLM states access to MCP tools.
   
   Usage in FSM definition:
   {\"id\" \"mc\"
    \"action\" \"llm\"
    \"hats\" [\"mcp\"]}
   
   Or with single server config:
   {\"hats\" [{\"mcp\" {:config my-server-config}}]}
   
   Or with multiple servers:
   {\"hats\" [{\"mcp\" {:servers {\"github\" github-config
                                \"tools\" tools-config}}}]}
   
   ## Design Decision: Tool Routing (Option B vs Option A)
   
   The MCP community is converging on prefixed tool names (Option A) like
   `github__list_issues` because MCP's tool name regex forbids `/` or `:`.
   
   We chose Option B (structured `server` field) instead because:
   1. Cognitive grouping - structured server groupings help LLMs reason about
      tool relationships (\"I need GitHub functionality\" → look at github tools)
   2. Schema validation - [:enum \"github\" \"tools\"] catches routing errors at
      validation time, not dispatch time  
   3. Semantic clarity - the \"simplicity\" of flat names is illusory; complexity
      just moves to munging/parsing
   4. Cleaner prompts - grouped tool documentation by server is more readable
   
   See issue #72 for full discussion."
  (:require
   [clojure.tools.logging :as log]
   [claij.action :refer [def-action]]
   [claij.hat :as hat]
   [claij.mcp.bridge :as bridge]
   [claij.mcp.schema :as mcp-schema]))

;; Forward declaration for mcp-hat-maker to reference
(declare mcp-service-action)

;;==============================================================================
;; Config Normalization
;;==============================================================================

(defn normalize-mcp-config
  "Normalize various MCP hat config formats to canonical multi-server format.
   
   Input formats:
   - nil or {}           -> {:servers {\"default\" {:config default-mcp-config}}}
   - {:config cfg}       -> {:servers {\"default\" {:config cfg}}}
   - {:servers {...}}    -> passed through as-is
   
   Output format (canonical):
   {:servers {\"server-name\" {:config mcp-server-config
                              :timeout-ms 30000}
              ...}
    :timeout-ms 30000}  ;; global default
   
   Each server entry can have:
   - :config - MCP server config map (command, args, transport, env)
   - :timeout-ms - per-server timeout override"
  [config]
  (let [global-timeout (or (:timeout-ms config) 30000)]
    (cond
      ;; Already has :servers - pass through with defaults applied
      (:servers config)
      (-> config
          (update :servers
                  (fn [servers]
                    (reduce-kv
                     (fn [acc server-name server-config]
                       (assoc acc server-name
                              (-> server-config
                                  (update :config #(or % bridge/default-mcp-config))
                                  (update :timeout-ms #(or % global-timeout)))))
                     {}
                     servers)))
          (assoc :timeout-ms global-timeout))

      ;; Has :config - wrap as single "default" server
      (:config config)
      {:servers {"default" {:config (:config config)
                            :timeout-ms global-timeout}}
       :timeout-ms global-timeout}

      ;; Empty/nil config - use default server
      :else
      {:servers {"default" {:config bridge/default-mcp-config
                            :timeout-ms global-timeout}}
       :timeout-ms global-timeout})))

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
  "Generate prompt text describing available MCP tools grouped by server.
   
   Takes state-id, service-id, and tools-by-server map:
   {\"github\" [{\"name\" \"list_issues\" ...} ...]
    \"tools\"  [{\"name\" \"bash\" ...} ...]}
   
   For single-server configs, tools-by-server will have one key (\"default\")."
  [state-id service-id tools-by-server]
  (if (seq tools-by-server)
    (let [server-names (keys tools-by-server)
          single-server? (= 1 (count server-names))
          ;; Format tools grouped by server
          server-sections (for [[server-name tools] (sort-by first tools-by-server)]
                            (str (when-not single-server?
                                   (str "### Server: " server-name "\n"))
                                 (clojure.string/join "\n\n" (map format-tool-schema tools))))
          tools-text (clojure.string/join "\n\n" server-sections)
          ;; Build example based on single vs multi server
          example-server (first server-names)
          example-tool (get-in tools-by-server [example-server 0 "name"] "tool_name")]
      (str "## MCP Tools\n\n"
           tools-text
           "\n\n"
           "To call a tool, output JSON with the following structure:\n"
           "```json\n"
           "{\"id\": [\"" state-id "\", \"" service-id "\"],\n"
           " \"server\": \"" example-server "\",\n"
           " \"message\": {\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"tools/call\",\n"
           "             \"params\": {\"name\": \"" example-tool "\", \"arguments\": {...}}}}\n"
           "```\n\n"
           (when-not single-server?
             (str "Available servers: " (clojure.string/join ", " (sort server-names)) "\n\n"))
           "The `server` field specifies which MCP server to route the tool call to.\n"
           "Tool results will be returned in the message field."))
    "No MCP tools available."))

;;==============================================================================
;; Schema Functions (hat-aware)
;;==============================================================================

(defn hat-mcp-request-schema-fn
  "Schema function for hat-based MCP requests.
   Looks up servers at [:hats :mcp :servers] and builds dynamic server enum.
   
   Schema includes:
   - id: transition id
   - server: enum of available server names
   - message: JSON-RPC tool call request"
  [context {xid "id" :as _xition}]
  (let [servers (get-in context [:hats :mcp :servers] {})
        server-names (keys servers)
        ;; Merge all caches to build combined tool schema
        all-caches (map :cache (vals servers))
        merged-cache {"tools" (vec (mapcat #(get % "tools" []) all-caches))}
        single-request-schema (mcp-schema/mcp-cache->request-schema merged-cache)
        batch-request-schema [:vector single-request-schema]
        server-enum (if (seq server-names)
                      (into [:enum] server-names)
                      :string)]
    [:map {:closed true}
     ["id" [:= xid]]
     ["server" server-enum]
     ["message" [:or single-request-schema batch-request-schema]]]))

(defn hat-mcp-response-schema-fn
  "Schema function for hat-based MCP responses.
   Looks up servers at [:hats :mcp :servers].
   
   Schema includes:
   - id: transition id
   - server: which server responded
   - message: JSON-RPC response"
  [context {xid "id" :as _xition}]
  (let [servers (get-in context [:hats :mcp :servers] {})
        ;; Merge all caches to build combined response schema
        all-caches (map :cache (vals servers))
        merged-cache {"tools" (vec (mapcat #(get % "tools" []) all-caches))}
        single-response-schema (mcp-schema/mcp-cache->response-schema merged-cache)
        batch-response-schema [:vector single-response-schema]]
    [:map {:closed true}
     ["id" [:= xid]]
     ["server" :string] ;; Echo back which server handled the request
     ["document" {:optional true} :string]
     ["message" {:optional true} [:or single-response-schema batch-response-schema]]]))

;;==============================================================================
;; MCP Hat Maker
;;==============================================================================

(defn mcp-hat-maker
  "Hat-maker for MCP tool access.
   
   Creates a dynamic hat that:
   - Normalizes config to multi-server format
   - Starts MCP bridges for each server (or reuses existing)
   - Generates service state for tool calls
   - Registers mcp-service-action in :id->action
   - Adds stop-hook for bridge cleanup
   - Registers schema functions for request/response validation
   
   Config options:
   - :config - Single MCP server config (becomes 'default' server)
   - :servers - Map of {server-name -> {:config ... :timeout-ms ...}}
   - :timeout-ms - Global initialization timeout (default 30000)
   
   Context storage:
   {:hats {:mcp {:servers {\"name\" {:bridge <bridge> :cache <cache>}
                           ...}}}}"
  [state-id config]
  (let [service-id (str state-id "-mcp")
        ;; Normalize config to canonical multi-server format
        normalized (normalize-mcp-config config)
        server-configs (:servers normalized)
        ;; Schema IDs are state-specific to avoid conflicts
        request-schema-id (str state-id "-mcp-request")
        response-schema-id (str state-id "-mcp-response")]
    (fn [context]
      ;; Get existing servers from context (may be empty)
      (let [existing-servers (get-in context [:hats :mcp :servers] {})

            ;; Determine which servers need initialization
            servers-to-init (reduce-kv
                             (fn [acc server-name server-config]
                               (if (contains? existing-servers server-name)
                                 acc ;; Already initialized
                                 (assoc acc server-name server-config)))
                             {}
                             server-configs)

            ;; Initialize any new servers
            newly-initialized (reduce-kv
                               (fn [acc server-name {:keys [config timeout-ms]}]
                                 (log/info "MCP hat: initializing server" server-name "for" state-id)
                                 (let [{:keys [bridge cache]} (bridge/init-bridge config {:timeout-ms timeout-ms})]
                                   (assoc acc server-name {:bridge bridge :cache cache})))
                               {}
                               servers-to-init)

            ;; Merge existing and new servers
            all-servers (merge existing-servers newly-initialized)

            ;; Collect tools from all servers with server name for grouping
            all-tools-by-server (reduce-kv
                                 (fn [acc server-name {:keys [cache]}]
                                   (let [tools (get cache "tools" [])]
                                     (if (seq tools)
                                       (assoc acc server-name tools)
                                       acc)))
                                 {}
                                 all-servers)

            ;; Update context with all servers
            ctx' (-> context
                     (assoc-in [:hats :mcp :servers] all-servers)
                     ;; Register schema functions
                     (update :id->schema merge
                             {request-schema-id hat-mcp-request-schema-fn
                              response-schema-id hat-mcp-response-schema-fn})
                     ;; Register the mcp-service action
                     (update :id->action assoc "mcp-service" #'mcp-service-action))

            ;; Add stop hook only if we initialized new servers
            ctx' (if (seq newly-initialized)
                   (hat/add-stop-hook
                    ctx'
                    (fn [ctx]
                      (log/info "MCP hat: stopping" (count (get-in ctx [:hats :mcp :servers])) "bridges")
                      (doseq [[server-name {:keys [bridge]}] (get-in ctx [:hats :mcp :servers])]
                        (log/info "MCP hat: stopping server" server-name)
                        (bridge/stop-bridge bridge))
                      (update ctx :hats dissoc :mcp)))
                   ctx')]

        (when (seq newly-initialized)
          (log/info "MCP hat: initialized" (count newly-initialized) "new servers:" (keys newly-initialized)))
        (when (seq (keys existing-servers))
          (log/info "MCP hat: reusing" (count existing-servers) "existing servers:" (keys existing-servers)))

        [ctx'
         {"states" [{"id" service-id
                     "action" "mcp-service"}]
          "xitions" [{"id" [state-id service-id]
                      "schema" request-schema-id}
                     {"id" [service-id state-id]
                      "schema" response-schema-id}]
          "prompts" [(format-tools-prompt state-id service-id all-tools-by-server)]}]))))

;;==============================================================================
;; MCP Service Action
;;==============================================================================

(def-action mcp-service-action
  "Action for MCP service state. Routes tool calls to appropriate server bridge.
   
   Expects servers at [:hats :mcp :servers] in context.
   Routes based on \"server\" field in event.
   Handles single or batched tool calls.
   Echoes server name back in response for LLM context."
  [:map] ;; No config required
  [_config _fsm _ix {state-id "id" :as _state}]
  (fn [context event _trail handler]
    (let [servers (get-in context [:hats :mcp :servers] {})
          [from _to] (get event "id")
          server-name (get event "server")
          message (get event "message")]
      (log/info "mcp-service-action:" state-id "from" from "server:" server-name)
      (cond
        ;; No servers configured
        (empty? servers)
        (do
          (log/error "mcp-service-action: no servers in context")
          (handler context {"id" [state-id from]
                            "server" server-name
                            "message" {"error" "No MCP servers configured"}}))

        ;; Server not found
        (not (contains? servers server-name))
        (do
          (log/error "mcp-service-action: unknown server" server-name
                     "available:" (keys servers))
          (handler context {"id" [state-id from]
                            "server" server-name
                            "message" {"error" (str "Unknown server: " server-name
                                                    ". Available: " (clojure.string/join ", " (keys servers)))}}))

        ;; Execute tool calls on correct server
        :else
        (let [bridge (get-in servers [server-name :bridge])
              ;; Normalize to batch format
              requests (if (vector? message) message [message])
              ;; Execute batch
              responses (bridge/send-and-await bridge requests 30000)
              ;; If single request, unwrap the response
              result (if (vector? message) responses (first responses))]
          ;; Drain notifications
          (bridge/drain-notifications bridge)
          ;; Return to calling state with server echoed
          (handler context {"id" [state-id from]
                            "server" server-name
                            "message" result}))))))
