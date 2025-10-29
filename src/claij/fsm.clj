(ns claij.fsm
  (:require
   [clojure.set :refer [intersection]]
   [m3.validate :refer [validate]]
   [claij.util :refer [index-by]]))

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
    
;; if the last xition was a response from an llm then the next will be
;; How can we work it so that the fsm has no concept of request/response but simply connects input channels to output channels -  put together a small demo that evaluates a piece of dsl
;; we need to use async http calls ...

(def schema-base-uri "http://megalodon:8080/schemas")

;; TODO - fsm needs a unique id - 
;; TODO: consider version...
(defn xition-id->schema-uri [state-id]
  (str schema-base-uri "/" "FSM-ID" "/" "FSM-VERSION" "/" state-id "/transitions"))

(defn xitions->schema [last-state xs]
  {"$schema" meta-schema-uri
   "$id" (xition-id->schema-uri last-state)
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
    xs)})

(defn walk [action-id->action {ss :states xs :xitions :as _fsm} [{[last-state-id next-state-id :as x-id] "id"  roles "roles" d "document" :as input} :as inputs]]
  (let [last-state-id->xitions (group-by (comp first :id) xs)
        last-xitions (last-state-id->xitions last-state-id)]
    (if last-xitions
      (let [last-schema (xitions->schema last-state-id last-xitions)]
        (if-let [next-state-id
                 (and
                  (:valid? (validate {} last-schema {} input)) ;; false on fail
                  (seq (intersection (set (((index-by :id xs) x-id) :roles)) (set roles)))
                  next-state-id)]
          ;; bind to next state
          (let [id->state (index-by :id ss)
                {action-id :action :as _next-state} (id->state next-state-id)
                action (action-id->action action-id)
                next-state-id->xitions (group-by (comp first :id) xs)
                next-xitions (next-state-id->xitions next-state-id)
                next-schema (xitions->schema next-state-id next-xitions)]
            (action next-schema inputs)
            ;; and then recurse (pass a handler...)
            )
          ;; bail
          next-state-id ;; nil or false
          ))
      inputs)))
