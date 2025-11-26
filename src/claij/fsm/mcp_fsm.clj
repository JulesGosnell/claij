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
                      refresh-mcp-cache-item
                      mcp-request-xition-schema-fn
                      mcp-response-xition-schema-fn]]))

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
        ;; Register schema functions for dynamic schema resolution
        updated-context (assoc context
                               :mcp/bridge {:input ic :output oc :stop stop}
                               :mcp/request-id 0
                               :id->schema {"mcp-request-xition" mcp-request-xition-schema-fn
                                            "mcp-response-xition" mcp-response-xition-schema-fn})]
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

(defn service-action
  "Routes messages to MCP bridge and handles responses.
   Routing depends on where the message came from."
  [context fsm {id "id"} state [{[is {m "message" document "document"} os] "content"} :as trail] handler]
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
   - resource without text → request resources/read"
  [context handler]
  (let [cache (get context "state")
        ;; Check 1: Any nil capabilities?
        missing-capability (first (filter (fn [[_k v]] (nil? v)) cache))
        ;; Check 2: Any resources without text?
        resources (get cache "resources")
        resource-without-text (when (and (not missing-capability)
                                         (sequential? resources))
                                (first (filter #(not (contains? % "text")) resources)))]

    (cond
      ;; Hole 1: Request capability list
      missing-capability
      (let [[capability _nil-value] missing-capability
            request-id (inc (:mcp/request-id context))
            request {"jsonrpc" "2.0"
                     "id" request-id
                     "method" (str capability "/list")}]
        (log/info "check-cache-and-continue: requesting" capability)
        (handler
         (assoc context :mcp/request-id request-id)
         {"id" ["caching" "servicing"] "message" request}))

      ;; Hole 2: Request resource read
      resource-without-text
      (let [uri (get resource-without-text "uri")
            request-id (inc (:mcp/request-id context))
            request {"jsonrpc" "2.0"
                     "id" request-id
                     "method" "resources/read"
                     "params" {"uri" uri}}]
        (log/info "check-cache-and-continue: requesting resource" uri)
        (handler
         (assoc context :mcp/request-id request-id)
         {"id" ["caching" "servicing"] "message" request}))

      ;; No holes - cache is complete!
      :else
      (do
        (log/info "check-cache-and-continue: cache complete, going to llm")
        (handler context {"id" ["caching" "llm"]})))))

(defn cache-action
  "Manages cache population by inspecting state and requesting missing data.
   Routes based on incoming message type."
  [context fsm {id "id"} state [{[is {m "message"} os] "content"} :as trail] handler]
  (log/info "cache-action: from" id "message:" (pr-str m))
  (let [{method "method" result "result"} m
        contents (get result "contents")]

    (cond
      ;; Resource contents - merge into resources
      contents
      (do
        (log/info "cache-action: received resource contents, merging")
        (let [updated-context (update-in context ["state" "resources"] merge-resources contents)]
          (check-cache-and-continue updated-context handler)))

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

(defn llm-action
  "LLM state - can make tool calls or signal completion.
   Routes based on incoming transition and message content."
  [context fsm {id "id"} state [{[is {m "message" d "document"} os] "content"} :as trail] handler]
  (log/info "llm-action: from" id "message:" (pr-str m))
  ;; Check if this is a tool response (has result) or initial entry
  (if (get m "result")
    ;; Tool response returned - we're done
    (let [result (get m "result")]
      (log/info "llm-action: tool result received" (pr-str result))
      (handler context {"id" ["llm" "end"] "result" result}))
    ;; Initial entry (from servicing timeout or caching) - make a test tool call
    (let [request-id (inc (:mcp/request-id context))
          tool-call {"jsonrpc" "2.0"
                     "id" request-id
                     "method" "tools/call"
                     "params" {"name" "clojure_eval"
                               "arguments" {"code" "(+ 1 1)"}}}]
      (log/info "llm-action: making tool call" (pr-str tool-call))
      (handler
       (assoc context :mcp/request-id request-id)
       (cond-> {"id" ["llm" "servicing"] "message" tool-call}
         d (assoc "document" d))))))

(defn end-action
  "Final action that signals FSM completion via promise delivery."
  [context _fsm _ix _state trail _handler]
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
     "label" "tool\nresponse"
     "description" "Tool response returning to LLM - schema dynamically generated from MCP cache"
     "schema" "mcp-response-xition"}

    {"id" ["caching" "llm"]
     "label" "cache\nready"
     "description" "Cache is ready, LLM can start work"
     "schema"
     {"type" "object"
      "properties"
      {"id" {"const" ["caching" "llm"]}}
      "additionalProperties" false
      "required" ["id"]}}

    {"id" ["llm" "servicing"]
     "label" "tool\ncall"
     "description" "LLM makes MCP request - schema dynamically generated from MCP cache"
     "schema" "mcp-request-xition"}

    {"id" ["llm" "end"]
     "label" "output"
     "description" "LLM completes work"
     "schema" true}]})
