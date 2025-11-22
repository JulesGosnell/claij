(ns claij.fsm.mcp-fsm
  "MCP Protocol FSM - Models MCP lifecycle as explicit states.
  
  This FSM orchestrates the complete MCP protocol flow:
  - Service initialization
  - Cache population  
  - Tool/resource operations"
  (:require
   [clojure.tools.logging :as log]
   [clojure.java.io :refer [resource]]
   [clojure.string :refer [starts-with?]]
   [clojure.data.json :refer [write-str read-str]]
   [clojure.core.async :refer [chan alt!! timeout <!! >!!]]
   [claij.util :refer [def-m2]]
   [claij.fsm :refer [def-fsm]]
   [claij.mcp.bridge :refer [start-mcp-bridge]]
   [claij.mcp :refer [initialise-request
                      initialised-notification
                      list-changed?
                      merge-resources
                      initialize-mcp-cache
                      invalidate-mcp-cache-item
                      refresh-mcp-cache-item]]))

;;==============================================================================
;; MCP Schema
;;==============================================================================

(def-m2
  mcp-schema
  (assoc
   (read-str (slurp (resource "mcp/schema.json")))
   "$$id"
   (str claij.fsm/schema-base-uri "/" "mcp.json")))

;;==============================================================================
;; Helper Functions
;;==============================================================================

(defn wrap
  ([id document message]
   {"id" id "document" document "message" message})
  ([id message]
   {"id" id "message" message}))

(defn unwrap [{m "message"}]
  m)

(defn take! [chan ms default]
  (alt!! chan ([v] v) (timeout ms) ([_] default)))

(defn hack [oc]
  (let [{method "method" :as message} (<!! oc)]
    (if (and method (list-changed? method))
      (do
        (log/warn "shedding:" (pr-str message))
        (hack oc))
      message)))

;;==============================================================================
;; State Management
;;==============================================================================

;; NOTE: MCP state is now stored in FSM context as :mcp/bridge and :mcp/request-id
;; This provides proper isolation between FSM instances and eliminates test interference

;;==============================================================================
;; MCP FSM Actions
;;==============================================================================

(defn start-action [context fsm ix state [{[is {d "document" :as event} os] "content"} :as trail] handler]
  (log/info "start-action:" (pr-str d))
  (let [n 100
        config {"command" "bash", "args" ["-c", "cd /home/jules/src/claij && ./bin/mcp-clojure-tools.sh"], "transport" "stdio"}
        ic (chan n (map write-str))
        oc (chan n (map read-str))
        stop (start-mcp-bridge config ic oc)
        ;; Store MCP bridge in context instead of global atom
        updated-context (assoc context 
                               :mcp/bridge {:input ic :output oc :stop stop}
                               :mcp/request-id 0)]
    (handler
     updated-context
     (let [{m "method" :as message} (take! oc 5000 (assoc initialise-request "id" 0))]
       (cond
         (= m "initialize")
         (wrap ["starting" "shedding"] d message))))))

(defn shed-action [context fsm ix state [{[is {{im "method" :as message} "message" document "document" :as event} os] "content"} :as trail] handler]
  (log/info "shed-action:" (pr-str message))
  (let [{{ic :input oc :output} :mcp/bridge} context]
    (>!! ic message)
    (handler context (wrap ["shedding" "initing"] document (hack oc)))))

(defn init-action [context fsm ix state [{[is {{im "method" :as message} "message" document "document" :as event} os] "content"} :as trail] handler]
  (log/info "init-action:" (pr-str message))
  ;; Extract capabilities from initialization response and set up cache
  (let [capabilities (get-in message ["result" "capabilities"])
        updated-context (if capabilities
                          (update context "state" initialize-mcp-cache capabilities)
                          context)]
    (handler updated-context (wrap ["initing" "servicing"] document initialised-notification))))

(defn service-action [context fsm ix state [{[is {m "message" document "document"} os] "content"} :as trail] handler]
  (log/info "service-action:" (pr-str m))
  (let [{{ic :input oc :output} :mcp/bridge} context]
    (>!! ic m)
    ;; diagram says we should consider a timeout here...
    (let [{method "method" :as oe} (take! oc 2000 {})]
      (handler
       context
       (cond
         method
         (wrap ["servicing" "caching"] document oe)
         :else
         {"id" ["servicing" "llm"] "document" document})))))

(defn cache-action [context fsm ix state [{[is {m "message"} os] "content"} :as trail] handler]
  (log/info "cache-action:" (pr-str m))
  (let [{method "method" {contents "contents" :as r} "result"} m]
    (cond
      ;; Handle list-changed notification: invalidate and request refresh
      (and method (list-changed? method))
      (let [capability (list-changed? method)
            request-id (inc (:mcp/request-id context))
            updated-context (-> context
                                (update "state" invalidate-mcp-cache-item capability)
                                (assoc :mcp/request-id request-id))
            refresh-request {"jsonrpc" "2.0"
                             "id" request-id
                             "method" (str capability "/" "list")}]
        (handler updated-context (wrap ["caching" "servicing"] refresh-request)))

      ;; Handle resource contents: merge into resources
      contents
      (let [updated-context (update-in context ["state" "resources"] merge-resources contents)]
        ;; After merging contents, continue servicing
        (handler updated-context (wrap ["caching" "servicing"] {})))

      ;; Handle list response: refresh cache item
      r
      (let [updated-context (update context "state" refresh-mcp-cache-item r)]
        ;; After refreshing cache, check if we need more data or can proceed to LLM
        ;; For now, continue servicing - we can refine this logic later
        (handler updated-context (wrap ["caching" "servicing"] {})))

      :else
      ;; Pass through - shouldn't normally happen
      (handler context (wrap ["caching" "llm"] {})))))

(defn llm-action [context fsm ix state [{[is document os] "content"} :as trail] handler]
  (log/info "llm-action:" (pr-str document))
  (handler context (wrap ["llm" "end"] nil)))

(defn end-action
  "Final action that signals FSM completion via promise delivery."
  [context _fsm _ix _state [{[_is event _os] "content"} :as _trail] _handler]
  (log/info "FSM complete - reached end state")
  
  ;; NOTE: We DON'T stop MCP subprocess here because it blocks
  ;; Let the FSM's stop function or test cleanup handle it
  
  ;; Deliver completion event
  (when-let [p (:fsm/completion-promise context)]
    (deliver p event))
  nil)

(def mcp-actions
  {"start" start-action
   "shed" shed-action
   "init" init-action
   "service" service-action
   "cache" cache-action
   "llm" llm-action
   "end" end-action})

;;==============================================================================
;; MCP FSM Definition
;;==============================================================================

(def-fsm
  mcp-fsm

  {"schema" mcp-schema
   "id" "mcp"
   "description" "Orchestrates MCP protocol interactions"

   "prompts"
   ["You are coordinating interactions with an MCP (Model Context Protocol) service"]

   "states"
   [{"id" "starting" "action" "start"}
    {"id" "shedding" "action" "shed"}
    {"id" "initing" "action" "init"}
    {"id" "servicing" "action" "service"}
    {"id" "caching" "action" "cache"}
    {"id" "llm" "action" "llm"}
    {"id" "end" "action" "end"}]

   "xitions"
   [{"id" ["start" "starting"]
     "description" "starts the MCP service. Returns an initialise-request."
     "label" "document"
     "schema"
     {"type" "object"
      "properties"
      {"id" {"const" ["start" "starting"]}
       "document" {"type" "string"}}
      "additionalProperties" false
      "required" ["id" "document"]}}

    {"id" ["starting" "shedding"]
     "description" "Shed unwanted list-changed messages."
     "label" "initialise\nrequest\n(timeout)"
     "schema"
     {"type" "object"
      "properties"
      {"id" {"const" ["starting" "shedding"]}
       "document" {"type" "string"}
       "message" true}
      "additionalProperties" false
      "required" ["id" "document" "message"]}}

    {"id" ["shedding" "initing"]
     "label" "initialise\nresponse"
     "schema"
     {"type" "object"
      "properties"
      {"id" {"const" ["shedding" "initing"]}
       "document" {"type" "string"}
       "message" true}
      "additionalProperties" false
      "required" ["id" "document" "message"]}}

    {"id" ["initing" "servicing"]
     "label" "initialise\nnotification"
     "schema"
     {"type" "object"
      "properties"
      {"id" {"const" ["initing" "servicing"]}
       "document" {"type" "string"}
       "message" true}
      "additionalProperties" false
      "required" ["id" "document" "message"]}}

    {"id" ["servicing" "caching"]
     "label" "list_changed,\nlist_response,\nread_response"
     "schema"
     {"type" "object"
      "properties"
      {"id" {"const" ["servicing" "caching"]}
       "message" true}
      "additionalProperties" false
      "required" ["id" "message"]}}

    {"id" ["caching" "servicing"]
     "label" "list_request,\nread_request"
     "schema"
     {"type" "object"
      "properties"
      {"id" {"const" ["caching" "servicing"]}
       "message" true}
      "additionalProperties" false
      "required" ["id" "message"]}}

    {"id" ["servicing" "llm"]
     "label" "tools\nreponse"
     "schema"
     {"type" "object"
      "properties"
      {"id" {"const" ["servicing" "llm"]}
       "document" {"type" "string"}}
      "additionalProperties" false
      "required" ["id" "document"]}}

    {"id" ["llm" "end"]
     "label" "ouput"
     "schema" true}]})
