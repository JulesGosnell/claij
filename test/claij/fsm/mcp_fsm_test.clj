(ns claij.fsm.mcp-fsm-test
  "MCP Protocol FSM - Models MCP lifecycle as explicit states.
  
  This FSM orchestrates the complete MCP protocol flow:
  - Service initialization
  - Cache population
  - Tool/resource operations
  
  Production code at top, tests at bottom."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.tools.logging :as log]
   [clojure.data.json :refer [read-str write-str]]
   [clojure.core.async :refer[chan alt!! timeout >!! <!!]]
   [claij.util :refer [def-m2]]
   [claij.fsm :refer [def-fsm start-fsm]]
   [claij.mcp.bridge :refer [start-mcp-bridge]]
   [claij.mcp :refer [mcp-schema initialise-request initialised-notification handle list-changed?]]))

;;==============================================================================
;; PRODUCTION CODE
;;==============================================================================

;;------------------------------------------------------------------------------
;; MCP Schema
;;------------------------------------------------------------------------------

;;------------------------------------------------------------------------------
;; MCP Actions
;;------------------------------------------------------------------------------

(defn wrap
  ([id document message]
   {"id" id "document" document "message" message})
  ([id message]
   {"id" id "message" message}))

(defn unwrap [{m "message"}]
  m)

(defn take! [chan ms default]
  (alt!! chan ([v] v) (timeout ms) ([_] default)))

;;------------------------------------------------------------------------------
;; MCP Actions
;;------------------------------------------------------------------------------

(def mcp-state (atom nil))

(def mcp-request-id (atom -1))

;; put mcp service in context
;; pass through original request

(defn start-action [context fsm ix state [{[is {d "document" :as event} os] "content"} :as trail] handler]
  (log/info "start-action:" (pr-str d))
  (let [n 100
        config {"command" "bash", "args" ["-c", "cd /home/jules/src/claij && ./bin/mcp-clojure-tools.sh"], "transport" "stdio"}
        ic (chan n (map write-str))
        oc (chan n (map read-str))
        ;; TODO: need to link the stopping of this bridge to the stopping of the fsm...
        stop (start-mcp-bridge config ic oc)]
    (swap! mcp-state assoc
           :input ic
           :output oc
           :stop stop)
    (handler
     (let [{m "method" :as message} (take! oc 5000 (assoc initialise-request "id" (swap! mcp-request-id inc)))]
       (cond
         (= m "initialize")
         (wrap ["starting" "shedding"] d message)

         ;; (list-changed? m)
         ;; (wrap ["starting" "starting"] message)
         )))))

;; these messages should go out on the fsm in a look but reflexive xitions are not working...yet...
(defn hack [oc]
  (let [{method "method" :as message} (<!! oc)]
    (if (and method (list-changed? method))
      (do
        (log/warn "shedding:" (pr-str message))
        (hack oc))
      message)))

(defn shed-action [context fsm ix state [{[is {{im "method" :as message} "message" document "document" :as event} os]  "content"} :as trail] handler]
  (log/info "shed-action:" (pr-str message))
  (let [{ic :input oc :output} @mcp-state]
    (>!! ic message)
    (handler (wrap ["shedding" "initing"] document (hack oc)))))

(defn init-action [context fsm ix state [{[is {{im "method" :as message} "message" document "document" :as event} os]  "content"} :as trail] handler]
  ;; TODO: put the initialise-response we receive into the cache
  (log/info "init-action:" (pr-str message))
  (handler (wrap ["initing" "servicing"] document initialised-notification)))

(defn service-action [context fsm ix state [{[is {m "message" document "document"} os]  "content"} :as trail] handler]
  (log/info "service-action:" (pr-str m))
  (let [{ic :input oc :output} @mcp-state]
    (>!! ic m)
    ;; diagram says we should consider a timeout here...
    (let [{method "method" :as oe} (take! oc 2000 {})]
      (handler
       (cond
         method
         (wrap ["servicing" "caching"] document oe)
         :else
         {"id" ["servicing" "llm"] "document" document})))))

(defn cache-action [context fsm ix state [{[is {m "message"} os]  "content"} :as trail] handler]
  (log/info "cache-action:" (pr-str m))

  (let [{m "method" {contents "contents" cs "capabilities" :as r} "result" :as message} m
        [oc oe] (cond
                  ;; ;; initialise cache
                  ;; cs [(update context "state" (partial reduce-kv (fn [acc k {lc "listChanged" s "subscribe"}] (if (or lc s) (assoc acc k nil) acc))) cs) nil]
                  ;; ;; refresh resource contents
                  ;; contents [(update-in context ["state" "resources"] merge-resources contents) nil]

                  ;; invalidate cache item
                  m  (let [capability (list-changed? m)]
                       [(assoc-in context ["state" capability] nil)
                        ;; ask to reload this capability...
                        (wrap ["caching" "servicing"] {"jsonrpc" "2.0" "id" 2 "method" (str capability "/" "list")})])

                  ;; ;; refresh cache item
                  ;; r  [(update context "state" merge r) nil]

                  ;; :else
                  ;; ;; pass through
                  ;; [context e]

                  ;;TODO: subscriptions
                  )]
    (handler oe))

  ;; (cond
  ;;     ;; each different message must do two things
  ;;     ;; - make a changhe to the cache
  ;;     ;; - either, request a further change to the cache
  ;;     ;; - or - declare the cache fresh and step up to the llm
  ;;     )
  )

(defn llm-action  [context fsm ix state [{[is document os]  "content"} :as trail] handler]
  (log/info "llm-action:" (pr-str document))
  (handler (wrap ["llm" "end"] nil)))


(defn end-action [context & args]
  (log/info "end-action:" args))

(def mcp-actions
  {"start" start-action
   "shed" shed-action
   "init" init-action
   "service" service-action
   "cache" cache-action
   "llm" llm-action
   "end"   end-action})  

;;------------------------------------------------------------------------------
;; MCP FSM Definition
;;------------------------------------------------------------------------------

(declare mcp-fsm)

(def-fsm
  mcp-fsm

  {"schema" mcp-schema
   "id" "mcp"
   "description" "Orchestrates MCP protocol interactions"

   "prompts"
   ["You are coordinating interactions with an MCP (Model Context Protocol) service"]

   "states"
   [;;{"id" "start"}
    {"id" "starting" "action" "start"}
    {"id" "shedding" "action" "shed"}
    {"id" "initing" "action" "init"}
    {"id" "servicing" "action" "service"}
    {"id" "caching" "action" "cache"}
    {"id" "llm" "action" "llm"}
    {"id" "end" "action" "end"}]

   "xitions"
   [{"id" ["start" "starting"]
     "description" "starts the MCP service. Returns an initialise-request."
     "schema"
     {"type" "object"
      "properties"
      {"id" {"const" ["start" "starting"]}
       "document" {"type" "string"}}
      "additionalProperties" false
      "required" ["id" "document"]}}

    {"id" ["starting" "shedding"]
     "description" "Shed unwanted list-changed messages."
     "schema"
     {"type" "object"
      "properties"
      {"id" {"const" ["starting" "shedding"]}
       "document" {"type" "string"}
       "message" true}
      "additionalProperties" false
      "required" ["id" "document" "message"]}}

    ;; {"id" ["shedding" "shedding"]
    ;;  "description" "Shed unwanted list-changed messages."
    ;;  "schema"
    ;;  {"type" "object"
    ;;   "properties"
    ;;   {"id" {"const" ["shedding" "shedding"]}
    ;;    "message" true}
    ;;   "additionalProperties" false
    ;;   "required" ["id" "message"]}}

    {"id" ["shedding" "initing"]
     "schema"
     {"type" "object"
      "properties"
      {"id" {"const" ["shedding" "initing"]}
       "document" {"type" "string"}
       "message" true}
      "additionalProperties" false
      "required" ["id" "document" "message"]}}

    {"id" ["initing" "servicing"]
     "schema"
     {"type" "object"
      "properties"
      {"id" {"const" ["initing" "servicing"]}
       "document" {"type" "string"}
       "message" true}
      "additionalProperties" false
      "required" ["id" "document" "message"]}}

    {"id" ["servicing" "caching"]
     "schema"
     {"type" "object"
      "properties"
      {"id" {"const" ["servicing" "caching"]}
       "message" true}
      "additionalProperties" false
      "required" ["id" "message"]}}

    {"id" ["caching" "servicing"]
     "schema"
     {"type" "object"
      "properties"
      {"id" {"const" ["caching" "servicing"]}
       "message" true}
      "additionalProperties" false
      "required" ["id" "message"]}}

    {"id" ["servicing" "llm"]
     "schema"
     {"type" "object"
      "properties"
      {"id" {"const" ["servicing" "llm"]}
       "document" {"type" "string"}}
      "additionalProperties" false
      "required" ["id" "document"]}}
    
    {"id" ["llm" "end"]
     "schema" true}]})

;;==============================================================================
;; TESTS
;;==============================================================================

(deftest ^:integration mcp-fsm-test

  (testing "walk the fsm"
    (let [context {:id->action mcp-actions}
          [submit stop] (start-fsm context mcp-fsm)]

      (is (fn? stop))      
      (submit
       {"id" ["start" "starting"]
        "document" "please evaluate (+ 1 1) at the repl"})
      
      (try
        (catch Throwable t
          (log/error "unexpected error" t))
        (finally
          (Thread/sleep 2000)
          ;;(stop)
          )))))

