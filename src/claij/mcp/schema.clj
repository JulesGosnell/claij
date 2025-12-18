(ns claij.mcp.schema
  "Malli schema generation for MCP protocol messages."
  (:require
   [malli.core :as m]
   [malli.registry :as mr]
   [claij.malli :refer [base-registry]]))

(def mcp-schemas
  {"logging-level" [:enum {:description "Log severity level (RFC-5424)"}
                    "debug" "info" "notice" "warning" "error" "critical" "alert" "emergency"]
   "text-content" [:map {:closed true :description "Plain text content"}
                   ["type" [:= "text"]]
                   ["text" {:description "The text content"} :string]]
   "image-content" [:map {:closed true :description "Base64-encoded image"}
                    ["type" [:= "image"]]
                    ["data" {:description "Base64-encoded image data"} :string]
                    ["mimeType" {:description "Image MIME type"} :string]]
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
   "content-item" [:or {:description "Any content type"}
                   [:ref "text-content"] [:ref "image-content"] [:ref "audio-content"]
                   [:ref "resource-link-content"] [:ref "embedded-resource-content"]]
   "tool-response" [:map {:description "Result from calling an MCP tool"}
                    ["content" {:description "Response content items"} [:vector [:ref "content-item"]]]
                    ["isError" {:optional true :description "True if tool call failed"} :boolean]
                    ["structuredContent" {:optional true :description "Structured data response"} :map]]
   "text-resource" [:map {:closed true :description "Text-based resource content"}
                    ["uri" {:description "Resource URI"} :string]
                    ["text" {:description "Text content"} :string]
                    ["mimeType" {:optional true :description "Content MIME type"} :string]]
   "blob-resource" [:map {:closed true :description "Binary resource content"}
                    ["uri" {:description "Resource URI"} :string]
                    ["blob" {:description "Base64-encoded binary data"} :string]
                    ["mimeType" {:optional true :description "Content MIME type"} :string]]
   "resource-content" [:or {:description "Resource content (text or binary)"}
                       [:ref "text-resource"] [:ref "blob-resource"]]
   "resource-response" [:map {:closed true :description "Response from reading a resource"}
                        ["contents" {:description "Resource contents"} [:vector [:ref "resource-content"]]]]
   "prompt-message" [:map {:closed true :description "A message in a prompt conversation"}
                     ["role" {:description "Message author role"} [:enum "user" "assistant"]]
                     ["content" {:description "Message content"} [:ref "content-item"]]]
   "prompt-response" [:map {:description "Response from getting a prompt"}
                      ["description" {:optional true :description "Prompt description"} :string]
                      ["messages" {:description "Prompt messages"} [:vector [:ref "prompt-message"]]]]
   "logging-set-level-request" [:map {:closed true :description "Request to set logging level"}
                                ["level" {:description "New log level"} [:ref "logging-level"]]]
   "logging-notification" [:map {:closed true :description "Log message notification"}
                           ["level" {:description "Log level"} [:ref "logging-level"]]
                           ["data" {:description "Log data"} :any]
                           ["logger" {:optional true :description "Logger name"} :string]]})

;; Inlined schemas for validation without MCP registry
;; These expand refs to avoid :malli.core/invalid-ref errors during validation

(def inlined-content-item
  "Content item schema fully inlined (no refs)."
  [:or
   [:map {:closed true} ["type" [:= "text"]] ["text" :string]]
   [:map {:closed true} ["type" [:= "image"]] ["data" :string] ["mimeType" :string]]
   [:map {:closed true} ["type" [:= "audio"]] ["data" :string] ["mimeType" :string]]
   [:map {:closed true} ["type" [:= "resource_link"]] ["uri" :string]]
   [:map {:closed true} ["type" [:= "resource"]] ["resource" :map]]])

(def inlined-tool-response-schema
  "Tool response schema fully inlined (no refs)."
  [:map
   ["content" [:vector inlined-content-item]]
   ["isError" {:optional true} :boolean]
   ["structuredContent" {:optional true} :map]])

(def jsonrpc-error-schema
  "JSON-RPC error object schema."
  [:map
   ["code" :int]
   ["message" :string]
   ["data" {:optional true} :any]])

(def tool-call-jsonrpc-response-schema
  "JSON-RPC response schema for tools/call - discriminated by result/error key.
   Does not include notifications (handled separately by drain-notifications)."
  [:multi {:dispatch (fn [m] (cond
                               (contains? m "result") :success
                               (contains? m "error") :error
                               :else :unknown))}
   [:success [:map {:closed true}
              ["jsonrpc" [:= "2.0"]]
              ["id" :int]
              ["result" inlined-tool-response-schema]]]
   [:error [:map {:closed true}
            ["jsonrpc" [:= "2.0"]]
            ["id" :int]
            ["error" jsonrpc-error-schema]]]])

(def inlined-resource-content
  "Resource content schema fully inlined."
  [:or
   [:map {:closed true} ["uri" :string] ["text" :string] ["mimeType" {:optional true} :string]]
   [:map {:closed true} ["uri" :string] ["blob" :string] ["mimeType" {:optional true} :string]]])

(def inlined-resource-response-schema
  "Resource response schema fully inlined."
  [:map {:closed true}
   ["contents" [:vector inlined-resource-content]]])

(def inlined-prompt-message
  "Prompt message schema fully inlined."
  [:map {:closed true}
   ["role" [:enum "user" "assistant"]]
   ["content" inlined-content-item]])

(def inlined-prompt-response-schema
  "Prompt response schema fully inlined."
  [:map
   ["description" {:optional true} :string]
   ["messages" [:vector inlined-prompt-message]]])

(def inlined-logging-notification-schema
  "Logging notification schema fully inlined."
  [:map {:closed true}
   ["level" [:enum "debug" "info" "notice" "warning" "error" "critical" "alert" "emergency"]]
   ["data" :any]
   ["logger" {:optional true} :string]])

(def mcp-registry (mr/composite-registry base-registry mcp-schemas))

(def tool-response-schema [:ref "tool-response"])
(def resource-response-schema [:ref "resource-response"])
(def prompt-response-schema [:ref "prompt-response"])
(def logging-level-strings #{"debug" "info" "notice" "warning" "error" "critical" "alert" "emergency"})
(def logging-levels [:ref "logging-level"])
(def logging-set-level-request-schema
  "Inlined schema (not using ref) so it works without MCP registry during validation."
  [:map {:closed true}
   ["level" [:enum "debug" "info" "notice" "warning" "error" "critical" "alert" "emergency"]]])
(def logging-notification-schema [:ref "logging-notification"])

;;------------------------------------------------------------------------------
;; Static Envelope Schemas (Story #64)
;;------------------------------------------------------------------------------
;;
;; These schemas define the STRUCTURE of MCP messages without requiring
;; runtime cache data. Payload fields use :any until resolved at runtime.
;;
;; Design principle: Same schema resolution API works at all three times:
;; - Config time (def-action): envelope + :any payload
;; - Start time (start-fsm): envelope + :any payload (could warm cache)
;; - Runtime: envelope + full payload schema from cache
;;------------------------------------------------------------------------------

(def json-rpc-request-envelope
  "Static JSON-RPC 2.0 request envelope. Payload in 'params' is :any until resolved."
  [:map {:closed false :description "JSON-RPC 2.0 request"}
   ["jsonrpc" [:= "2.0"]]
   ["id" [:or :int :string]] ;; MCP allows string or int IDs
   ["method" :string]
   ["params" {:optional true} :any]])

(def json-rpc-response-envelope
  "Static JSON-RPC 2.0 response envelope. Payload in 'result' is :any until resolved."
  [:map {:closed false :description "JSON-RPC 2.0 response"}
   ["jsonrpc" [:= "2.0"]]
   ["id" [:or :int :string]]
   ["result" {:optional true} :any]
   ["error" {:optional true}
    [:map
     ["code" :int]
     ["message" :string]
     ["data" {:optional true} :any]]]])

(def json-rpc-notification-envelope
  "Static JSON-RPC 2.0 notification envelope (no id field).
   Closed map - rejects messages with 'id' field (those are requests, not notifications)."
  [:map {:closed true :description "JSON-RPC 2.0 notification"}
   ["jsonrpc" [:= "2.0"]]
   ["method" :string]
   ["params" {:optional true} :any]])

(defn make-request-envelope-schema
  "Build request envelope with specific method and params schema.
   If params-schema is nil, uses :any."
  [method params-schema]
  [:map {:closed true}
   ["jsonrpc" [:= "2.0"]]
   ["id" [:or :int :string]]
   ["method" [:= method]]
   ["params" (or params-schema :any)]])

(defn make-response-envelope-schema
  "Build response envelope with specific result schema.
   If result-schema is nil, uses :any."
  [result-schema]
  [:map {:closed true}
   ["jsonrpc" [:= "2.0"]]
   ["id" [:or :int :string]]
   ["result" (or result-schema :any)]])

;;------------------------------------------------------------------------------
;; Phase 2: Runtime Schema Resolution (Story #64)
;;------------------------------------------------------------------------------
;;
;; These functions resolve schemas at runtime by looking up the MCP cache.
;; They work at all three times:
;; - Config/start time: No cache or tool-name → returns envelope + :any
;; - Runtime: Cache available → returns envelope + full tool schema
;;------------------------------------------------------------------------------

(defn find-tool-in-cache
  "Find a tool definition by name in the cache.
   Returns the tool map or nil if not found."
  [cache tool-name]
  (when-let [tools (get cache "tools")]
    (some #(when (= tool-name (get % "name")) %) tools)))

(defn resolve-mcp-tool-input-schema
  "Resolve the input (params) schema for a specific tool.
   Returns the Malli schema for tool arguments, or :any if tool not found."
  [cache tool-name]
  (if-let [tool (find-tool-in-cache cache tool-name)]
    ;; Use existing tool-cache->request-schema but extract just the params part
    (let [input-schema (get tool "inputSchema")]
      (if input-schema
        [:json-schema {:schema input-schema}]
        :any))
    :any))

(defn resolve-mcp-tool-output-schema
  "Resolve the output (result) schema for a specific tool.
   MCP tools always return tool-response format."
  [_cache _tool-name]
  ;; All MCP tools return the same response structure
  [:ref "tool-response"])

(defn resolve-mcp-request-schema
  "Build complete JSON-RPC request schema for a tool call.
   
   At runtime with cache: envelope + typed params for specific tool
   At config/start without cache: envelope + :any params
   
   Parameters:
   - context: FSM context containing cache at (get context \"state\")
   - tool-name: Name of tool to resolve (nil for generic envelope)"
  [context tool-name]
  (let [cache (get context "state")
        params-schema (if tool-name
                        [:map {:closed true}
                         ["name" [:= tool-name]]
                         ["arguments" (resolve-mcp-tool-input-schema cache tool-name)]]
                        :any)]
    (make-request-envelope-schema "tools/call" params-schema)))

(defn resolve-mcp-response-schema
  "Build complete JSON-RPC response schema for a tool call.
   
   MCP tool responses always have the same structure (tool-response).
   
   Parameters:
   - context: FSM context (currently unused, for API consistency)
   - tool-name: Name of tool (currently unused, all tools have same response)"
  [context tool-name]
  (let [cache (get context "state")
        result-schema (resolve-mcp-tool-output-schema cache tool-name)]
    (make-response-envelope-schema result-schema)))

;;------------------------------------------------------------------------------
;; Phase 3: FSM Integration (Story #64)
;;------------------------------------------------------------------------------
;;
;; Schema functions for use with FSM's :id->schema lookup.
;; These follow the (context xition) -> schema signature.
;;
;; At config/start time: tool-name not yet known → generic envelope
;; At runtime: tool-name can be extracted from xition metadata → specific schema
;;------------------------------------------------------------------------------

(defn mcp-tool-request-schema-fn
  "Schema function for :id->schema registration.
   
   Works at all three times:
   - Config/start: Returns envelope with :any params (tool unknown)
   - Runtime: If xition has 'tool-name' metadata, resolves specific schema
   
   Usage in FSM:
   {:id->schema {\"mcp-tool-request\" mcp-tool-request-schema-fn}}"
  [context xition]
  (let [tool-name (get xition "tool-name")]
    (resolve-mcp-request-schema context tool-name)))

(defn mcp-tool-response-schema-fn
  "Schema function for :id->schema registration.
   
   Works at all three times:
   - Config/start: Returns envelope with :any result
   - Runtime: If xition has 'tool-name' metadata, resolves specific schema
   
   Usage in FSM:
   {:id->schema {\"mcp-tool-response\" mcp-tool-response-schema-fn}}"
  [context xition]
  (let [tool-name (get xition "tool-name")]
    (resolve-mcp-response-schema context tool-name)))

(defn tool-cache->request-schema [tool-cache]
  (let [tool-name (get tool-cache "name")
        input-schema (get tool-cache "inputSchema")]
    [:map {:closed true}
     ["name" [:= tool-name]]
     ["arguments" (if input-schema [:json-schema {:schema input-schema}] :any)]]))

(defn tool-cache->response-schema [_] [:ref "tool-response"])
(defn tools-cache->request-schema [tools-cache] (into [:or] (mapv tool-cache->request-schema tools-cache)))
(defn tools-cache->response-schema
  "Response schema for tool calls - uses inlined schema to avoid ref resolution issues."
  [_]
  inlined-tool-response-schema)

(defn resources-cache->request-schema [resources-cache]
  [:map {:closed true} ["uri" (into [:enum] (mapv #(get % "uri") resources-cache))]])

(defn prompt-cache->request-schema [prompt-cache]
  (let [prompt-name (get prompt-cache "name")
        arguments (get prompt-cache "arguments" [])
        has-required-args? (some #(get % "required") arguments)
        arg-entries (mapv (fn [{n "name" r "required"}] (if r [n :string] [n {:optional true} :string])) arguments)]
    [:map {:closed true}
     ["name" [:= prompt-name]]
     (if has-required-args?
       ["arguments" (into [:map {:closed true}] arg-entries)]
       ["arguments" {:optional true} (if (seq arg-entries) (into [:map {:closed true}] arg-entries) [:map])])]))

(defn prompts-cache->request-schema [prompts-cache] (into [:or] (mapv prompt-cache->request-schema prompts-cache)))

(defn mcp-cache->request-schema [{tools "tools" resources "resources" prompts "prompts"}]
  (let [wrap-jsonrpc (fn [method params-schema]
                       [:map {:closed true}
                        ["jsonrpc" [:= "2.0"]] ["id" :int] ["method" [:= method]] ["params" params-schema]])
        schemas (cond-> []
                  (seq tools) (conj (wrap-jsonrpc "tools/call" (tools-cache->request-schema tools)))
                  (seq resources) (conj (wrap-jsonrpc "resources/read" (resources-cache->request-schema resources)))
                  (seq prompts) (conj (wrap-jsonrpc "prompts/get" (prompts-cache->request-schema prompts)))
                  true (conj (wrap-jsonrpc "logging/setLevel" logging-set-level-request-schema)))]
    (into [:or] schemas)))

(defn mcp-cache->response-schema
  "Generate response schema for all MCP methods. Uses inlined schemas to avoid ref resolution issues."
  [{tools "tools" :as _cache}]
  (let [wrap-response (fn [result-schema]
                        [:map {:closed true} ["jsonrpc" [:= "2.0"]] ["id" :int] ["result" result-schema]])
        wrap-notification (fn [method params-schema]
                            [:map {:closed true} ["jsonrpc" [:= "2.0"]] ["method" [:= method]] ["params" params-schema]])
        tool-response (when (seq tools) (wrap-response (tools-cache->response-schema tools)))
        schemas (cond-> [(wrap-response inlined-resource-response-schema)
                         (wrap-response inlined-prompt-response-schema)
                         (wrap-notification "notifications/message" inlined-logging-notification-schema)]
                  tool-response (conj tool-response))]
    (into [:or] schemas)))

(defn mcp-request-schema-fn [context _xition] (mcp-cache->request-schema (get context "state")))
(defn mcp-response-schema-fn [context _xition] (mcp-cache->response-schema (get context "state")))

(defn mcp-request-xition-schema-fn
  "Schema for LLM→servicing transition. Supports single or batch requests."
  [context {xid "id" :as _xition}]
  (let [single-request-schema (mcp-cache->request-schema (get context "state"))
        batch-request-schema [:vector single-request-schema]]
    [:map {:closed true}
     ["id" [:= xid]]
     ["message" [:or single-request-schema batch-request-schema]]]))

(defn mcp-response-xition-schema-fn
  "Schema for servicing→LLM transition. Supports single or batch responses."
  [context {xid "id" :as _xition}]
  (let [single-response-schema (mcp-cache->response-schema (get context "state"))
        batch-response-schema [:vector single-response-schema]]
    [:map {:closed true}
     ["id" [:= xid]]
     ["document" {:optional true} :string]
     ["message" {:optional true} [:or single-response-schema batch-response-schema]]]))
