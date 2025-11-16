(ns claij.fsm.mcp-fsm
  (:require
   [clojure.tools.logging :as log]
   [clojure.java.io :refer [resource]]
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
   (read-str (slurp (resource "mcp/schema.json")))
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
    {"command"  "bash",
     "args" ["-c", "cd /home/jules/src/m3 && ./bin/mcp-language-server.sh"],
     "transport" "stdio"},
    "claij-clojure-tools"
    {"command" "bash",
     "args" ["-c", "cd /home/jules/src/claij && ./bin/mcp-clojure-tools.sh"],
     "transport" "stdio"},
    "claij-clojure-language-server"
    {"command"  "bash",
     "args" ["-c", "cd /home/jules/src/claij && ./bin/mcp-language-server.sh"],
     "transport" "stdio"}
    }})

;; a map of id: [input-channel output-channel stop]

(def mcp-initialise-request
  {:jsonrpc "2.0"
   :id 1
   :method "initialize"
   :params
   {:protocolVersion "2025-06-18"
    :capabilities
    {:elicitation {}}
    :clientInfo
    {:name "claij"
     :version "1.0-SNAPSHOT"}}})

;; TODO: think about what we should do with notifications... maybe
;; store them until someone shows an interest in that service.... or
;; just throw them all away... Otherwise we would have to pile them
;; all into the context which would be over the top...

(defn notification? [{m "method"}]
  (and m (starts-with? m "notifications/")))

;; these should be initialised LAZILY !

(defn start-mcp [id config]
  (let [n 100
        ic (chan n (map write-str))
        oc (chan n (comp (map read-str)
                         ;; we'll start by discarding notifications - later on these should be collapsed into the initial states...
                         (filter (complement notification?))))
        stop (start-mcp-bridge config ic oc)
        _ (>!! ic mcp-initialise-request)
        r (<!! oc)]
    [r ic oc stop]))

(def mcp-services
  (atom
   nil ;;(map-values start-mcp (mcp-config "mcpServers"))
   ))

(def-fsm mcp-fsm
  {"schema" mcp-schema
   "id" "mcp"

   "description" "Coordinate M[odel] C[ontext] P[rotocol] interactions."
   
   "prompts" ["You are involved in an interaction with an M[odel] C[ontext] P[rotocol] service"]

   "states"
   [;; entry point
    ;;{"id" "start"}

    ;; calls llm with text from start and list of extant mcp services asking fr an mcp request
    {"id" "info"
     "prompts" ["This is a mp of [id : initialisation-response] of the available mcp services:"
                (write-str (map-values (fn [_k [ir]] ir) @mcp-services)) ;; TODO - can't put this here...
                "If you would like to make an MCP request please build one conformnt to your output schema and return it"]
     "action" "llm"}

    ;; receives an mcp request, dispatches onto service, returns an mcp response
    {"id" "mcp"
     "action" "mcp"
     "prompts" ["Process an MCP non-Initialize request."]}

    ;; exit point
    {"id" "end"}]

   "xitions"
   [{"id" ["start" "info"]
     "schema" {"description" "use this to learn about the mcp services available."
               "type" "object"
               "properties"
               {"id" {"const" ["start" "info"]}
                "document" {"type" "string"}}
               "additionalProperties" false
               "required" ["id" "document"]}}

    {"id" ["info" "mcp"]
     "schema" {"description" "use this to make a request to an MCP service."
               "type" "object"
               "properties"
               {"id"
                {"const" ["info" "mcp"]}
                "service"
                {"description" "The id of the MCP sevice that you want to talk to."
                 "type" "string"}
                "request"
                {"description" "The MCP request that you want to send"
                 "type" {"$ref" "#/definitions/JSONRPCRequest"}}}
               "additionalProperties" false
               "required" ["id" "service" "request"]}}

    {"id" ["mcp" "info"]
     "schema" {"description" "returns the response from an MCP service"
               "type" "object"
               "properties"
               {"id"
                {"const" ["info" "mcp"]}
                "service"
                {"description" "The id of the MCP sevice that sent the response."
                 "type" "string"}
                "response"
                {"description" "The MCP service's response"
                 "type"
                 {"$ref" "#/definitions/JSONRPCResponse"}}}
               "additionalProperties" false
               "required" ["id" "service" "response"]}}

    {"id" ["info" "end"]
     "schema" {"description" "Completes your interaction with the MCP service"
               "type" "object"
               "properties"
               {"id"
                {"const" ["info" "mcp"]}
                "document"
                {"description" "Whatever you like"
                 "type" true}}
               "additionalProperties" false
               "required" ["id" "document"]}}

    ;; TODO: looks like we might need to plumb start and end types of sub-fsms into super-fsms and not assume that they are just (type string}
    ]})

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
   {:id->action mcp-actions}


  )

  ;; double check that the fsm schema is only included once in our state - on entry to the fsm

  ;; hmmm... how will LLM know which schema we are $ref-ing if we keep switcing schemas - we may have to qualify refs... (i.e. use external ones)
  
)
