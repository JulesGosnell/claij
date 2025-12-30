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
   [claij.mcp.schema :as mcp-schema :refer [native-tools-schema-title]]
   [claij.parallel :as parallel]))

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
   
   For single-server configs, tools-by-server will have one key (\"default\").
   
   The prompt explains the cross-server batching format where multiple servers
   can be called in a single round trip."
  [state-id service-id tools-by-server]
  (if (seq tools-by-server)
    (let [server-names (sort (keys tools-by-server))
          single-server? (= 1 (count server-names))
          ;; Format tools grouped by server
          server-sections (for [[server-name tools] (sort-by first tools-by-server)]
                            (str (when-not single-server?
                                   (str "### Server: " server-name "\n"))
                                 (clojure.string/join "\n\n" (map format-tool-schema tools))))
          tools-text (clojure.string/join "\n\n" server-sections)
          ;; Build example
          example-server (first server-names)
          example-tool (get-in tools-by-server [example-server 0 "name"] "tool_name")]
      (str "## MCP Tools\n\n"
           tools-text
           "\n\n"
           "To call tools, output JSON with calls grouped by server:\n"
           "```json\n"
           "{\"id\": [\"" state-id "\", \"" service-id "\"],\n"
           " \"calls\": {\n"
           "   \"" example-server "\": [\n"
           "     {\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"tools/call\",\n"
           "      \"params\": {\"name\": \"" example-tool "\", \"arguments\": {...}}}\n"
           "   ]"
           (when-not single-server?
             (str ",\n   \"" (second server-names) "\": [...]"))
           "\n }}\n"
           "```\n\n"
           (if single-server?
             "Tool results will be returned in the `results` field keyed by server."
             (str "Available servers: " (clojure.string/join ", " server-names) "\n\n"
                  "You can call multiple tools across multiple servers in a single request.\n"
                  "Results will be returned in the `results` field keyed by server."))))
    "No MCP tools available."))

;;==============================================================================
;; Schema Functions (hat-aware)
;;==============================================================================

(defn hat-mcp-request-schema-fn
  "Schema function for hat-based MCP requests with cross-server batching.
   Looks up servers at [:hats :mcp :servers] and builds dynamic server enum.
   
   Schema format enables calling multiple servers in one round trip:
   {\"id\": [\"mc\", \"mc-mcp\"],
    \"calls\": {\"github\": [<request>, ...],
               \"tools\": [<request>, ...]}}
   
   Each server gets a batch of JSON-RPC tool call requests.
   
   The schema includes 'title' field with native-tools-schema-title value
   so that llm-action can identify this as the tools component."
  [context {xid "id" :as _xition}]
  (let [servers (get-in context [:hats :mcp :servers] {})
        server-names (vec (keys servers))
        ;; Merge all caches to build combined tool schema
        all-caches (map :cache (vals servers))
        merged-cache {"tools" (vec (mapcat #(get % "tools" []) all-caches))}
        single-request-schema (mcp-schema/mcp-cache->request-schema merged-cache)
        batch-request-schema {"type" "array" "items" single-request-schema}]
    {"title" native-tools-schema-title
     "description" "MCP tool calling interface. Select tools and provide arguments."
     "type" "object"
     "additionalProperties" false
     "required" ["id" "calls"]
     "properties" {"id" {"const" xid}
                   "calls" {"type" "object"
                            "propertyNames" (if (seq server-names)
                                              {"enum" server-names}
                                              {"type" "string"})
                            "additionalProperties" batch-request-schema}}}))

(defn hat-mcp-response-schema-fn
  "Schema function for hat-based MCP responses with cross-server results.
   
   Schema format returns results keyed by server:
   {\"id\": [\"mc-mcp\", \"mc\"],
    \"results\": {\"github\": [<response>, ...],
                 \"tools\": [<response>, ...]}}
   
   Each server's responses are in the same order as the requests.
   Uses discriminated union (result vs error key) for JSON-RPC responses."
  [context {xid "id" :as _xition}]
  (let [servers (get-in context [:hats :mcp :servers] {})
        server-names (vec (keys servers))]
    {"type" "object"
     "additionalProperties" false
     "required" ["id" "results"]
     "properties" {"id" {"const" xid}
                   "document" {"type" "string"}
                   "results" {"type" "object"
                              "propertyNames" (if (seq server-names)
                                                {"enum" server-names}
                                                {"type" "string"})
                              "additionalProperties" {"type" "array"
                                                      "items" mcp-schema/tool-call-jsonrpc-response-schema}}}}))

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
                     "description" "Model Context Protocol"
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
  "Action for MCP service state. Routes tool calls to appropriate server bridges.
   
   Supports cross-server batching: multiple servers can be called in one round trip.
   Now with parallel execution via claij.parallel/collect-async.
   
   Expects:
   - servers at [:hats :mcp :servers] in context
   - event with \"calls\" map: {\"server-name\" [<requests>...], ...}
   
   Returns:
   - \"results\" map: {\"server-name\" [<responses>...], ...}"
  {} ;; No config required
  [_config _fsm _ix {state-id "id" :as _state}]
  (fn [context event _trail handler]
    (let [servers (get-in context [:hats :mcp :servers] {})
          [from _to] (get event "id")
          calls (get event "calls" {})]
      (log/info "mcp-service-action:" state-id "from" from
                "servers:" (keys calls) "total-calls:" (reduce + 0 (map count (vals calls))))
      (cond
        ;; No servers configured
        (empty? servers)
        (do
          (log/error "mcp-service-action: no servers in context")
          (handler context {"id" [state-id from]
                            "results" {"error" "No MCP servers configured"}}))

        ;; Check for unknown servers
        (let [unknown (remove #(contains? servers %) (keys calls))]
          (seq unknown))
        (do
          (log/error "mcp-service-action: unknown servers" (remove #(contains? servers %) (keys calls))
                     "available:" (keys servers))
          (handler context {"id" [state-id from]
                            "results" {"error" (str "Unknown servers: "
                                                    (clojure.string/join ", " (remove #(contains? servers %) (keys calls)))
                                                    ". Available: " (clojure.string/join ", " (keys servers)))}}))

        ;; Execute tool calls on each server (PARALLEL via collect-async)
        :else
        (let [;; Build operations for parallel execution
              operations (for [[server-name requests] calls]
                           {:id server-name
                            :fn (fn [on-success on-error]
                                  (try
                                    (let [bridge (get-in servers [server-name :bridge])
                                          responses (bridge/send-and-await bridge requests 30000)]
                                      (bridge/drain-notifications bridge)
                                      (on-success responses))
                                    (catch Throwable t
                                      (log/error t "mcp-service-action: error calling server" server-name)
                                      (on-error {:exception (.getMessage t)
                                                 :server server-name}))))})
              ;; Execute in parallel (default) with 30s timeout
              {:keys [results all-succeeded? timed-out-ids]} (parallel/collect-async operations {:timeout-ms 30000
                                                                                                 :parallel? true})
              ;; Transform results back to the expected format
              ;; {server-name -> responses} for successes
              ;; {server-name -> error-info} for failures
              formatted-results (reduce-kv
                                 (fn [acc server-name {:keys [status value error]}]
                                   (case status
                                     :success (assoc acc server-name value)
                                     :error (assoc acc server-name {"error" error})
                                     :timeout (assoc acc server-name {"error" {:timeout true}})))
                                 {}
                                 results)]
          (when-not all-succeeded?
            (log/warn "mcp-service-action: not all servers succeeded."
                      "timed-out:" timed-out-ids))
          ;; Return to calling state with results keyed by server
          (handler context {"id" [state-id from]
                            "results" formatted-results}))))))
