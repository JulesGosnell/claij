(ns claij.llm.tools
  "Translation layer between native LLM tool formats and MCP event formats.
   
   Native tool_calls format (from claij.llm.service):
   [{:id \"call_123\"
     :name \"tool_name\"
     :arguments {:arg1 \"value\"}}]   ;; keyword keys from JSON parsing
   
   MCP event format (for FSM transitions):
   {\"id\" [\"from\" \"to\"]
    \"calls\" {\"server\" [{\"jsonrpc\" \"2.0\"
                          \"id\" \"call_123\"
                          \"method\" \"tools/call\"
                          \"params\" {\"name\" \"tool\"
                                     \"arguments\" {\"arg1\" \"value\"}}}]}}
   
   Key transformations:
   - keyword keys → string keys (stringify-keys)
   - Prefixed tool names → server routing (parse-prefixed-tool-name)
   - Native format → MCP JSON-RPC format"
  (:require
   [clojure.walk :refer [postwalk]]))

;;------------------------------------------------------------------------------
;; Helper: Stringify Keys
;;------------------------------------------------------------------------------

(defn stringify-keys
  "Recursively convert all keyword keys to string keys in maps.
   
   This is critical because:
   - LLM service returns arguments with keyword keys (json/parse-string true)
   - MCP expects all string keys for schema validation
   
   Examples:
     (stringify-keys {:a 1 :b {:c 2}})
     => {\"a\" 1 \"b\" {\"c\" 2}}
     
     (stringify-keys {:a [1 2 {:b 3}]})
     => {\"a\" [1 2 {\"b\" 3}]}"
  [x]
  (postwalk
   (fn [node]
     (if (map? node)
       (into {} (map (fn [[k v]] [(if (keyword? k) (name k) k) v]) node))
       node))
   x))

;;------------------------------------------------------------------------------
;; Helper: Parse Prefixed Tool Name
;;------------------------------------------------------------------------------

(defn parse-prefixed-tool-name
  "Parse a potentially prefixed tool name into [server-name tool-name].
   
   Multi-server MCP setups use prefixed names for routing:
   - \"github__list_issues\" → [\"github\" \"list_issues\"]
   - \"tools__bash\" → [\"tools\" \"bash\"]
   
   Non-prefixed names go to default server:
   - \"bash\" → [\"default\" \"bash\"]
   - \"clojure_eval\" → [\"default\" \"clojure_eval\"]"
  [prefixed-name]
  (if-let [[_ server tool] (re-matches #"^([^_]+)__(.+)$" prefixed-name)]
    [server tool]
    ["default" prefixed-name]))

;;------------------------------------------------------------------------------
;; Native Tool Calls → MCP Event
;;------------------------------------------------------------------------------

(defn native-tool-call->mcp-request
  "Convert a single native tool call to MCP JSON-RPC request format.
   
   Input (from LLM service):
   {:id \"call_123\" :name \"bash\" :arguments {:command \"ls\"}}
   
   Output (MCP JSON-RPC):
   {\"jsonrpc\" \"2.0\"
    \"id\" \"call_123\"
    \"method\" \"tools/call\"
    \"params\" {\"name\" \"bash\" \"arguments\" {\"command\" \"ls\"}}}"
  [{:keys [id name arguments]}]
  (let [[_server tool-name] (parse-prefixed-tool-name name)]
    {"jsonrpc" "2.0"
     "id" id
     "method" "tools/call"
     "params" {"name" tool-name
               "arguments" (stringify-keys arguments)}}))

(defn native-tool-calls->mcp-event
  "Convert native tool_calls to MCP FSM event format.
   
   Groups tool calls by server (extracted from prefixed names) and
   creates an FSM event that can transition to the MCP state.
   
   Input:
   - from-state: Current state ID (e.g., \"llm\")
   - to-state: MCP state ID (e.g., \"llm-mcp\")
   - tool-calls: Vector of native tool calls from LLM service
   
   Output:
   {\"id\" [\"llm\" \"llm-mcp\"]
    \"calls\" {\"server1\" [requests...] \"server2\" [requests...]}}"
  [from-state to-state tool-calls]
  (let [;; Group by server
        grouped (group-by (fn [{:keys [name]}]
                            (first (parse-prefixed-tool-name name)))
                          tool-calls)
        ;; Convert each group to MCP requests
        calls-by-server (into {}
                              (map (fn [[server calls]]
                                     [server (mapv native-tool-call->mcp-request calls)])
                                   grouped))]
    {"id" [from-state to-state]
     "calls" calls-by-server}))

;;------------------------------------------------------------------------------
;; MCP Tools → Native Tools (for LLM request)
;;------------------------------------------------------------------------------

(defn mcp-tool->native-tool
  "Convert a single MCP tool definition to OpenAI native tool format.
   
   MCP format (from tools/list):
   {\"name\" \"bash\"
    \"description\" \"Run command\"
    \"inputSchema\" {...}}
   
   OpenAI format:
   {:type \"function\"
    :function {:name \"bash\"
               :description \"Run command\"
               :parameters {...}}}
   
   For multi-server setups, server-name is used to prefix the tool name."
  ([mcp-tool]
   (mcp-tool->native-tool nil mcp-tool))
  ([server-name {:strs [name description inputSchema]}]
   (let [tool-name (if (and server-name (not= server-name "default"))
                     (str server-name "__" name)
                     name)
         desc (if (and server-name (not= server-name "default"))
                (str "[" server-name "] " (or description ""))
                (or description ""))]
     {:type "function"
      :function {:name tool-name
                 :description desc
                 :parameters (or inputSchema {"type" "object" "properties" {}})}})))

(defn mcp-tools->native-tools
  "Convert MCP tools (by server) to OpenAI native tool format.
   
   Takes a map of server-name -> vector of MCP tools.
   Returns a vector of native tools with prefixed names for multi-server routing.
   
   Single server (\"default\"):
   - Tool names are NOT prefixed
   - Descriptions are NOT prefixed
   
   Multi-server:
   - Tool names are prefixed: \"github__list_issues\"
   - Descriptions are prefixed: \"[github] List issues\""
  [tools-by-server]
  (let [single-server? (and (= 1 (count tools-by-server))
                            (contains? tools-by-server "default"))]
    (if single-server?
      ;; Single default server - no prefixing
      (mapv mcp-tool->native-tool (get tools-by-server "default"))
      ;; Multi-server - prefix all tool names
      (vec
       (for [[server-name tools] tools-by-server
             tool tools]
         (mcp-tool->native-tool server-name tool))))))
