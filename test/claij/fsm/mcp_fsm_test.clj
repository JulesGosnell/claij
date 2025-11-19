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

(defn wrap [id message]
  {"id" id "message" message})

(defn unwrap [{m "message"}]
  m)

(defn take! [chan ms default]
  (alt!! chan ([v] v) (timeout ms) ([_] default)))

;;------------------------------------------------------------------------------
;; MCP Actions
;;------------------------------------------------------------------------------

(def mcp-state (atom nil))


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
     (let [{m "method" :as message} (take! oc 5000 initialise-request)]
       (cond
         (= m "initialize")
         (wrap ["starting" "servicing"] message)

         ;; (list-changed? m)
         ;; (wrap ["starting" "starting"] message)
         )))))

(defn service-action [context fsm ix state [{[is {m "message"} os]  "content"} :as trail] handler]
  (log/info "service-action:" (pr-str m))
  (let [{ic :input oc :output} @mcp-state]
    (>!! ic m)
    ;; diagram says we should consider a timeout here...
    (let [{{cs "capabilities"} "result" method "method" :as oe} (<!! oc)
          oid (cond cs "initialising" :else "caching")]
      (handler (wrap ["servicing" oid] oe)))))

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

(defn initialise-action [context fsm ix state [{[is {m "message"} os]  "content"} :as trail] handler]
  (log/info "initialise-action:" (pr-str m))
  (handler (wrap ["initialising" "servicing"] initialised-notification)))



(defn end-action [context & args]
  (log/info "end-action:" args))

(def mcp-actions
  {"start" start-action
   "service" service-action
   "cache" cache-action
   "initialise" initialise-action
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
    {"id" "servicing" "action" "service"}
    {"id" "caching" "action" "cache"}
    {"id" "initialising" "action" "initialise"}
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

    {"id" ["starting" "servicing"]
     "schema"
     {"type" "object"
      "properties"
      {"id" {"const" ["starting" "servicing"]}
       "message" true}
      "additionalProperties" false
      "required" ["id" "message"]}}

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

    {"id" ["servicing" "initialising"]
     "schema"
     {"type" "object"
      "properties"
      {"id" {"const" ["servicing" "initialising"]}
       "message" true}
      "additionalProperties" false
      "required" ["id" "message"]}}

    {"id" ["initialising" "servicing"]
     "schema"
     {"type" "object"
      "properties"
      {"id" {"const" ["initialising" "servicing"]}
       "message" true}
      "additionalProperties" false
      "required" ["id" "message"]}}
    
    {"id" ["starting" "end"]
     "schema" true}]})

;;==============================================================================
;; TESTS
;;==============================================================================

(deftest mcp-fsm-test

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

