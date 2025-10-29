(ns claij.fsm-test
  (:require
   [clojure.tools.logging :as log]
   [clojure.test :refer [deftest testing is]]
   [claij.fsm :refer [make-fsm]]))

;;------------------------------------------------------------------------------

;; how do we know when a trail is finished
;; an action on the terminal state...

;; can we define an fsm with e.g. start->meeting->stop
;; can we define an fsm schema ?

;; the fsm itself should be json and have a schema

;; think about terminaology for states and transitions - very important to get it right - tense ?

(def fsm
  {"states"
   [{"id" "start"}
    {"id" "meeting" "action" "greet"}
    {"id" "end"     "action" "log"}]

   "xitions"
   [{"id" [nil "start"]
     "schema" true} ;; TODO: tighten schema
    {"id" ["start" "meeting"]
     "schema" {"type" "string"}}
    {"id" ["meeting" "end"]
     "schema" {"type" "string"}}]})

;;------------------------------------------------------------------------------
;; some actions:

(defn llm-action [input]
  ;; we need to know
  ;; - the interceptors that will build the prompt stack
  ;; - the input schema (as it documents the input)
  ;; - the input
  ;; - the output schema (built from fsm)
  ;; - which llm to use - are we taking them from a pool, choosing them at random ? are they interchangeable
  ;; - the full trail through the fsm to this point - our memory

  ;; then we call the llm return its output to our calling state, which will map it the next transition
  
  )

;; TODO: provide schemas for each item in trail otherwise how will they be understood ?

(defn greet-action [[[_ {document "document" :as _input}] :as _inputs] {schema-id "$id" [{{{id "const"} "id"} "properties"}] "oneOf" :as _schema} handler]
  (if-let [es
           (handler
            {"$schema" schema-id
             "$id" "TODO"
             "id" id
             "document" ({"how are you ?" "very well thank-you"} document)})]
    (log/error es)
    nil))

;; TODO: this may not be enough
(defn log-action [[input :as _inputs] _schema handler]
  (log/info "END:" (second input))
  ;; (if-let [es (handler input)]
  ;;   (log/error es)
  ;;   nil)
  )

(def action-id->action
  {"greet" greet-action
   "log" log-action})

(comment
  (make-fsm action-id->action fsm)
  (def c (first *1))
  (clojure.core.async/>!! c [[true {"$schema" "http://megalodon:8080/schemas/FSM-ID/FSM-VERSION/start/transitions" "$id" "TODO" "id" ["start" "meeting"] "document" "how are you ?"}]]))


;; TODO:
;; reintroduce roles as hats
;; add [sub-]schemas to trail
;; if [m2 m1] is returned by action and m2s are unique then we could just index-by and look up m2 without needing the oneOf validation... - yippee !
;; no - an llm will return just the m1 and we will need to do the oneOf validation to know what they meant ? or do e just get them to return [m2 m1]
;; we could just give them a list of schemas to choose from ...
;; maybe stick with oneOf stuff for the moment - consider tomorrow
;; should this be wired together with async channels and all just kick off asynchronously - yes - pass a handler to walk to put trail onto channel
;; the above is useful for controlled testing but not production
;; replace original with new impl
;; integrate an llm
;; integrate some sort of human postbox - email with a link ?
;; integrate mcp
;; integrate repl
