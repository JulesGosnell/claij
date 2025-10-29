(ns claij.fsm
  (:require
   [clojure.set :refer [intersection]]
   [m3.validate :refer [validate]]
   ))

;; we need an example fsm

(def m2
  {"type" "object"
   "$defs"
   {"done"
    {"type" "string"
     "description" "summarise what you have done"}

    "mcp-tool-invocation"
    {"description" "args list for the mcp invocation"
     "type" "array"
     "items" "string"}

    "mcp-tool-invocations"
    {"type" "array"
     "items" {"$ref" "#/$defs/mcp-tool-invocation"}}

    "mcp-tool-request"
    {"type" "object"
     "properties"
     {"id"
      {"type" "string"}
      "invocations"
      {"$ref" "#/$defs/mcp-tool-invocations"}}}

    "mcp-tool-requests"
    {"type" "array"
     "items" {"$ref" "#/$defs/mcp-tool-requests"}}

    "mcp-service-request"
    {"type" "object"
     "properties"
     {"id" {"type" "string"}
      "tool-requests"
      {"$ref" "#/$defs/mcp-tools-request"}}}

    "chat-request"
    {"$ref" "#/$defs/request"}

    "request"
    {"type" "string"
     "description" "summarise what you would like"}

    "response"
    {"oneOf"
     [{"$ref" "#/$defs/done"}
      {"$ref" "#/$defs/mcp-tools-request"}
     ;;{"$ref" "#/$defs/repl-request"}
      {"$ref" "#/$defs/chat-request"}]}}

   "properties"
   {"request"
    {"$ref" "#/$defs/request"}

    "response"
    {"$ref" "#/$defs/response"}}})


;; the plan
;; to transition we need a json document - a "proposal"
;; this either comes from a request (us->llm) or a response (llm->us)
;; the fsm is direction agnostic
;; each xition carries a schema
;; the document will be validated against oneOf these schemas
;; it will make the xition of which it validates against the schema
;; when making a request, we will send a "oneOf" the xition from our state's schemas
;; the llm will make a response that conforms to the xition it wants to make
;; if it validates, we will make the xition, otherwise we go back to the llm til it gets it right

;; a transition ensures that you hve all the correct parameter for the following state
;; the state is one of:
;; -  human
;; - an llm
;; - an mcp service
;; - a repl
;; - a yet-to-be-invented extension
;; the state takes its input and provides an output

;; thoughts
;; - if the action is going to cause a request to kick off to an llvm then we need to pass in everything that it might need...
;; - fsm
(def meta-schema-uri "https://json-schema.org/draft/2020-12/schema")

(defn make-xitions-schema
  "make a schema that describes a valid proposal from a given state"
  [{xs :xitions} state-id]
  {"$schema" meta-schema-uri
   "$id" "TODO"
   "oneOf"
   (mapv
    (fn [{i :id s :schema}]
      {"properties"
       {"$schema" {"type" "string"}
        "$id" {"type" "string"}
        "id" {"const" i}
        "roles" {"type" "array" "items" {"type" "string"} "additionalItems" false}
        "document" s}
       "additionalProperties" false
       "required" ["$id" "id" "roles" "document"]})
    ((group-by (comp first :id) xs)
     state-id))})

(defn index-by [f es]
  (reduce (fn [acc e] (assoc acc (f e) e)) {} es))

(defn xition
  "given the fsm, the current-state and a valid proposal transition from
  current state to one of the possible next states"
  [{xs :xitions :as fsm} last-state {[_ next-state :as x-id] "id" rs "roles" :as input}]
  (and
   (:valid? (validate {} (make-xitions-schema fsm last-state) {} input)) ;; false on fail
   (seq (intersection (set (((index-by :id xs) x-id) :roles)) (set rs))) ;; inefficient... - nil on fail
   next-state))

;; should states and transitions be joined directly or by async channels ?

(defn state
  "bind to a new state - potentially performing an associated action"
  [action->fn {states :states :as fsm} id input]
  (let [{a :action :as state} ((index-by :id states) id)
        action (action->fn a)]
    (action input)))
    
    

    ;; if the last xition was a response from an llm then the next will be

    ;; How can we work it so that the fsm has no concept of request/response but simply connects input channels to output channels -  put together a small demo that evaluates a piece of dsl
    
    ;; we need to use async http calls ...

