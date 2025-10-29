(ns claij.fsm-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.set :refer [intersection]]
   [m3.validate :refer [validate]]
   [claij.fsm :refer [make-xitions-schema xition state meta-schema-uri index-by]]))

(def fsm
  {:states
   [{:id "a"}
    {:id "b"}
    {:id "c"}]

   :xitions
   [{:id ["a" "a"]
     :roles ["lead"]
     :schema {"type" "number"}}
    {:id ["a" "b"]
     :roles ["lead" "dev"]
     :schema {"type" "string"}}
    {:id ["a" "c"]
     :roles ["tools"]
     :schema {"type" "boolean"}}]})

(deftest test-fsm

  (testing "make-xitions-schema"
    (is
     (=
      {"$schema" "https://json-schema.org/draft/2020-12/schema",
       "$id" "TODO",
       "oneOf"
       [{"properties"
         {"$schema" {"type" "string"},
          "$id" {"type" "string"},
          "id" {"const" ["a" "a"]},
          "roles"
          {"type" "array",
           "items" {"type" "string"},
           "additionalItems" false},
          "document" {"type" "number"}},
         "additionalProperties" false,
         "required" ["$id" "id" "roles" "document"]}
        {"properties"
         {"$schema" {"type" "string"},
          "$id" {"type" "string"},
          "id" {"const" ["a" "b"]},
          "roles"
          {"type" "array",
           "items" {"type" "string"},
           "additionalItems" false},
          "document" {"type" "string"}},
         "additionalProperties" false,
         "required" ["$id" "id" "roles" "document"]}
        {"properties"
         {"$schema" {"type" "string"},
          "$id" {"type" "string"},
          "id" {"const" ["a" "c"]},
          "roles"
          {"type" "array",
           "items" {"type" "string"},
           "additionalItems" false},
          "document" {"type" "boolean"}},
         "additionalProperties" false,
         "required" ["$id" "id" "roles" "document"]}]}
      (make-xitions-schema fsm "a"))))

;; allow switching transition by role
  ;; but then transitions become ambiguous

  (testing "xition: "
    (testing "matches"
      (is
       (=
        "b"
        (xition
         fsm
         "a"
         {"$id" "TODO"
          "id" ["a" "b"]
          "roles" ["dev" "docs"]
          "document" "a test xition"}))))
    (testing "doesn't match"
      (is
       (false?
        (xition
         fsm
         "a"
         {"$id" "TODO"
          "id" ["a" "b"]
          "roles" ["dev" "docs"]
          "document" 0}))))
    (testing "out of role"
      (is
       (nil?
        (xition
         fsm
         "a"
         {"$id" "TODO"
          "id" ["a" "b"]
          "roles" ["docs"]
          "document" "a test xition"})))))

  (testing "state: "
    (testing "simple"
      (let [state-id "meeting"
            action-id "greet"
            input "how are you"
            output "very well thank-you"]
        (is (=
             output
             (state
              {action-id {input output}}
              {:states [{:id state-id :action action-id}]}
              state-id
              input)))))))



;;------------------------------------------------------------------------------

;; how do we know when a trail is finished
;; an action on the terminal state...

;; can we define an fsm with e.g. start->meeting->stop
;; can we define an fsm schema ?

;; the fsm itself should be json and have a schema

;; think about terminaology for states and transitions - very important to get it right - tense ?

(def fsm2
  {:states
   [{:id "start"}
    {:id "meeting" :action "greet"}
    {:id "end"     :action "identity"}]

   :xitions
   [{:id ["start" "meeting"]
     :roles ["jules"]
     :schema {"type" "string"}}
    {:id ["meeting" "end"]
     :roles ["jules"]
     :schema {"type" "string"}}]})

;;------------------------------------------------------------------------------
;; some actions:

(defn llm-action [input]
  ;; we need to know
  ;; - the transition's roles, so we can don those hats
  ;; - the interceptors that will build the prompt stack
  ;; - the input schema (as it documents the input)
  ;; - the input
  ;; - the output schema (built from fsm)
  ;; - which llm to use - are we taking them from a pool, choosing them at random ? are they interchangeable
  ;; - the full trail through the fsm to this point - our memory

  ;; then we call the llm return its output to our calling state, which will map it the next transition
  
  )

;; TODO: provide schemas for each item in trail otherwise how will they be understood ?

(defn greet-action [{schema-id "$id" [{{{id "const"} "id"} "properties"}] "oneOf" :as _schema} [{document "document" :as _input} :as inputs]]
  (cons
   {"$schema" schema-id
    "$id" "TODO"
    "id" id
    "roles" ["jules"] "document"
    ({"how are you ?" "very well thank-you"} document)}
   inputs))

;; TODO: this may not be enough
(defn identity-action [_schema inputs]
  inputs)

(def action-id->action
  {"greet" greet-action
   "identity" identity-action})

;;------------------------------------------------------------------------------

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
  
(deftest walk-test
  (testing "a couple more hops"
    (is
     (=
      [{"$schema"
        "http://megalodon:8080/schemas/FSM-ID/FSM-VERSION/meeting/transitions",
        "$id" "TODO",
        "id" ["meeting" "end"],
        "roles" ["jules"],
        "document" "very well thank-you"}
       {"$schema"
        "http://megalodon:8080/schemas/FSM-ID/FSM-VERSION/start/transitions",
        "$id" "TODO",
        "id" ["start" "meeting"],
        "roles" ["jules"],
        "document" "how are you ?"}]
      (walk
       action-id->action
       fsm2
       (walk
        action-id->action
        fsm2
        (list
         {"$schema" "http://megalodon:8080/schemas/FSM-ID/FSM-VERSION/start/transitions"
          "$id" "TODO"
          "id" ["start" "meeting"]
          "roles" ["jules"]
          "document" "how are you ?"})))))))


 
;; TODO:
;; add [sub-]schemas to trail
;; reconsider roles - llm will be whatever we need it to be on every request so it will always be in role...
;; I think roles = hats - document the distinction
;; I guess humans could be out of role but ...
;; if [m2 m1] is returned by action and m2s are unique then we could just index-by and look up m2 without needing the oneOf validation... - yippee !
;; no - an llm will return just the m1 and we will need to do the oneOf validation to know what they meant ? or do e just get them to return [m2 m1]
;; we could just give them a list of schemas to choose from ...
;; maybe stick with oneOf stuff for the moment - consider tomorrow
;; should this be wired together with async channels and all just kick off asynchronously - yes - pass a handler to walk to put trail onto channel
;; the above is useful for controlled testing but not production
;; fsm should not have keywords
;; replace original with new impl
;; we should probably rename roles to hats
;; integrate an llm
;; integrate some sort of human postbox - email with a link ?
;; integrate mcp
;; integrate repl
