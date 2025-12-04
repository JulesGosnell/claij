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
   [claij.malli :refer [def-fsm]]
   [claij.mcp.schema :refer [def-json-schema]]
   [claij.fsm :refer [llm-action]]
   [claij.mcp.bridge :refer [start-mcp-bridge]]
   [claij.mcp :refer [initialise-request
                      initialised-notification
                      list-changed?
                      initialize-mcp-cache
                      invalidate-mcp-cache-item
                      refresh-mcp-cache-item
                      mcp-request-xition-schema-fn
                      mcp-response-xition-schema-fn]]))

;;==============================================================================
;; MCP Schema
;;==============================================================================

(def-json-schema
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

(defn start-action [context fsm ix state {d "document" :as event} trail handler]
  (log/info "start-action:" (pr-str d))
  (let [n 100
        config {"command" "bash", "args" ["-c", "cd /home/jules/src/claij && ./bin/mcp-clojure-tools.sh"], "transport" "stdio"}
        ic (chan n (map write-str))
        oc (chan n (map read-str))
        stop (start-mcp-bridge config ic oc)
        ;; Store MCP bridge in context instead of global atom
        ;; Register schema functions for dynamic schema resolution
        ;; Store document in context so it's available throughout the FSM
        updated-context (assoc context
                               :mcp/bridge {:input ic :output oc :stop stop}
                               :mcp/request-id 0
                               :mcp/document d
                               :id->schema {"mcp-request-xition" mcp-request-xition-schema-fn
                                            "mcp-response-xition" mcp-response-xition-schema-fn})
        init-request (assoc initialise-request "id" 0)]
    (handler
     updated-context
     (let [{m "method" :as message} (take! oc 5000 init-request)]
       (cond
         ;; Server sent initialize request, or we timed out (default is init-request)
         (= m "initialize")
         (wrap ["starting" "shedding"] d message)

         ;; Server sent something else (notifications) - use our initialize request
         :else
         (wrap ["starting" "shedding"] d init-request))))))

(defn shed-action [context fsm ix state {{im "method" :as message} "message" document "document" :as event} trail handler]
  (log/info "shed-action:" (pr-str message))
  (let [{{ic :input oc :output} :mcp/bridge} context]
    (>!! ic message)
    (handler context (wrap ["shedding" "initing"] document (hack oc)))))

(defn init-action [context fsm ix state {{im "method" :as message} "message" document "document" :as event} trail handler]
  (log/info "init-action:" (pr-str message))
  ;; Extract capabilities from initialization response and set up cache
  (let [capabilities (get-in message ["result" "capabilities"])
        updated-context (if capabilities
                          (update context "state" initialize-mcp-cache capabilities)
                          context)]
    (handler updated-context (wrap ["initing" "servicing"] document initialised-notification))))

(defn service-action
  "Routes messages to MCP bridge and handles responses.
   Routing depends on where the message came from."
  [context fsm {id "id"} state {m "message" document "document" :as event} trail handler]
  (log/info "service-action: from" id "message:" (pr-str m))
  (let [{{ic :input oc :output} :mcp/bridge} context
        [from _to] id]
    (>!! ic m)
    (let [{method "method" :as oe} (take! oc 2000 {})]
      (handler
       context
       (cond
         ;; Notification received - route to caching
         method
         (wrap ["servicing" "caching"] document oe)

         ;; Response to tool call from llm - route back to llm with result
         (= from "llm")
         (cond-> {"id" ["servicing" "llm"] "message" oe}
           document (assoc "document" document))

         ;; Response to cache request - route back to caching with result
         (= from "caching")
         {"id" ["servicing" "caching"] "message" oe}

         ;; Timeout/empty response during init phase - go to caching to populate
         (= from "initing")
         {"id" ["servicing" "caching"] "message" {}}

         ;; Other timeout - go to llm
         :else
         (cond-> {"id" ["servicing" "llm"]}
           document (assoc "document" document)))))))

(defn check-cache-and-continue
  "Inspect cache for holes. Request missing data until cache is complete.
   
   Holes:
   - nil capability value → request capability/list
   
   Note: Resource bodies are loaded lazily at LLM's request, not eagerly here."
  [context handler]
  (let [cache (get context "state")
        missing-capability (first (filter (fn [[_k v]] (nil? v)) cache))]
    (if missing-capability
      ;; Request capability list
      (let [[capability _nil-value] missing-capability
            request-id (inc (:mcp/request-id context))
            request {"jsonrpc" "2.0"
                     "id" request-id
                     "method" (str capability "/list")}]
        (log/info "check-cache-and-continue: requesting" capability)
        (handler
         (assoc context :mcp/request-id request-id)
         {"id" ["caching" "servicing"] "message" request}))
      ;; Cache is complete - go to LLM with document
      (do
        (log/info "check-cache-and-continue: cache complete, going to llm")
        (handler context {"id" ["caching" "llm"]
                          "document" (:mcp/document context)})))))

(defn cache-action
  "Manages cache population by inspecting state and requesting missing data.
   Routes based on incoming message type."
  [context fsm {id "id"} state {m "message" :as event} trail handler]
  (let [{method "method" result "result"} m]
    (cond
      ;; Incoming list response - refresh cache with data
      result
      (do
        (log/info "cache-action: received list response, refreshing cache")
        (let [updated-context (update context "state" refresh-mcp-cache-item result)]
          (check-cache-and-continue updated-context handler)))

      ;; list_changed notification - invalidate and request refresh
      (and method (list-changed? method))
      (let [capability (list-changed? method)
            updated-context (update context "state" invalidate-mcp-cache-item capability)
            request-id (inc (:mcp/request-id context))
            request {"jsonrpc" "2.0"
                     "id" request-id
                     "method" (str capability "/list")}]
        (log/info "cache-action: list_changed for" capability ", requesting refresh")
        (handler
         (assoc updated-context :mcp/request-id request-id)
         {"id" ["caching" "servicing"] "message" request}))

      ;; Initial entry or retry - inspect cache and request missing data
      :else
      (do
        (log/info "cache-action: checking cache for missing data")
        (check-cache-and-continue context handler)))))

(defn end-action
  "Final action that signals FSM completion via promise delivery."
  [context _fsm _ix _state event trail _handler]
  (log/info "FSM complete - reached end state")

  ;; NOTE: We DON'T stop MCP subprocess here because it blocks
  ;; Let the FSM's stop function or test cleanup handle it

  ;; Deliver completion: [context trail]
  (when-let [p (:fsm/completion-promise context)]
    (deliver p [context trail]))
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

;; NOTE: If you modify this FSM definition, regenerate the visualization:
;;   1. At the REPL: (require '[claij.graph :refer [fsm->dot]]
;;                             '[claij.fsm.mcp-fsm :refer [mcp-fsm]])
;;                   (spit "doc/mcp-fsm.dot" (fsm->dot mcp-fsm))
;;   2. Generate SVG: dot -Tsvg doc/mcp-fsm.dot -o doc/mcp-fsm.svg
;;   This keeps the README visualization in sync with the code.

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
     "omit" true
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
     "omit" true
     "schema"
     {"type" "object"
      "properties"
      {"id" {"const" ["starting" "shedding"]}
       "document" {"type" "string"}
       "message" true}
      "additionalProperties" false
      "required" ["id" "document" "message"]}}

    {"id" ["starting" "initing"]
     "description" "Direct path when initialize response received"
     "label" "initialise\nresponse"
     "omit" true
     "schema"
     {"type" "object"
      "properties"
      {"id" {"const" ["starting" "initing"]}
       "document" {"type" "string"}
       "message" true}
      "additionalProperties" false
      "required" ["id" "document" "message"]}}

    {"id" ["shedding" "initing"]
     "label" "initialise\nresponse"
     "omit" true
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
     "omit" true
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
     "omit" true
     "schema"
     {"type" "object"
      "properties"
      {"id" {"const" ["servicing" "caching"]}
       "message" true}
      "additionalProperties" false
      "required" ["id" "message"]}}

    {"id" ["caching" "servicing"]
     "label" "list_request,\nread_request"
     "omit" true
     "schema"
     {"type" "object"
      "properties"
      {"id" {"const" ["caching" "servicing"]}
       "message" true}
      "additionalProperties" false
      "required" ["id" "message"]}}

    {"id" ["servicing" "llm"]
     "label" "tool\nresponse"
     "description" "Tool response returning to LLM - schema dynamically generated from MCP cache"
     "schema" "mcp-response-xition"}

    {"id" ["caching" "llm"]
     "label" "cache\nready"
     "description" "Cache is ready, LLM can start work"
     "schema"
     {"type" "object"
      "properties"
      {"id" {"const" ["caching" "llm"]}
       "document" {"type" "string"}}
      "additionalProperties" false
      "required" ["id" "document"]}}

    {"id" ["llm" "servicing"]
     "label" "tool\ncall"
     "description" "LLM makes MCP request - schema dynamically generated from MCP cache"
     "schema" "mcp-request-xition"}

    {"id" ["llm" "end"]
     "label" "output"
     "description" "LLM completes work - result must use MCP content format"
     "schema"
     {"type" "object"
      "properties"
      {"id" {"const" ["llm" "end"]}
       "result" {"type" "object"
                 "properties"
                 {"content" {"type" "array"
                             "items" {"type" "object"
                                      "properties"
                                      {"type" {"type" "string"
                                               "enum" ["text" "image" "resource"]}
                                       "text" {"type" "string"}}
                                      "required" ["type" "text"]
                                      "additionalProperties" false}}
                  "isError" {"type" "boolean"}}
                 "required" ["content"]
                 "additionalProperties" false}}
      "additionalProperties" false
      "required" ["id" "result"]}}]})
