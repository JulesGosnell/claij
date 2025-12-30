(ns claij.mcp.schema
  "JSON Schema generation for MCP protocol messages.
   
   Generates JSON Schema for MCP tool/resource/prompt calls that can be
   used for FSM transition validation.
   
   Also provides conversion functions between MCP and Native (OpenAI) formats:
   - M2 (Schema): mcp-tool-schema->native-tool-def
   - M1 (Instance): native-tool-call->mcp-tool-call, mcp-tool-result->native-tool-result")

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
  "Generate request schema for a single tool.
   Includes description and inputSchema to make the schema self-documenting.
   LLMs can use the description to understand when to call the tool."
  [tool-cache]
  (let [tool-name (get tool-cache "name")
        tool-desc (get tool-cache "description")
        input-schema (get tool-cache "inputSchema" {})]
    (cond-> {"type" "object"
             "additionalProperties" false
             "required" ["name" "arguments"]
             "properties" {"name" {"const" tool-name}
                           "arguments" input-schema}}
      tool-desc (assoc "description" tool-desc))))

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
    {"oneOf" schemas}))

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

;;------------------------------------------------------------------------------
;; Native ↔ MCP Format Conversions
;;
;; "Native" = OpenAI/LLM provider format (what LLM APIs speak)
;; "MCP" = MCP protocol format (what MCP servers speak)
;;
;; M2 (Schema): For building LLM tools parameter from hat's schema
;; M1 (Instance): For runtime message routing between LLM and MCP
;;------------------------------------------------------------------------------

;; Detection constant - used by hat to mark schemas, by llm-action to detect them
(def native-tools-schema-title
  "Magic value for JSON Schema 'title' field to identify MCP tool schemas.
   Hat marks its output schema with this title so llm-action can detect it."
  "mcp-tools")

(defn native-tools-schema?
  "Check if a schema is an MCP tools schema by its title marker."
  [schema]
  (= native-tools-schema-title (get schema "title")))

;;------------------------------------------------------------------------------
;; M2: Schema Conversions (MCP → Native)
;; 
;; These convert hat's tool schemas to OpenAI native tool definitions.
;; Used by llm-action to build the :tools parameter for LLM calls.
;;------------------------------------------------------------------------------

(defn mcp-tool-schema->native-tool-def
  "Convert hat's tool schema to OpenAI native tool definition.
   
   MCP tool schema (from tool-cache->request-schema):
   {\"type\" \"object\"
    \"description\" \"Run shell command\"
    \"properties\" {\"name\" {\"const\" \"bash\"}
                   \"arguments\" {<inputSchema>}}}
   
   OpenAI native tool definition:
   {\"type\" \"function\"
    \"function\" {\"name\" \"bash\"
                 \"description\" \"Run shell command\"
                 \"parameters\" {<inputSchema>}}}"
  [tool-schema]
  {"type" "function"
   "function" {"name" (get-in tool-schema ["properties" "name" "const"])
               "description" (get tool-schema "description" "")
               "parameters" (get-in tool-schema ["properties" "arguments"] {})}})

(defn mcp-tool-schemas-from-request-schema
  "Extract individual tool schemas from full MCP request schema.
   
   The full schema (from hat-mcp-request-schema-fn) has structure:
   {\"properties\" {\"calls\" {\"additionalProperties\" 
                             {\"items\" {\"oneOf\" [{\"properties\" 
                                                    {\"params\" {\"oneOf\" [<tool-schemas>]}}}]}}}}}
   
   Returns vector of tool schemas."
  [mcp-schema]
  (get-in mcp-schema ["properties" "calls" "additionalProperties" 
                      "items" "oneOf" 0 "properties" "params" "oneOf"]))

(defn mcp-request-schema->native-tool-defs
  "Convert full MCP request schema to vector of native tool definitions.
   
   Full pipeline: MCP schema → extract tool schemas → convert each to native."
  [mcp-schema]
  (when-let [tool-schemas (mcp-tool-schemas-from-request-schema mcp-schema)]
    (mapv mcp-tool-schema->native-tool-def tool-schemas)))

;;------------------------------------------------------------------------------
;; M1: Instance Conversions (Bidirectional)
;;
;; These convert runtime messages between LLM and MCP formats.
;; Used by llm-action to route tool_calls to MCP and results back to LLM.
;;------------------------------------------------------------------------------

(defn parse-prefixed-tool-name
  "Parse a potentially prefixed tool name into [server-name tool-name].
   
   Multi-server MCP setups use prefixed names for routing:
   - \"github__list_issues\" → [\"github\" \"list_issues\"]
   - \"tools__bash\" → [\"tools\" \"bash\"]
   
   Non-prefixed names go to default server:
   - \"bash\" → [\"default\" \"bash\"]"
  [prefixed-name]
  (if-let [[_ server tool] (re-matches #"^([^_]+)__(.+)$" prefixed-name)]
    [server tool]
    ["default" prefixed-name]))

(defn native-tool-call->mcp-tool-call
  "Convert native tool_call to MCP tools/call request.
   
   Native tool_call (from LLM response):
   {\"id\" \"call_123\"
    \"name\" \"bash\"  
    \"arguments\" {\"command\" \"ls\"}}
   
   MCP tools/call request:
   {\"jsonrpc\" \"2.0\"
    \"id\" \"call_123\"
    \"method\" \"tools/call\"
    \"params\" {\"name\" \"bash\" \"arguments\" {\"command\" \"ls\"}}}"
  [{:strs [id name arguments] :as tool-call}]
  (let [[_server tool-name] (parse-prefixed-tool-name name)]
    {"jsonrpc" "2.0"
     "id" id
     "method" "tools/call"
     "params" {"name" tool-name
               "arguments" (or arguments {})}}))

(defn mcp-tool-result->native-tool-result
  "Convert MCP tool result to native tool result message for LLM.
   
   MCP result:
   {\"result\" {\"content\" [{\"type\" \"text\" \"text\" \"output here\"}]}}
   
   Native tool result (for LLM conversation):
   {\"role\" \"tool\"
    \"tool_call_id\" \"call_123\"
    \"content\" \"output here\"}"
  [mcp-result tool-call-id]
  (let [content-items (get-in mcp-result ["result" "content"] [])
        ;; Concatenate all text content
        text-content (->> content-items
                          (filter #(= "text" (get % "type")))
                          (map #(get % "text"))
                          (clojure.string/join "\n"))]
    {"role" "tool"
     "tool_call_id" tool-call-id
     "content" text-content}))

;;------------------------------------------------------------------------------
;; XOR Constraint for Native Tool Calling
;;------------------------------------------------------------------------------

(def xor-tool-prompt
  "Prompt text to enforce XOR constraint: tool_calls OR content, never both.
   LLMs are trained on chat UIs where returning both is normal - we need this
   prompt to enforce clean separation for FSM transition routing."
  "TOOL CALLING RULES:
Return EITHER a tool call OR a JSON response, NEVER both.

- If you need to use a tool: Return ONLY the tool call. Do NOT include any text or JSON.
- If you have a final answer: Return ONLY the JSON object. Do NOT include any tool calls.

Returning both tool_calls and content together is a protocol error.")

(defn find-mcp-xition
  "Find the MCP tools xition from a collection of output xitions.
   Returns the xition whose schema has the native-tools-schema-title, or nil."
  [output-xitions schema-resolver]
  (first
   (filter
    (fn [xition]
      (let [schema (schema-resolver xition)]
        (native-tools-schema? schema)))
    output-xitions)))

(defn extract-native-tools-from-xitions
  "Extract native tools from output xitions if an MCP xition exists.
   
   Returns {:tools [...] :mcp-xition xition} if MCP tools found, nil otherwise.
   
   schema-resolver is a function (fn [xition] schema) that resolves the schema
   for a given xition (needed because schema resolution depends on FSM context)."
  [output-xitions schema-resolver]
  (when-let [mcp-xition (find-mcp-xition output-xitions schema-resolver)]
    (let [schema (schema-resolver mcp-xition)
          tools (mcp-request-schema->native-tool-defs schema)]
      {:tools tools
       :mcp-xition mcp-xition
       :mcp-schema schema})))

(defn xor-violation?
  "Check if LLM response violates XOR constraint (has both tool_calls AND content).
   Returns true if violation detected, false otherwise."
  [response]
  (boolean
   (and (seq (get response "tool_calls"))
        (some? (get response "content"))
        (not (clojure.string/blank? (get response "content"))))))

(defn native-tool-calls->mcp-event
  "Convert native tool_calls to MCP event format.
   
   Takes the MCP xition id and a vector of native tool calls,
   returns an MCP event that can be used as FSM transition.
   
   Native tool_calls (from LLM, string keys):
   [{\"id\" \"call_123\" \"name\" \"bash\" \"arguments\" {\"cmd\" \"ls\"}}
    {\"id\" \"call_456\" \"name\" \"github__list_issues\" \"arguments\" {\"repo\" \"x\"}}]
   
   MCP event:
   {\"id\" [\"llm\" \"llm-mcp\"]
    \"calls\" {\"default\" [{mcp-request}]
              \"github\" [{mcp-request}]}}"
  [mcp-xition-id tool-calls]
  (let [grouped-calls (group-by 
                       (fn [tc]
                         (first (parse-prefixed-tool-name (get tc "name"))))
                       tool-calls)
        mcp-calls (into {}
                        (map (fn [[server calls]]
                               [server (mapv native-tool-call->mcp-tool-call calls)])
                             grouped-calls))]
    {"id" mcp-xition-id
     "calls" mcp-calls}))
