(ns claij.mcp.schema
  "JSON Schema generation for MCP protocol messages.
   
   Generates JSON Schema for MCP tool/resource/prompt calls that can be
   used for FSM transition validation.")

;;------------------------------------------------------------------------------
;; Static Schema Definitions (JSON Schema)
;;------------------------------------------------------------------------------

(def logging-level-strings
  #{"debug" "info" "notice" "warning" "error" "critical" "alert" "emergency"})

(def logging-levels
  {"enum" (vec logging-level-strings)})

(def logging-set-level-request-schema
  {"type" "object"
   "additionalProperties" false
   "required" ["level"]
   "properties" {"level" {"enum" (vec logging-level-strings)}}})

(def logging-notification-schema
  {"type" "object"
   "additionalProperties" false
   "required" ["level" "data"]
   "properties" {"level" {"enum" (vec logging-level-strings)}
                 "data" {}
                 "logger" {"type" "string"}}})

;; Content item schemas
(def text-content-schema
  {"type" "object"
   "additionalProperties" false
   "required" ["type" "text"]
   "properties" {"type" {"const" "text"}
                 "text" {"type" "string"}}})

(def image-content-schema
  {"type" "object"
   "additionalProperties" false
   "required" ["type" "data" "mimeType"]
   "properties" {"type" {"const" "image"}
                 "data" {"type" "string"}
                 "mimeType" {"type" "string"}}})

(def audio-content-schema
  {"type" "object"
   "additionalProperties" false
   "required" ["type" "data" "mimeType"]
   "properties" {"type" {"const" "audio"}
                 "data" {"type" "string"}
                 "mimeType" {"type" "string"}}})

(def resource-link-content-schema
  {"type" "object"
   "additionalProperties" false
   "required" ["type" "uri"]
   "properties" {"type" {"const" "resource_link"}
                 "uri" {"type" "string"}}})

(def embedded-resource-content-schema
  {"type" "object"
   "additionalProperties" false
   "required" ["type" "resource"]
   "properties" {"type" {"const" "resource"}
                 "resource" {"type" "object"}}})

(def content-item-schema
  {"oneOf" [text-content-schema
            image-content-schema
            audio-content-schema
            resource-link-content-schema
            embedded-resource-content-schema]})

;; Response schemas
(def tool-response-schema
  {"type" "object"
   "required" ["content"]
   "properties" {"content" {"type" "array" "items" content-item-schema}
                 "isError" {"type" "boolean"}
                 "structuredContent" {"type" "object"}}})

(def jsonrpc-error-schema
  {"type" "object"
   "required" ["code" "message"]
   "properties" {"code" {"type" "integer"}
                 "message" {"type" "string"}
                 "data" {}}})

(def tool-call-jsonrpc-response-schema
  "JSON-RPC response schema for tools/call."
  {"oneOf"
   [{"type" "object"
     "additionalProperties" false
     "required" ["jsonrpc" "id" "result"]
     "properties" {"jsonrpc" {"const" "2.0"}
                   "id" {"type" "integer"}
                   "result" tool-response-schema}}
    {"type" "object"
     "additionalProperties" false
     "required" ["jsonrpc" "id" "error"]
     "properties" {"jsonrpc" {"const" "2.0"}
                   "id" {"type" "integer"}
                   "error" jsonrpc-error-schema}}]})

;; Resource schemas
(def text-resource-schema
  {"type" "object"
   "additionalProperties" false
   "required" ["uri" "text"]
   "properties" {"uri" {"type" "string"}
                 "text" {"type" "string"}
                 "mimeType" {"type" "string"}}})

(def blob-resource-schema
  {"type" "object"
   "additionalProperties" false
   "required" ["uri" "blob"]
   "properties" {"uri" {"type" "string"}
                 "blob" {"type" "string"}
                 "mimeType" {"type" "string"}}})

(def resource-content-schema
  {"oneOf" [text-resource-schema blob-resource-schema]})

(def resource-response-schema
  {"type" "object"
   "additionalProperties" false
   "required" ["contents"]
   "properties" {"contents" {"type" "array" "items" resource-content-schema}}})

;; Prompt schemas
(def prompt-message-schema
  {"type" "object"
   "additionalProperties" false
   "required" ["role" "content"]
   "properties" {"role" {"enum" ["user" "assistant"]}
                 "content" content-item-schema}})

(def prompt-response-schema
  {"type" "object"
   "required" ["messages"]
   "properties" {"description" {"type" "string"}
                 "messages" {"type" "array" "items" prompt-message-schema}}})

;;------------------------------------------------------------------------------
;; Static Envelope Schemas
;;------------------------------------------------------------------------------

(def json-rpc-request-envelope
  "Static JSON-RPC 2.0 request envelope."
  {"type" "object"
   "required" ["jsonrpc" "id" "method"]
   "properties" {"jsonrpc" {"const" "2.0"}
                 "id" {"oneOf" [{"type" "integer"} {"type" "string"}]}
                 "method" {"type" "string"}
                 "params" {}}})

(def json-rpc-response-envelope
  "Static JSON-RPC 2.0 response envelope."
  {"type" "object"
   "required" ["jsonrpc" "id"]
   "properties" {"jsonrpc" {"const" "2.0"}
                 "id" {"oneOf" [{"type" "integer"} {"type" "string"}]}
                 "result" {}
                 "error" {"type" "object"
                          "required" ["code" "message"]
                          "properties" {"code" {"type" "integer"}
                                        "message" {"type" "string"}
                                        "data" {}}}}})

(def json-rpc-notification-envelope
  "Static JSON-RPC 2.0 notification envelope (no id field)."
  {"type" "object"
   "additionalProperties" false
   "required" ["jsonrpc" "method"]
   "properties" {"jsonrpc" {"const" "2.0"}
                 "method" {"type" "string"}
                 "params" {}}})

(defn make-request-envelope-schema
  "Build request envelope with specific method and params schema."
  [method params-schema]
  {"type" "object"
   "additionalProperties" false
   "required" ["jsonrpc" "id" "method" "params"]
   "properties" {"jsonrpc" {"const" "2.0"}
                 "id" {"oneOf" [{"type" "integer"} {"type" "string"}]}
                 "method" {"const" method}
                 "params" (or params-schema {})}})

(defn make-response-envelope-schema
  "Build response envelope with specific result schema."
  [result-schema]
  {"type" "object"
   "additionalProperties" false
   "required" ["jsonrpc" "id" "result"]
   "properties" {"jsonrpc" {"const" "2.0"}
                 "id" {"oneOf" [{"type" "integer"} {"type" "string"}]}
                 "result" (or result-schema {})}})

;;------------------------------------------------------------------------------
;; Dynamic Schema Generation Functions
;;------------------------------------------------------------------------------

(defn find-tool-in-cache
  "Find a tool definition by name in the cache.
   Returns the tool map or nil if not found."
  [cache tool-name]
  (when-let [tools (get cache "tools")]
    (some #(when (= tool-name (get % "name")) %) tools)))

(defn resolve-mcp-tool-input-schema
  "Resolve the input (params) schema for a specific tool."
  [cache tool-name]
  (if-let [tool (find-tool-in-cache cache tool-name)]
    (get tool "inputSchema" {})
    {}))

(defn resolve-mcp-request-schema
  "Build complete JSON-RPC request schema for a tool call."
  [context tool-name]
  (let [cache (get context "state")
        params-schema (if tool-name
                        {"type" "object"
                         "additionalProperties" false
                         "required" ["name" "arguments"]
                         "properties" {"name" {"const" tool-name}
                                       "arguments" (resolve-mcp-tool-input-schema cache tool-name)}}
                        {})]
    (make-request-envelope-schema "tools/call" params-schema)))

(defn resolve-mcp-response-schema
  "Build complete JSON-RPC response schema for a tool call."
  [_context _tool-name]
  (make-response-envelope-schema tool-response-schema))

(defn mcp-tool-request-schema-fn
  "Schema function for :id->schema registration."
  [context xition]
  (let [tool-name (get xition "tool-name")]
    (resolve-mcp-request-schema context tool-name)))

(defn mcp-tool-response-schema-fn
  "Schema function for :id->schema registration."
  [context xition]
  (let [tool-name (get xition "tool-name")]
    (resolve-mcp-response-schema context tool-name)))

(defn tool-cache->request-schema
  "Generate request schema for a single tool."
  [tool-cache]
  (let [tool-name (get tool-cache "name")
        input-schema (get tool-cache "inputSchema" {})]
    {"type" "object"
     "additionalProperties" false
     "required" ["name" "arguments"]
     "properties" {"name" {"const" tool-name}
                   "arguments" input-schema}}))

(defn tool-cache->response-schema [_] tool-response-schema)

(defn tools-cache->request-schema
  "Generate oneOf schema for multiple tools."
  [tools-cache]
  (if (seq tools-cache)
    {"oneOf" (mapv tool-cache->request-schema tools-cache)}
    {}))

(defn tools-cache->response-schema [_] tool-response-schema)

(defn resources-cache->request-schema
  "Generate request schema for resources."
  [resources-cache]
  {"type" "object"
   "additionalProperties" false
   "required" ["uri"]
   "properties" {"uri" {"enum" (mapv #(get % "uri") resources-cache)}}})

(defn prompt-cache->request-schema
  "Generate request schema for a single prompt."
  [prompt-cache]
  (let [prompt-name (get prompt-cache "name")
        arguments (get prompt-cache "arguments" [])
        required-args (filterv #(get % "required") arguments)
        arg-properties (into {} (map (fn [{n "name"}] [n {"type" "string"}]) arguments))]
    {"type" "object"
     "additionalProperties" false
     "required" (if (seq required-args)
                  ["name" "arguments"]
                  ["name"])
     "properties" (merge
                   {"name" {"const" prompt-name}}
                   (when (seq arguments)
                     {"arguments" {"type" "object"
                                   "additionalProperties" false
                                   "required" (mapv #(get % "name") required-args)
                                   "properties" arg-properties}}))}))

(defn prompts-cache->request-schema
  "Generate oneOf schema for multiple prompts."
  [prompts-cache]
  (if (seq prompts-cache)
    {"oneOf" (mapv prompt-cache->request-schema prompts-cache)}
    {}))

(defn wrap-jsonrpc-request
  "Wrap a params schema in JSON-RPC request envelope."
  [method params-schema]
  {"type" "object"
   "additionalProperties" false
   "required" ["jsonrpc" "id" "method" "params"]
   "properties" {"jsonrpc" {"const" "2.0"}
                 "id" {"type" "integer"}
                 "method" {"const" method}
                 "params" params-schema}})

(defn wrap-jsonrpc-response
  "Wrap a result schema in JSON-RPC response envelope."
  [result-schema]
  {"type" "object"
   "additionalProperties" false
   "required" ["jsonrpc" "id" "result"]
   "properties" {"jsonrpc" {"const" "2.0"}
                 "id" {"type" "integer"}
                 "result" result-schema}})

(defn wrap-jsonrpc-notification
  "Wrap a params schema in JSON-RPC notification envelope."
  [method params-schema]
  {"type" "object"
   "additionalProperties" false
   "required" ["jsonrpc" "method" "params"]
   "properties" {"jsonrpc" {"const" "2.0"}
                 "method" {"const" method}
                 "params" params-schema}})

(defn mcp-cache->request-schema
  "Generate combined request schema for all MCP methods."
  [{tools "tools" resources "resources" prompts "prompts"}]
  (let [schemas (cond-> []
                  (seq tools) (conj (wrap-jsonrpc-request "tools/call" (tools-cache->request-schema tools)))
                  (seq resources) (conj (wrap-jsonrpc-request "resources/read" (resources-cache->request-schema resources)))
                  (seq prompts) (conj (wrap-jsonrpc-request "prompts/get" (prompts-cache->request-schema prompts)))
                  true (conj (wrap-jsonrpc-request "logging/setLevel" logging-set-level-request-schema)))]
    (if (> (count schemas) 1)
      {"oneOf" schemas}
      (first schemas))))

(defn mcp-cache->response-schema
  "Generate combined response schema for all MCP methods."
  [{tools "tools" :as _cache}]
  (let [tool-response-wrapped (when (seq tools) (wrap-jsonrpc-response tool-response-schema))
        schemas (cond-> [(wrap-jsonrpc-response resource-response-schema)
                         (wrap-jsonrpc-response prompt-response-schema)
                         (wrap-jsonrpc-notification "notifications/message" logging-notification-schema)]
                  tool-response-wrapped (conj tool-response-wrapped))]
    {"oneOf" schemas}))

;;------------------------------------------------------------------------------
;; FSM Schema Functions
;;------------------------------------------------------------------------------

(defn mcp-request-schema-fn [context _xition]
  (mcp-cache->request-schema (get context "state")))

(defn mcp-response-schema-fn [context _xition]
  (mcp-cache->response-schema (get context "state")))

(defn mcp-request-xition-schema-fn
  "Schema for LLM→servicing transition. Supports single or batch requests."
  [context {xid "id" :as _xition}]
  (let [single-request-schema (mcp-cache->request-schema (get context "state"))
        batch-request-schema {"type" "array" "items" single-request-schema}]
    {"type" "object"
     "additionalProperties" false
     "required" ["id" "message"]
     "properties" {"id" {"const" xid}
                   "message" {"oneOf" [single-request-schema batch-request-schema]}}}))

(defn mcp-response-xition-schema-fn
  "Schema for servicing→LLM transition. Supports single or batch responses."
  [context {xid "id" :as _xition}]
  (let [single-response-schema (mcp-cache->response-schema (get context "state"))
        batch-response-schema {"type" "array" "items" single-response-schema}]
    {"type" "object"
     "additionalProperties" false
     "required" ["id"]
     "properties" {"id" {"const" xid}
                   "document" {"type" "string"}
                   "message" {"oneOf" [single-response-schema batch-response-schema]}}}))
