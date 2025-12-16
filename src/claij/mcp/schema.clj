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

(def mcp-registry (mr/composite-registry base-registry mcp-schemas))

(def tool-response-schema [:ref "tool-response"])
(def resource-response-schema [:ref "resource-response"])
(def prompt-response-schema [:ref "prompt-response"])
(def logging-level-strings #{"debug" "info" "notice" "warning" "error" "critical" "alert" "emergency"})
(def logging-levels [:ref "logging-level"])
(def logging-set-level-request-schema [:ref "logging-set-level-request"])
(def logging-notification-schema [:ref "logging-notification"])

(defn tool-cache->request-schema [tool-cache]
  (let [tool-name (get tool-cache "name")
        input-schema (get tool-cache "inputSchema")]
    [:map {:closed true}
     ["name" [:= tool-name]]
     ["arguments" (if input-schema [:json-schema {:schema input-schema}] :any)]]))

(defn tool-cache->response-schema [_] [:ref "tool-response"])
(defn tools-cache->request-schema [tools-cache] (into [:or] (mapv tool-cache->request-schema tools-cache)))
(defn tools-cache->response-schema [_] [:ref "tool-response"])

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

(defn mcp-cache->response-schema [{tools "tools" :as _cache}]
  (let [wrap-response (fn [result-schema]
                        [:map {:closed true} ["jsonrpc" [:= "2.0"]] ["id" :int] ["result" result-schema]])
        wrap-notification (fn [method params-schema]
                            [:map {:closed true} ["jsonrpc" [:= "2.0"]] ["method" [:= method]] ["params" params-schema]])
        tool-response (when (seq tools) (wrap-response (tools-cache->response-schema tools)))
        schemas (cond-> [(wrap-response resource-response-schema)
                         (wrap-response prompt-response-schema)
                         (wrap-notification "notifications/message" logging-notification-schema)]
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
