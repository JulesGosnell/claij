(ns claij.fsm.mcp-fsm
  "MCP Protocol FSM - Models MCP lifecycle as explicit states.
  
  This FSM orchestrates the complete MCP protocol flow:
  - Service initialization
  - Cache population  
  - Tool/resource operations"
  (:require
   [clojure.tools.logging :as log]
   [clojure.string :refer [starts-with?]]
   [clojure.data.json :refer [write-str read-str]]
   [clojure.core.async :refer [chan alt!! timeout <!! >!!]]
   [malli.registry :as mr]
   [claij.malli :refer [def-fsm base-registry]]
   [claij.action :refer [def-action]]
   [claij.fsm :refer [llm-action]]
   [claij.mcp.bridge :as bridge]
   [claij.mcp.client :as client]
   [claij.mcp.protocol :refer [initialise-request
                               initialised-notification
                               list-changed?]]
   [claij.mcp.cache :refer [initialize-mcp-cache
                            invalidate-mcp-cache-item
                            refresh-mcp-cache-item]]
   [claij.mcp.schema :refer [mcp-request-xition-schema-fn
                             mcp-response-xition-schema-fn
                             mcp-schemas]]))

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

(defn drain-notifications
  "Drain and discard notifications from output channel.
   With correlated bridge, notifications go to :output-chan while
   responses with IDs go to promises. This drains any pending notifications."
  [bridge]
  (loop []
    (when-some [msg (take! (:output-chan bridge) 100 nil)]
      (log/debug "drain-notifications: discarding" (pr-str msg))
      (recur))))

(defn send-and-wait
  "Send a single request and wait for response.
   Wrapper around bridge/send-request for init/cache flow."
  [bridge request timeout-ms]
  (let [p (bridge/send-request bridge request)]
    (bridge/await-response p (get request "id") timeout-ms)))

;;==============================================================================
;; State Management
;;==============================================================================

;; NOTE: MCP state is now stored in FSM context as :mcp/bridge and :mcp/request-id
;; This provides proper isolation between FSM instances and eliminates test interference

;;==============================================================================
;; MCP FSM Actions
;;==============================================================================

(def-action start-action
  "Starts the MCP bridge and sends initialize request."
  [:map]
  [_config _fsm _ix _state]
  (fn [context {d "document" :as event} _trail handler]
    (log/info "start-action:" (pr-str d))
    (let [config {"command" "bash"
                  "args" ["-c" "cd /home/jules/src/claij && ./bin/mcp-clojure-tools.sh"]
                  "transport" "stdio"}
          ;; Create correlated bridge for request/response correlation
          mcp-bridge (bridge/create-correlated-bridge config)
          ;; Build composite registry: base + FSM schemas + MCP schemas
          existing-registry (get context :malli/registry)
          mcp-registry (mr/composite-registry
                        (or existing-registry base-registry)
                        mcp-schemas)
          ;; Store MCP bridge in context
          ;; Register schema functions for dynamic schema resolution
          ;; Store document in context so it's available throughout the FSM
          updated-context (assoc context
                                 :mcp/bridge mcp-bridge
                                 :mcp/request-id 0
                                 :mcp/document d
                                 :malli/registry mcp-registry
                                 :id->schema {"mcp-request-xition" mcp-request-xition-schema-fn
                                              "mcp-response-xition" mcp-response-xition-schema-fn})
          ;; Send initialize request and wait for response
          init-request (assoc initialise-request "id" 0)
          response (send-and-wait mcp-bridge init-request 5000)]
      ;; Drain any notifications that arrived during init
      (drain-notifications mcp-bridge)
      (handler
       updated-context
       (wrap ["starting" "initing"] d response)))))

(def-action shed-action
  "Sheds unwanted list_changed messages during initialization.
   Note: With correlated bridge, this is largely handled automatically.
   This action is retained for backward compatibility."
  [:map]
  [_config _fsm _ix _state]
  (fn [context {{im "method" :as message} "message" document "document" :as event} _trail handler]
    (log/info "shed-action:" (pr-str message))
    (let [bridge (:mcp/bridge context)
          response (send-and-wait bridge message 5000)]
      ;; Drain any notifications
      (drain-notifications bridge)
      (handler context (wrap ["shedding" "initing"] document response)))))

(def-action init-action
  "Extracts capabilities from initialization response and sets up cache."
  [:map]
  [_config _fsm _ix _state]
  (fn [context {{im "method" :as message} "message" document "document" :as event} _trail handler]
    (log/info "init-action:" (pr-str message))
    ;; Extract capabilities from initialization response and set up cache
    (let [capabilities (get-in message ["result" "capabilities"])
          updated-context (if capabilities
                            (update context "state" initialize-mcp-cache capabilities)
                            context)]
      (handler updated-context (wrap ["initing" "servicing"] document initialised-notification)))))

(def-action service-action
  "Routes messages to MCP bridge and handles responses.
   
   Supports batched tool calls from LLM:
   - Single request: {\"jsonrpc\" \"2.0\" \"id\" 1 \"method\" \"tools/call\" ...}
   - Batch request: [{\"jsonrpc\" ... } {\"jsonrpc\" ...}]
   
   Responses preserve the same format:
   - Single: single response map
   - Batch: vector of response maps in request order"
  [:map]
  [_config _fsm ix _state]
  (fn [context {m "message" document "document" :as event} _trail handler]
    (let [{id "id"} ix
          [from _to] id
          bridge (:mcp/bridge context)]
      (log/info "service-action: from" from "message:" (pr-str m))

      (cond
        ;; From LLM - use client API which supports batching
        (= from "llm")
        (let [;; Normalize to batch format
              requests (if (vector? m) m [m])
              ;; Execute batch
              responses (client/call-batch bridge requests {:timeout-ms 30000})
              ;; If single request, unwrap the response
              result (if (vector? m) responses (first responses))]
          ;; Check for notifications while waiting
          (drain-notifications bridge)
          (handler
           context
           (cond-> {"id" ["servicing" "llm"] "message" result}
             document (assoc "document" document))))

        ;; From cache - single request
        (= from "caching")
        (let [response (send-and-wait bridge m 5000)]
          ;; Check for notifications
          (let [notification (take! (:output-chan bridge) 100 nil)]
            (if notification
              ;; Got a notification - route to caching
              (do
                (log/info "service-action: notification during cache request" (pr-str notification))
                (handler context (wrap ["servicing" "caching"] document notification)))
              ;; Normal response
              (handler context {"id" ["servicing" "caching"] "message" response}))))

        ;; From initing - send initialized notification
        (= from "initing")
        (let [response (send-and-wait bridge m 5000)]
          (drain-notifications bridge)
          (handler context {"id" ["servicing" "caching"] "message" response}))

        ;; Other - timeout/go to llm
        :else
        (do
          (log/warn "service-action: unexpected source" from)
          (handler context
                   (cond-> {"id" ["servicing" "llm"]}
                     document (assoc "document" document))))))))

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

(def-action cache-action
  "Manages cache population by inspecting state and requesting missing data.
   Routes based on incoming message type."
  [:map]
  [_config _fsm ix _state]
  (fn [context {m "message" :as event} _trail handler]
    (let [{id "id"} ix
          {method "method" result "result"} m]
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
          (check-cache-and-continue context handler))))))

(def-action mcp-end-action
  "Final action that signals FSM completion via promise delivery."
  [:map]
  [_config _fsm _ix _state]
  (fn [context _event trail _handler]
    (log/info "FSM complete - reached end state")

    ;; NOTE: We DON'T stop MCP subprocess here because it blocks
    ;; Let the FSM's stop function or test cleanup handle it

    ;; Deliver completion: [context trail]
    (when-let [p (:fsm/completion-promise context)]
      (deliver p [context trail]))
    nil))

(def mcp-actions
  "MCP action implementations. Stores vars to preserve metadata for action? checks."
  {"start" #'start-action
   "shed" #'shed-action
   "init" #'init-action
   "service" #'service-action
   "cache" #'cache-action
   "llm" #'llm-action
   "end" #'mcp-end-action})

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

  {;; No top-level schema - MCP uses dynamic schemas generated from cache
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
     [:map {:closed true}
      ["id" [:= ["start" "starting"]]]
      ["document" :string]]}

    {"id" ["starting" "shedding"]
     "description" "Shed unwanted list-changed messages."
     "label" "initialise\nrequest\n(timeout)"
     "omit" true
     "schema"
     [:map {:closed true}
      ["id" [:= ["starting" "shedding"]]]
      ["document" :string]
      ["message" :any]]}

    {"id" ["starting" "initing"]
     "description" "Direct path when initialize response received"
     "label" "initialise\nresponse"
     "omit" true
     "schema"
     [:map {:closed true}
      ["id" [:= ["starting" "initing"]]]
      ["document" :string]
      ["message" :any]]}

    {"id" ["shedding" "initing"]
     "label" "initialise\nresponse"
     "omit" true
     "schema"
     [:map {:closed true}
      ["id" [:= ["shedding" "initing"]]]
      ["document" :string]
      ["message" :any]]}

    {"id" ["initing" "servicing"]
     "label" "initialise\nnotification"
     "omit" true
     "schema"
     [:map {:closed true}
      ["id" [:= ["initing" "servicing"]]]
      ["document" :string]
      ["message" :any]]}

    {"id" ["servicing" "caching"]
     "label" "list_changed,\nlist_response,\nread_response"
     "omit" true
     "schema"
     [:map {:closed true}
      ["id" [:= ["servicing" "caching"]]]
      ["message" :any]]}

    {"id" ["caching" "servicing"]
     "label" "list_request,\nread_request"
     "omit" true
     "schema"
     [:map {:closed true}
      ["id" [:= ["caching" "servicing"]]]
      ["message" :any]]}

    {"id" ["servicing" "llm"]
     "label" "tool\nresponse"
     "description" "Tool response returning to LLM - schema dynamically generated from MCP cache"
     "schema" "mcp-response-xition"}

    {"id" ["caching" "llm"]
     "label" "cache\nready"
     "description" "Cache is ready, LLM can start work"
     "schema"
     [:map {:closed true}
      ["id" [:= ["caching" "llm"]]]
      ["document" :string]]}

    {"id" ["llm" "servicing"]
     "label" "tool\ncall"
     "description" "LLM makes MCP request - schema dynamically generated from MCP cache"
     "schema" "mcp-request-xition"}

    {"id" ["llm" "end"]
     "label" "output"
     "description" "LLM completes work - result must use MCP content format"
     "schema"
     [:map {:closed true}
      ["id" [:= ["llm" "end"]]]
      ["result" [:map
                 ["content" [:vector
                             [:map {:closed true}
                              ["type" [:enum "text" "image" "resource"]]
                              ["text" :string]]]]
                 ["isError" {:optional true} :boolean]]]]}]})
