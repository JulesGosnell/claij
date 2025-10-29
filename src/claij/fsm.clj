(ns claij.fsm
  (:require
   [clojure.tools.logging :as log]
   [clojure.core.async :refer [chan go-loop alts! >!!]]
   [m3.validate :refer [validate]]
   [claij.util :refer [index-by ->key map-values]]))

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
    (fn [{i "id" s "schema"}]
      {"properties"
       {"$schema" {"type" "string"}
        "$id" {"type" "string"}
        "id" {"const" i}
        "document" s}
       "additionalProperties" false
       "required" ["$id" "id" "document"]})
    xs)})

(defn embed-schema [id s]
  {"$schema" meta-schema-uri
   "$id" (str schema-base-uri "/" "TODO")
   "properties"
   {"$schema" {"type" "string"}
    "$id" {"type" "string"}
    "id" {"const" id}
    "document" s}})

;; rename "action" "transform"
;; TODO: if we inline cform, we may be able to more work outside and less inside it...
(defn xform [action-id->action {[from to] "id" :as ix} {sid "id" a "action" :as s} ox-and-cs [h :as trail]]
  (log/infof "[%s -> %s]: %s" from to (pr-str h))

  ;; TODO:
  ;; an llm state should say which model to used
  ;; an llm state should provide prompts

  (let [state-schema {"$schema" meta-schema-uri
                      "$id" (str schema-base-uri "/FSM-ID/FSM-VERSION/" sid)
                      ;; consider additional* and other tightenings...
                      "oneOf" (mapv (fn [[{oid "id" schema "schema" :as x} c]] (embed-schema oid schema)) ox-and-cs)}
        id->x-and-c (index-by (comp (->key "id") first) ox-and-cs)
        ;; handler returns nil on success otherwise validation errors...
        handler (fn [{oid "id" :as output}]
                  (let [[{xition-schema "schema" :as ox} c] (id->x-and-c oid)
                        schema (embed-schema oid xition-schema)
                        {v? :valid? es :errors} (validate {} schema {} output)]
                    (if v?
                      (do
                        (>!! c (cons [schema output] trail))
                        nil)
                      es)))]
    (if-let [action (action-id->action a)]
      (action trail state-schema handler)
      (handler (second (first trail))))))

(defn make-fsm [action-id->action {ss "states" xs "xitions"}]
  (let [id->x (index-by (->key "id") xs)
        xid->c (map-values (fn [_k _v] (chan)) id->x)
        xs->x-and-cs (fn [_ xs] (mapv (juxt identity (comp xid->c (->key "id"))) xs))
        sid->ix-and-cs (map-values xs->x-and-cs (group-by (comp second (->key "id")) xs))
        sid->ox-and-cs (map-values xs->x-and-cs (group-by (comp first  (->key "id")) xs))]
    ((into
     {}
     (mapv
      (fn [{id "id" :as s}]
        [id
         (let [ix-and-cs (sid->ix-and-cs id)
               ox-and-cs (sid->ox-and-cs id)
               ic->ix (into {} (mapv (fn [[x c]] [c x]) ix-and-cs))
               cs (keys ic->ix)]
           (go-loop [] ;; hopefully start a loop in another thread
             (let [[v ic] (alts! cs) ;; check all input channels
                   ix (ic->ix ic)]
             ;; TODO: if we inline xform here, perhaps we can pull more code out of the loop ?
               (xform action-id->action ix s ox-and-cs v)))
           cs)])
      ss))
     "start")))


;; need:
;; a correlation id threaded through the data
;; a monitor or callback on each state
;; start and end states which are just labels
;; sub-fsms = like a sub-routine - can be embedded in a state or a transition - hmmm - schemas must match ?


