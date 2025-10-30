(ns claij.fsm-test
  (:require
   [clojure.tools.logging :as log]
   [clojure.data.json :refer [write-str]]
   [clojure.test :refer [deftest testing is]]
   [claij.util :refer [def-m2]]
   [claij.fsm :refer [def-fsm make-fsm fsm-m2]]))

;;------------------------------------------------------------------------------

;; how do we know when a trail is finished
;; an action on the terminal state...

;; can we define an fsm with e.g. start->meeting->stop
;; can we define an fsm schema ?

;; the fsm itself should be json and have a schema

;; think about terminaology for states and transitions - very important to get it right - tense ?

(def-fsm
  fsm
  {"states"
   [{"id" "start"}
    {"id" "meeting" "action" "greet"}
    {"id" "end"     "action" "log"}]

   "xitions"
   [{"id" ["" "start"]
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

;;------------------------------------------------------------------------------
;; what would a code-review-fsm look like :-)

(def-m2
  code-review-m2

  {"$schema" "https://json-schema.org/draft/2020-12/schema"
   ;;"$id" "https://example.com/code-review-schema" ;; $id is messing up $refs :-(
   "$version" 0

   "description" "structures defining possible interactions during a code review workflow"

   "type" "object"

   "$defs"
   {"code"
    {"type" "object"
     "properties"
     {"language"
      {"type" "object"
       "properties"
       {"name"
        {"type" "string"}
        "version"
        {"type" "string"}}
       "additionalProperties" false
       "required" ["name"]}

      "text"
      {"type" "string"}}
     "additionalProperties" false
     "required" ["language" "text"]}

    "notes"
    {"description" "general notes that you wish to communicate during the workflow"
     "type" "string"}

    "comments"
    {"description" "a list of specific issues that you feel should be addressed"
     "type" "array"
     "items" {"type" "string"}
     "additionalItems" false}

    "request"
    {"type" "object"
     "properties"
     {"code" {"$ref" "#/$defs/code"}
      "notes" {"$ref" "#/$defs/notes"}}
     "additionalProperties" false
     "required" ["code" "notes"]}

    "response"
    {"type" "object"
     "properties"
     {"code" {"$ref" "#/$defs/code"}
      "notes" {"$ref" "#/$defs/notes"}
      "comments" {"$ref" "#/$defs/comments"}}
     "additionalProperties" false
     "required" ["code" "notes" "comments"]}

    "summary"
    {"type" "object"
     "properties"
     {"code" {"$ref" "#/$defs/code"}
      "notes" {"$ref" "#/$defs/notes"}}
     "additionalProperties" false
     "required" ["code" "notes"]}}

   "oneOf"
   [{"description" "use this to make a request to start/continue a code review"
     "$ref" "#/$defs/request"}
    {"description" "use this to communicate your comments during a code review"
     "$ref" "#/$defs/response"}
    {"description" "use this to summarise and exit a code review loop"
     "$ref" "#/$defs/summary"}]})


(def-fsm
  code-review-fsm
  {"prompts" [{"role" "system"
               "content" "You are involved in a code review workflow"}]
   "states"
   [{"id" "mc"
     "action" "llm"
     "prompts"
     [{"role" "system"
       "content" "You are an MC orchestrating a code review."}
      {"role" "system"
       "content" "You should always request at least one review, then merge any useful code changes and/or refactor to take any useful comments into consideration and ask for further review."}
      {"role" "system"
       "content" "Keep requesting and reacting to reviews until you are satisfied that you are no longer turning up useful issues. Then please summarise your findings with the final version of the code."}]}
    {"id" "reviewer"
     "action" "llm"
     "prompts"
     [{"role" "system"
       "content" "You are a code reviewer"}
      {"role" "system"
       "content" "You will be requested to review some code. Please give the following careful consideration - clarity, simplicity, beauty, efficiency, intuitiveness, terseness - along with measures of your own choice."}
      {"role" "system"
       "content" "You can change the code, add your comments to the review, along with general notes in your response."}
      {"role" "system"
       "content" "Please do not feel that you have to find fault. If you like the code, just respond thus so that the MC can terminate the review."}]}

    {"id" "end"
     "action" "end"}]

   "xitions"
   [{"id" ["mc" "reviewer"]
     "prompts" []
     "schema" {"$ref" "https://example.com/code-review-schema/$defs/request"}}
    {"id" ["reviewer" "mc"]
     "prompts" []
     "schema" {"$ref" "https://example.com/code-review-schema/$defs/response"}}
    {"id" ["mc" "end"]
     "prompts" []
     "schema" {"$ref" "https://example.com/code-review-schema/$defs/summary"}}]})


  ;; TODO
(defn llm-action [[[_ {document "document" :as _input}] :as _inputs] {schema-id "$id" [{{{id "const"} "id"} "properties"}] "oneOf" :as _schema} handler]

;; llm-action
;; explain that reqs/ress are in json - non-conformant documents will be returned
{"role" "system"
 "content" "You are a cog in a larger machine. All your requests and responses must be received/given in JSON. You will be provided with relevant self-explanatory schemas."}
;; provide the input schema
{"role" "system"
 "content" "Your input document is conformant to this schema: %s"}
;; provide the output schema
{"role" "system"
 "content" "Your output document must be conformant to this schema: %s"}

  ;; we need:

  ;; fsm-prompts
  ;; input xition:
  ;; - prompts
  ;; - schema
  ;; state prompts
  ;; output xitions schema

  ;; arrange them all then call open-router, registering handler to be called with output document
  
  (if-let [es
           (handler
            {"$schema" schema-id
             "$id" "TODO"
             "id" id
             "document" ({"how are you ?" "very well thank-you"} document)})]
    (log/error es)
    nil))

;; throw a retrospective flag on end state to queue this workflow for a trtro
