(ns claij.mcp
  (:require
   [clojure.tools.logging :as log]
   [clojure.string :refer [starts-with?]]
   [clojure.data.json :refer [write-str read-str]]
   [clojure.core.async :refer [chan go-loop >! alts! <!! >!!]]
   [malli.core :as m]
   [malli.registry :as mr]
   [claij.util :refer [map-values]]
   [claij.malli :refer [def-fsm base-registry]]
   [claij.fsm :refer [start-fsm]]
   [claij.mcp.bridge :refer [start-mcp-bridge]]))

;; Note: The MCP protocol schema is defined in TypeScript, not JSON Schema.
;; The individual schemas below are hand-written Malli for the specific
;; structures we need for LLM interactions.

;; can we generate this fsm directly from the schema url ? much less code to maintain...

;; temporarily copied from .config/Claude/claude_desktop_config.json

(def mcp-config
  {"mcpServers"
   {"emacs"
    {"command" "socat",
     "args" ["-", "UNIX-CONNECT:/home/jules/.emacs.d/emacs-mcp-server.sock"],
     "transport" "stdio"},
    "m3-clojure-tools"
    {"command" "bash",
     "args" ["-c", "cd /home/jules/src/m3 && ./bin/mcp-clojure-tools.sh"],
     "transport" "stdio"},
    "m3-clojure-language-server"
    {"command" "bash",
     "args" ["-c", "cd /home/jules/src/m3 && ./bin/mcp-language-server.sh"],
     "transport" "stdio"},
    "claij-clojure-tools"
    {"command" "bash",
     "args" ["-c", "cd /home/jules/src/claij && ./bin/mcp-clojure-tools.sh"],
     "transport" "stdio"},
    "claij-clojure-language-server"
    {"command" "bash",
     "args" ["-c", "cd /home/jules/src/claij && ./bin/mcp-language-server.sh"],
     "transport" "stdio"}}})

;; a map of id: [input-channel output-channel stop]

(def initialise-request
  {:jsonrpc "2.0"
   "id" 1
   "method" "initialize"
   :params
   {:protocolVersion "2025-06-18"
    :capabilities
    {:elicitation {}}
    :clientInfo
    {:name "claij"
     :version "1.0-SNAPSHOT"}}})

(def initialised-notification
  {:jsonrpc "2.0"
   :method "notifications/initialized"
   :params {}})

(def list-tools-request
  {"jsonrpc" "2.0"
   "id" 2
   "method" "tools/list"})

(def list-prompts-request
  {"jsonrpc" "2.0"
   "id" 3
   "method" "prompts/list"})

(def list-resources-request
  {"jsonrpc" "2.0"
   "id" 4
   "method" "resources/list"})

;; TODO: think about what we should do with notifications... maybe
;; store them until someone shows an interest in that service.... or
;; just throw them all away... Otherwise we would have to pile them
;; all into the context which would be over the top...

(defn notification? [{m "method"}]
  (and m (starts-with? m "notifications/")))

;;------------------------------------------------------------------------------

(comment
  (def config {"command" "bash", "args" ["-c", "cd /home/jules/src/claij && ./bin/mcp-clojure-tools.sh"], "transport" "stdio"})

  (let [n 100
        ic (chan n (map write-str))
        oc (chan n (map read-str))
        stop (start-mcp-bridge config ic oc)
        _ (>!! ic initialise-request)
        r (<!! oc)]
    [r ic oc stop]))

;;------------------------------------------------------------------------------

(defn initialize-mcp-cache
  "Initialize MCP cache structure from initialization response capabilities.
  
  Takes current MCP cache state map and a capabilities map from an MCP initialize
  response. Returns updated cache with keys for each capability that supports list
  changes or subscriptions, initialized to nil to indicate they need to be loaded."
  [mcp-cache capabilities]
  (reduce-kv
   (fn [acc k {lc "listChanged" s "subscribe"}]
     (if (or lc s) (assoc acc k nil) acc))
   mcp-cache
   capabilities))

(defn invalidate-mcp-cache-item
  "Invalidate a specific MCP cache item by setting it to nil.
  
  Takes the current MCP cache map and a capability name (tools/prompts/resources).
  Returns updated cache with that capability set to nil, indicating it needs refresh."
  [mcp-cache capability]
  (assoc mcp-cache capability nil))

(defn refresh-mcp-cache-item
  "Merge fresh list response data into MCP cache.
  
  Takes the current MCP cache map and a result map from a list response.
  Returns updated cache with the result data merged in."
  [mcp-cache result]
  (merge mcp-cache result))

(defn list-changed? [m]
  (when-let [[_ capability] (re-matches #"notifications/([^/]+)/list_changed" m)]
    capability))

;; ============================================================
;; MCP Schema Definitions (Malli)
;; ============================================================

(def mcp-schemas
  "Schema definitions for MCP protocol messages.
   Plain map for emit-for-llm analysis and inlining.
   Use string keys for refs and map entries for LLM JSON compatibility."
  {;; Logging level enum (RFC-5424 syslog severities)
   "logging-level" [:enum {:description "Log severity level (RFC-5424)"}
                    "debug" "info" "notice" "warning" "error" "critical" "alert" "emergency"]

   ;; Content types for tool responses and prompts
   "text-content" [:map {:closed true :description "Plain text content"}
                   ["type" [:= "text"]]
                   ["text" {:description "The text content"} :string]]

   "image-content" [:map {:closed true :description "Base64-encoded image"}
                    ["type" [:= "image"]]
                    ["data" {:description "Base64-encoded image data"} :string]
                    ["mimeType" {:description "Image MIME type (e.g. 'image/png')"} :string]]

   "audio-content" [:map {:closed true :description "Base64-encoded audio"}
                    ["type" [:= "audio"]]
                    ["data" {:description "Base64-encoded audio data"} :string]
                    ["mimeType" {:description "Audio MIME type"} :string]]

   "resource-link-content" [:map {:closed true :description "Link to an MCP resource"}
                            ["type" [:= "resource_link"]]
                            ["uri" {:description "Resource URI"} :string]]

   "embedded-resource-content" [:map {:closed true :description "Embedded resource object"}
                                ["type" [:= "resource"]]
                                ["resource" {:description "The embedded resource"} :map]]

   ;; Union of all content types
   "content-item" [:or {:description "Any content type"}
                   [:ref "text-content"]
                   [:ref "image-content"]
                   [:ref "audio-content"]
                   [:ref "resource-link-content"]
                   [:ref "embedded-resource-content"]]

   ;; Tool response (CallToolResult)
   "tool-response" [:map {:description "Result from calling an MCP tool"}
                    ["content" {:description "Response content items"} [:vector [:ref "content-item"]]]
                    ["isError" {:optional true :description "True if tool call failed"} :boolean]
                    ["structuredContent" {:optional true :description "Structured data response"} :map]]

   ;; Resource contents (text or blob variants)
   "text-resource" [:map {:closed true :description "Text-based resource content"}
                    ["uri" {:description "Resource URI"} :string]
                    ["text" {:description "Text content"} :string]
                    ["mimeType" {:optional true :description "Content MIME type"} :string]]

   "blob-resource" [:map {:closed true :description "Binary resource content"}
                    ["uri" {:description "Resource URI"} :string]
                    ["blob" {:description "Base64-encoded binary data"} :string]
                    ["mimeType" {:optional true :description "Content MIME type"} :string]]

   "resource-content" [:or {:description "Resource content (text or binary)"}
                       [:ref "text-resource"]
                       [:ref "blob-resource"]]

   ;; Resource response (ReadResourceResult)
   "resource-response" [:map {:closed true :description "Response from reading a resource"}
                        ["contents" {:description "Resource contents"} [:vector [:ref "resource-content"]]]]

   ;; Prompt message
   "prompt-message" [:map {:closed true :description "A message in a prompt conversation"}
                     ["role" {:description "Message author role"} [:enum "user" "assistant"]]
                     ["content" {:description "Message content"} [:ref "content-item"]]]

   ;; Prompt response (GetPromptResult)
   "prompt-response" [:map {:description "Response from getting a prompt"}
                      ["description" {:optional true :description "Prompt description"} :string]
                      ["messages" {:description "Prompt messages"} [:vector [:ref "prompt-message"]]]]

   ;; Logging schemas
   "logging-set-level-request" [:map {:closed true :description "Request to set logging level"}
                                ["level" {:description "New log level"} [:ref "logging-level"]]]

   "logging-notification" [:map {:closed true :description "Log message notification"}
                           ["level" {:description "Log level"} [:ref "logging-level"]]
                           ["data" {:description "Log data"} :any]
                           ["logger" {:optional true :description "Logger name"} :string]]})

(def mcp-registry
  "Malli registry for MCP schema validation.
   Composes base-registry with mcp-schemas."
  (mr/composite-registry
   base-registry
   mcp-schemas))

;; ============================================================
;; Tool schema generation
;; ============================================================

(def tool-response-schema
  "Malli schema for MCP tool call responses (CallToolResult).
   References mcp-schemas definitions."
  [:ref "tool-response"])

(defn tool-cache->request-schema
  "Generate a Malli schema for a tool call request from cached tool info.
   
   The inputSchema from MCP is JSON Schema, which we embed using our custom
   :json-schema Malli type. This delegates validation to m3 while keeping
   a unified Malli-based validation API."
  [tool-cache]
  (let [tool-name (get tool-cache "name")
        input-schema (get tool-cache "inputSchema")]
    [:map {:closed true}
     ["name" [:= tool-name]]
     ["arguments" (if input-schema
                    [:json-schema {:schema input-schema}]
                    :any)]]))

(defn tool-cache->response-schema
  "Generate response schema for a tool.
   Note: outputSchema from MCP is JSON Schema - ignored for now.
   TODO: Convert outputSchema to Malli to constrain structuredContent."
  [_tool-cache]
  ;; For now, return the base tool-response schema
  ;; outputSchema would need JSON Schema -> Malli conversion
  [:ref "tool-response"])

(defn tools-cache->request-schema
  "Generate an :or schema for all tools in cache."
  [tools-cache]
  (into [:or] (mapv tool-cache->request-schema tools-cache)))

(defn tools-cache->response-schema
  "Generate response schema for all tools.
   Currently all tools share the same response schema."
  [_tools-cache]
  ;; Since all tools return the same response schema for now,
  ;; no need for :or - just return the base schema
  [:ref "tool-response"])

(def resource-response-schema
  "Malli schema for MCP resource read responses (ReadResourceResult).
   References mcp-schemas definitions."
  [:ref "resource-response"])

(defn resources-cache->request-schema
  "Generate request schema constraining uri to known resources."
  [resources-cache]
  (let [uris (mapv #(get % "uri") resources-cache)]
    [:map {:closed true}
     ["uri" (into [:enum] uris)]]))

(def prompt-response-schema
  "Malli schema for MCP prompt get responses (GetPromptResult).
   References mcp-schemas definitions."
  [:ref "prompt-response"])

(defn prompt-cache->request-schema
  "Generate request schema for a single prompt from cache entry.
   Arguments are always strings in MCP prompts."
  [prompt-cache]
  (let [prompt-name (get prompt-cache "name")
        arguments (get prompt-cache "arguments" [])
        has-required-args? (some #(get % "required") arguments)
        ;; Build Malli map entries for arguments
        arg-entries (mapv (fn [{n "name" r "required"}]
                            (if r
                              [n :string]
                              [n {:optional true} :string]))
                          arguments)]
    [:map {:closed true}
     ["name" [:= prompt-name]]
     ;; Make arguments optional if there are no required arguments
     (if has-required-args?
       ["arguments" (into [:map {:closed true}] arg-entries)]
       ["arguments" {:optional true} (if (seq arg-entries)
                                       (into [:map {:closed true}] arg-entries)
                                       [:map])])]))

(defn prompts-cache->request-schema
  "Generate an :or schema for all prompts in cache."
  [prompts-cache]
  (into [:or] (mapv prompt-cache->request-schema prompts-cache)))

(def logging-level-strings
  "The actual logging level string values (RFC-5424 syslog severities)."
  #{"debug" "info" "notice" "warning" "error" "critical" "alert" "emergency"})

(def logging-levels
  "MCP logging levels as a Malli schema reference."
  [:ref "logging-level"])

(def logging-set-level-request-schema
  "Malli schema for MCP logging/setLevel request.
   References mcp-schemas definitions."
  [:ref "logging-set-level-request"])

(def logging-notification-schema
  "Malli schema for MCP notifications/message (log message from server).
   References mcp-schemas definitions."
  [:ref "logging-notification"])

;;-----------------------------------------------------------------------------
;; Level 1: Combined MCP request/response schemas
;;
;; These functions take the MCP cache and produce unified schemas covering
;; all available operations. The schemas are dynamic - they reflect what's
;; actually available from the connected MCP server(s).

(defn mcp-cache->request-schema
  "Generate combined request schema from MCP cache.
   Returns an :or schema covering all available operations:
   tools/call, resources/read, prompts/get, logging/setLevel.
   
   Each alternative is a complete JSON-RPC request with:
   - jsonrpc: const \"2.0\"
   - id: integer (request id)
   - method: const for the operation
   - params: operation-specific parameters
   
   Cache shape: {\"tools\" [...] \"resources\" [...] \"prompts\" [...]}"
  [{tools "tools" resources "resources" prompts "prompts"}]
  (let [;; Helper to wrap method+params in JSON-RPC envelope (Malli)
        wrap-jsonrpc (fn [method params-schema]
                       [:map {:closed true}
                        ["jsonrpc" [:= "2.0"]]
                        ["id" :int]
                        ["method" [:= method]]
                        ["params" params-schema]])

        schemas (cond-> []
                  (seq tools)
                  (conj (wrap-jsonrpc "tools/call" (tools-cache->request-schema tools)))

                  (seq resources)
                  (conj (wrap-jsonrpc "resources/read" (resources-cache->request-schema resources)))

                  (seq prompts)
                  (conj (wrap-jsonrpc "prompts/get" (prompts-cache->request-schema prompts)))

                  ;; logging/setLevel is always available (not cache-dependent)
                  true
                  (conj (wrap-jsonrpc "logging/setLevel" logging-set-level-request-schema)))]
    (into [:or] schemas)))

(defn mcp-cache->response-schema
  "Generate combined response schema from MCP cache.
   Returns an :or schema covering all possible JSON-RPC responses.
   
   JSON-RPC responses have: jsonrpc, id, result
   JSON-RPC notifications have: jsonrpc, method, params (no id)
   
   Note: Response schemas are mostly static (not per-tool/resource/prompt)
   except for tools with outputSchema which constrain structuredContent."
  [{tools "tools" :as _cache}]
  (let [;; Helper to wrap result content in JSON-RPC response envelope (Malli)
        wrap-response (fn [result-schema]
                        [:map {:closed true}
                         ["jsonrpc" [:= "2.0"]]
                         ["id" :int]
                         ["result" result-schema]])

        ;; Helper to wrap notification in JSON-RPC notification envelope (Malli)
        wrap-notification (fn [method params-schema]
                            [:map {:closed true}
                             ["jsonrpc" [:= "2.0"]]
                             ["method" [:= method]]
                             ["params" params-schema]])

        ;; Tool responses - simplified since all share same schema for now
        tool-response (when (seq tools)
                        (wrap-response (tools-cache->response-schema tools)))

        schemas (cond-> [;; Static response types
                         (wrap-response resource-response-schema)
                         (wrap-response prompt-response-schema)
                         ;; Logging is a notification, not a response
                         (wrap-notification "notifications/message" logging-notification-schema)]
                  tool-response
                  (conj tool-response))]
    (into [:or] schemas)))

;;-----------------------------------------------------------------------------
;; FSM Schema Functions
;;
;; These functions have signature (fn [context xition] schema) for use with
;; FSM dynamic schema resolution. They extract MCP cache from context and
;; delegate to the schema generation functions above.

(defn mcp-request-schema-fn
  "Schema function for FSM dynamic schema resolution.
   Extracts MCP cache from context and generates request schema.
   
   Context must have MCP cache at (get context \"state\") with shape:
   {\"tools\" [...] \"resources\" [...] \"prompts\" [...]}"
  [context _xition]
  (mcp-cache->request-schema (get context "state")))

(defn mcp-response-schema-fn
  "Schema function for FSM dynamic schema resolution.
   Extracts MCP cache from context and generates response schema.
   
   Context must have MCP cache at (get context \"state\")."
  [context _xition]
  (mcp-cache->response-schema (get context "state")))

(defn mcp-request-xition-schema-fn
  "Schema function for llm->servicing transition.
   Builds complete transition envelope with dynamic MCP request schema.
   
   Used as `:id->schema \"mcp-request-xition\"` in FSM context."
  [context {xid "id" :as _xition}]
  [:map {:closed true}
   ["id" [:= xid]]
   ["message" (mcp-cache->request-schema (get context "state"))]])

(defn mcp-response-xition-schema-fn
  "Schema function for servicing->llm transition.
   Builds complete transition envelope with dynamic MCP response schema.
   
   Used as `:id->schema \"mcp-response-xition\"` in FSM context."
  [context {xid "id" :as _xition}]
  [:map {:closed true}
   ["id" [:= xid]]
   ["document" {:optional true} :string]
   ["message" {:optional true} (mcp-cache->response-schema (get context "state"))]])

;; TODO: Additional MCP capabilities not yet implemented
;;
;; The schemas above define what our MCP integration can do. The LLM cannot
;; request capabilities we don't offer schemas for.
;;
;; Future capabilities to consider:
;;
;; - sampling (Server → Client): Server requests client to make an LLM call.
;;   Enables nested/recursive LLM reasoning - tools can spawn their own AI.
;;   High priority for multi-agent "society of AIs" architecture.
;;
;; - completions (Server → Client): Autocompletion for tool/prompt arguments.
;;   Could provide dynamic valid-value suggestions beyond static enums.
;;
;; - elicitation (Server → Client): Server requests user input.
;;   For interactive workflows requiring human-in-the-loop.
;;
;; - progress (Server → Client): Progress notifications for long operations.
;;
;; - roots (Client → Server): Client tells server about filesystem roots.
;;
;; - subscriptions (Client → Server): Subscribe to resource updates.
;;   Note: clojure-mcp doesn't currently support this.

