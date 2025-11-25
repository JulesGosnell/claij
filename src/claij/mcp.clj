(ns claij.mcp
  (:require
   [clojure.tools.logging :as log]
   [clojure.string :refer [starts-with?]]
   [clojure.data.json :refer [write-str read-str]]
   [clojure.core.async :refer [chan go-loop >! alts! <!! >!!]]
   [claij.util :refer [def-m2 map-values]]
   [clj-http.client :refer [get] :rename {get http-get}]
   [claij.fsm :refer [def-fsm schema-base-uri start-fsm]]
   ;;[claij.llm :refer [llm-action]]
   [claij.mcp.bridge :refer [start-mcp-bridge]]))

(def-m2
  mcp-schema
  (assoc
   ;; N.B. This is a BIG schema - 1598 lines
   (read-str (:body (http-get "https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/refs/heads/main/schema/2025-06-18/schema.json")))
   "$$id"
   (str schema-base-uri "/" "mcp.json")))

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

;; these should be initialised LAZILY !

;; (def mcp-services
;;   (atom
;;    (map-values
;;     (fn [_ config]
;;       (let [n 100
;;             ic (chan n (map write-str))
;;             oc (chan n (comp (map read-str) (filter (complement notification?))))
;;             stop (start-mcp-bridge config ic oc)
;;             _ (>!! ic initialise-request)
;;             r (<!! oc)]
;;         [r ic oc stop]))
;;     (mcp-config "mcpServers"))))

;; ;; how do we hook this up ?

;; (defn dispatcher [ic oc]
;;   ;; connect fsm -> mcp
;;   ;; (go-loop []
;;   ;;   (when-some [{id "id" r "request"} (<! ic)]
;;   ;;     (let [[i] (@mcp-services id)]
;;   ;;       (>! i (write-str r))))
;;   ;;   (recur))
;;   ;; connect mcp -> fsm
;;   (go-loop []
;;     (let [id->s @mcp-services
;;           oc->id (reduce-kv (fn [acc id [_ic oc _stop]] (assoc acc oc id)) {} id->s) ;wasteful...
;;           [event c] (alts! (map second (vals id->s)))]
;;       (>! oc {"id" (oc->id c) "response" (read-str event)}))
;;     (recur)))

;; ;; TODO: add descriptions

;; (def-fsm mcp-fsm
;;   {"schema" mcp-schema
;;    "id" "mcp"

;;    "prompts" ["Coordinate MCP interactions."]

;;    "states"
;;    [;; entry point
;;     {"id" "start"}

;;     ;; calls llm with text from start and list of extant mcp services asking fr an mcp request
;;     {"id" "info"
;;      "prompts" ["This is a map of [id : initialisation-response] of the available mcp services:"
;;                 (write-str (map-values (fn [_k [ir]] ir) @mcp-services))
;;                 "If you would like to make an MCP request please build one conformnt to your output schema and return it"]
;;      "action" "llm"}

;;     ;; receives an mcp request, dispatches onto service, returns an mcp response
;;     {"id" "mcp"
;;      "action" "mcp"
;;      "prompts" ["Process an MCP non-Initialize request."]}

;;     ;; exit point
;;     {"id" "end"}]

;;    "xitions"
;;    [{"id" ["start" "info"]
;;      "schema" {"type" "string"}}

;;     {"id" ["info" "mcp"]
;;      "schema" {"properties"
;;                {"id"
;;                 {"description" "The id of the mcp sevice that you want to talk to."
;;                  "type" "string"}
;;                 "request"
;;                 {"description" "The MCP request that you want to send"
;;                  "type"
;;                  {"$ref" "#/definitions/JSONRPCRequest"}}}}}

;;     {"id" ["mcp" "info"]
;;      "schema" {"properties"
;;                {"id"
;;                 {"description" "The id of the mcp sevice that you talked to."
;;                  "type" "string"}
;;                 "response"
;;                 {"description" "The MCP response that you received"
;;                  "type"
;;                  {"$ref" "#/definitions/JSONRPCResponse"}}}}}

;;     {"id" ["info" "end"]
;;      "schema" true}

;;     ;; TODO: looks like we might need to plumb start and end types of sub-fsms into super-fsms and not assume that they are just (type string}
;;     ]})

;; ;; TODO: instead of piping everything backwards and forwards with
;; ;; go-loops, we should be able to plumb all this together with async
;; ;; constructs or even just plug channels directly in instead of doing
;; ;; a->b->c

;; (defn mcp-action [context fsm ix state [[_is {id "id" r "request"} _os]] handler]
;;   (let [[_ir ic _oc _stop] (mcp-services id)]
;;     (>!! ic r)))

;; ;; TODO:
;; ;; connect dispatcher
;; ;; logging would be helpful
;; ;; complete integration of llm-action
;; ;; get this working !

;; ;; long-term
;; ;; mcp-services may be restarted - the fsm needs to refresh them each time we go into init state - prompts may need to understand dereffables
;; ;; shrink (shake-out) schema ? - not sure we can do that now all requests go through same state...
;; ;; keep an eye on billing to see if mcp too expensive

;; ;; tidy up
;; ;; test
;; ;; worry about parallel invocations
;; ;; shrink schema use with DSL

;; (def mcp-actions
;;   {
;;    ;; "llm" llm-action ;; TODO - needs parameters in state - check this
;;    "mcp" mcp-action})

(comment

  (start-fsm
   {:id->action mcp-actions})

;; double check that the fsm schema is only included once in our state - on entry to the fsm

  ;; hmmm... how will LLM know which schema we are $ref-ing if we keep switcing schemas - we may have to qualify refs... (i.e. use external ones)
  )
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

(defn merge-resources
  "Merge read-resource response contents into existing resources list.
  
  Takes a vector of resources (each with a 'uri' key) and a vector of contents
  (each with 'uri' and 'text' keys). Returns the resources vector with text
  merged in based on matching URIs."
  [resources contents]
  (let [id->index (reduce (fn [acc [n {id "uri"}]] (assoc acc id n)) {} (map-indexed vector resources))]
    (reduce
     (fn [acc {id "uri" t "text"}]
       (assoc-in acc [(id->index id) "text"] t))
     resources
     contents)))

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
;; Tool schema generation
;; ============================================================

(def tool-response-schema
  "Minimal schema for MCP tool call responses (CallToolResult).
   Covers: TextContent, ImageContent, AudioContent, ResourceLink, EmbeddedResource"
  {"type" "object"
   "properties"
   {"content"
    {"type" "array"
     "items"
     {"anyOf"
      [{"type" "object"
        "properties" {"type" {"const" "text"}
                      "text" {"type" "string"}}
        "required" ["type" "text"]}
       {"type" "object"
        "properties" {"type" {"const" "image"}
                      "data" {"type" "string"}
                      "mimeType" {"type" "string"}}
        "required" ["type" "data" "mimeType"]}
       {"type" "object"
        "properties" {"type" {"const" "audio"}
                      "data" {"type" "string"}
                      "mimeType" {"type" "string"}}
        "required" ["type" "data" "mimeType"]}
       {"type" "object"
        "properties" {"type" {"const" "resource_link"}
                      "uri" {"type" "string"}}
        "required" ["type" "uri"]}
       {"type" "object"
        "properties" {"type" {"const" "resource"}
                      "resource" {"type" "object"}}
        "required" ["type" "resource"]}]}}
    "isError" {"type" "boolean"}
    "structuredContent" {"type" "object"}}
   "required" ["content"]})

(defn tool-cache->request-schema
  "Generate a JSON Schema for a tool call request from cached tool info.
   Preserves descriptions to guide LLM output generation."
  [tool-cache]
  (let [tool-name (get tool-cache "name")
        input-schema (get tool-cache "inputSchema")]
    {"type" "object"
     "properties" {"name" {"const" tool-name}
                   "arguments" input-schema}
     "required" ["name" "arguments"]}))

(defn tool-cache->response-schema
  "Generate response schema for a tool.
   If tool has outputSchema, constrains structuredContent to match it."
  [tool-cache]
  (if-let [output-schema (get tool-cache "outputSchema")]
    (assoc-in tool-response-schema
              ["properties" "structuredContent"]
              output-schema)
    tool-response-schema))

(defn tools-cache->request-schema
  "Generate a oneOf schema for all tools in cache."
  [tools-cache]
  {"oneOf" (mapv tool-cache->request-schema tools-cache)})

(defn tools-cache->response-schema
  "Generate an anyOf schema for all tool responses in cache."
  [tools-cache]
  {"anyOf" (mapv tool-cache->response-schema tools-cache)})

(def resource-response-schema
  "Schema for MCP resource read responses (ReadResourceResult).
   Contents can be text or blob (base64 binary)."
  {"type" "object"
   "properties"
   {"contents"
    {"type" "array"
     "items"
     {"anyOf"
      [{"type" "object"
        "properties" {"uri" {"type" "string"}
                      "text" {"type" "string"}
                      "mimeType" {"type" "string"}}
        "required" ["uri" "text"]}
       {"type" "object"
        "properties" {"uri" {"type" "string"}
                      "blob" {"type" "string"}
                      "mimeType" {"type" "string"}}
        "required" ["uri" "blob"]}]}}}
   "required" ["contents"]})

(defn resources-cache->request-schema
  "Generate request schema constraining uri to known resources."
  [resources-cache]
  {"type" "object"
   "properties" {"uri" {"type" "string"
                        "enum" (mapv #(get % "uri") resources-cache)}}
   "required" ["uri"]})

(def prompt-response-schema
  "Schema for MCP prompt get responses (GetPromptResult).
   Returns messages with role and content blocks."
  {"type" "object"
   "properties"
   {"description" {"type" "string"}
    "messages"
    {"type" "array"
     "items"
     {"type" "object"
      "properties"
      {"role" {"type" "string" "enum" ["user" "assistant"]}
       "content"
       {"anyOf"
        [{"type" "object"
          "properties" {"type" {"const" "text"}
                        "text" {"type" "string"}}
          "required" ["type" "text"]}
         {"type" "object"
          "properties" {"type" {"const" "image"}
                        "data" {"type" "string"}
                        "mimeType" {"type" "string"}}
          "required" ["type" "data" "mimeType"]}
         {"type" "object"
          "properties" {"type" {"const" "audio"}
                        "data" {"type" "string"}
                        "mimeType" {"type" "string"}}
          "required" ["type" "data" "mimeType"]}
         {"type" "object"
          "properties" {"type" {"const" "resource_link"}
                        "uri" {"type" "string"}}
          "required" ["type" "uri"]}
         {"type" "object"
          "properties" {"type" {"const" "resource"}
                        "resource" {"type" "object"}}
          "required" ["type" "resource"]}]}}
      "required" ["role" "content"]}}}
   "required" ["messages"]})

(defn prompt-cache->request-schema
  "Generate request schema for a single prompt from cache entry.
   Arguments are always strings in MCP prompts."
  [prompt-cache]
  (let [prompt-name (get prompt-cache "name")
        arguments (get prompt-cache "arguments" [])
        arg-properties (into {}
                             (map (fn [{n "name" d "description"}]
                                    [n (cond-> {"type" "string"}
                                         d (assoc "description" d))]))
                             arguments)
        required-args (vec (keep (fn [{n "name" r "required"}]
                                   (when r n))
                                 arguments))]
    {"type" "object"
     "properties" {"name" {"const" prompt-name}
                   "arguments" (cond-> {"type" "object"
                                        "properties" arg-properties}
                                 (seq required-args) (assoc "required" required-args))}
     "required" ["name"]}))

(defn prompts-cache->request-schema
  "Generate a oneOf schema for all prompts in cache."
  [prompts-cache]
  {"oneOf" (mapv prompt-cache->request-schema prompts-cache)})

