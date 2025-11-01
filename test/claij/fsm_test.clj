(ns claij.fsm-test
  (:require
   [clojure.tools.logging :as log]
   [clojure.data.json :refer [write-str]]
   [clojure.test :refer [deftest testing is]]
   [m3.validate :refer [validate]]
   [claij.util :refer [def-m2]]
   [claij.llm.open-router :refer [open-router-async]]
   [claij.fsm :refer [def-fsm make-fsm state-schema xition-schema]]))

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

;; TODO: provide schemas for each item in trail otherwise how will they be understood ?

(defn greet-action [_fsm _ix _s [[_ {document "document" :as _input}] :as _inputs] {schema-id "$id" [{{{id "const"} "id"} "properties"}] "oneOf" :as _schema} handler]
  (if-let [es
           (handler
            {"$schema" schema-id
             "$id" "TODO"
             "id" id
             "document" ({"how are you ?" "very well thank-you"} document)})]
    (log/error es)
    nil))

;; TODO: this may not be enough
(defn log-action [_fsm _ix _s [input :as _inputs] _schema handler]
  (log/info "END:" (second input))
  ;; (if-let [es (handler input)]
  ;;   (log/error es)
  ;;   nil)
  )

(def action-id->action
  {"greet" greet-action
   "log" log-action})

(comment
  (make-fsm action-id->action fsm "mc")
  (def c (first *1))
  (clojure.core.async/>!! c [[true {"$schema" "http://megalodon:8080/schemas/FSM-ID/FSM-VERSION/start/transitions" "$id" "TODO" "id" ["start" "meeting"] "document" "how are you ?"}]])

  )


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
  code-review-schema

  {"$schema" "https://json-schema.org/draft/2020-12/schema"
   ;;"$id" "https://example.com/code-review-schema" ;; $id is messing up $refs :-(
   "$$id" "https://claij.org/schemas/code-review-schema"
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
    {"description" "use this to make a request to start/continue a code review"
     "type" "object"
     "properties"
     {"code" {"$ref" "#/$defs/code"}
      "notes" {"$ref" "#/$defs/notes"}}
     "additionalProperties" false
     "required" ["code" "notes"]}

    "response"
    {"description" "use this to respond with your comments during a code review"
     "type" "object"
     "properties"
     {"code" {"$ref" "#/$defs/code"}
      "notes" {"$ref" "#/$defs/notes"}
      "comments" {"$ref" "#/$defs/comments"}}
     "additionalProperties" false
     "required" ["code" "notes" "comments"]}

    "summary"
    {"description" "use this to summarise and exit a code review loop"
     "type" "object"
     "properties"
     {"code" {"$ref" "#/$defs/code"}
      "notes" {"$ref" "#/$defs/notes"}}
     "additionalProperties" false
     "required" ["code" "notes"]}}

   "oneOf"
   [{"$ref" "#/$defs/request"}
    {"$ref" "#/$defs/response"}
    {"$ref" "#/$defs/summary"}]})

;; (def code-def
;;   {"type" "object"
;;    "properties"
;;    {"language"
;;     {"type" "object"
;;      "properties"
;;      {"name"
;;       {"type" "string"}
;;       "version"
;;       {"type" "string"}}
;;      "additionalProperties" false
;;      "required" ["name"]}

;;     "text"
;;     {"type" "string"}}
;;    "additionalProperties" false
;;    "required" ["language" "text"]})

;; (def notes-def
;;   {"description" "general notes that you wish to communicate during the workflow"
;;    "type" "string"})

;; (def comments-def
;;   {"description" "a list of specific issues that you feel should be addressed"
;;    "type" "array"
;;    "items" {"type" "string"}
;;    "additionalItems" false})

;; (def request-def
;;   {"description" "use this to make a request to start/continue a code review"
;;    "type" "object"
;;    "properties"
;;    {"code" code-def
;;     "notes" notes-def}
;;    "additionalProperties" false
;;    "required" ["code" "notes"]})

;; (def response-def
;;   {"description" "use this to respond with your comments during a code review"
;;    "type" "object"
;;    "properties"
;;    {"code" code-def
;;     "notes" notes-def
;;     "comments" comments-def}
;;    "additionalProperties" false
;;    "required" ["code" "notes" "comments"]})

;; (def summary-def
;;   {"description" "use this to summarise and exit a code review loop"
;;    "type" "object"
;;    "properties"
;;    {"code" code-def
;;     "notes" notes-def}
;;    "additionalProperties" false
;;    "required" ["code" "notes"]})

(def-fsm
  code-review-fsm
  {"schema" code-review-schema
   "prompts" [{"role" "user"
               "content" "You are involved in a code review workflow"}]
   "states"
   [{"id" "mc"
     "action" "llm"
     "prompts"
     [{"role" "user"
       "content" "You are an MC orchestrating a code review."}
      {"role" "user"
       "content" "You should always request at least one review, then merge any useful code changes and/or refactor to take any useful comments into consideration and ask for further review."}
      {"role" "user"
       "content" "Keep requesting and reacting to reviews until you are satisfied that you are no longer turning up useful issues. Then please summarise your findings with the final version of the code."}]}
    {"id" "reviewer"
     "action" "llm"
     "prompts"
     [{"role" "user"
       "content" "You are a code reviewer"}
      {"role" "user"
       "content" "You will be requested to review some code. Please give the following careful consideration - clarity, simplicity, beauty, efficiency, intuitiveness, laconicity, idiomaticity, correctness, security, and maintainability - along with measures of your own choice."}
      {"role" "user"
       "content" "You can change the code, add your comments to the review, along with general notes in your response."}
      {"role" "user"
       "content" "Please do not feel that you have to find fault. If you like the code, just respond thus so that the MC can terminate the review."}]}

    {"id" "end"
     "action" "end"}]

   "xitions"
   [{"id" ["" "mc"]
     "schema" true} ;; TODO: tighten schema
    {"id" ["mc" "reviewer"]
     "prompts" []
     "schema" {"$ref" "https://claij.org/schemas/code-review-schema#/$defs/request"}}
    {"id" ["reviewer" "mc"]
     "prompts" []
     "schema" {"$ref" "https://claij.org/schemas/code-review-schema#/$defs/response"}}
    {"id" ["mc" "end"]
     "prompts" []
     "schema" {"$ref" "https://claij.org/schemas/code-review-schema#/$defs/summary"}}]})

;; TODO

(def system-prompts
  [{"role" "system"
    "content" "You are a cog in a larger machine. All your requests and responses must be received/given in JSON. You will be provided with relevant self-explanatory schemas."}])

(defn llm-action [{fsm-prompts "prompts" :as _fsm}
                  {ix-schema "schema" ix-prompts "prompts" :as _ix}
                  {state-prompts "prompts"}
                  [input & memory]
                  ox-schema
                  handler]
  (let [prompts
        (concat
         system-prompts
         fsm-prompts
         [{"role" "user"
           "content" (format "Your input document is conformant to this schema: %s" (write-str ix-schema))}
          {"role" "user"
           "content" (format "Your output document must be conformant to this schema: %s" (write-str ox-schema))}]
         ix-prompts
         state-prompts
         [{"role" "user" "content" "The following two json values are arranged in [schema document] pairs. The first is a complete conversational history (newest->oldest). The second is your current input. Please produce your output accordingly"}
          {"role" "user"  "content" (write-str (vec memory))}
          {"role" "user"  "content" (write-str input)}])]
    
    (open-router-async
     ;; "anthropic" "claude-sonnet-4.5" ;; markdown
     ;; "x-ai" "grok-code-fast-1" ;; markdown
     ;; "openai"    "gpt-5-codex" ;; didn't conform
     "google" "gemini-2.5-flash" ;; markdown
     ox-schema
     prompts
     (fn [output]
       (if-let [es (handler output)]
         (log/error es)
         nil)))))

;; TODO
;; throw a retrospective flag on end state to queue this workflow for a trtro


(comment
  (make-fsm action-id->action code-review-fsm "mc")
  (def c (first *1))
  (clojure.core.async/>!! c [[true "I have this piece of Clojure code to calculate the fibonacci series - can we improve it through a code review?: `(def fibs (lazy-seq (cons 0 (cons 1 (map + fibs (rest fibs))))))`"]])

  
  (clojure.core.async/>!! c [[true {"id" ["" "mc"] "document" "I have this piece of Clojure code to calculate the fibonacci series - can we improve it through a code review?: `(def fibs (lazy-seq (cons 0 (cons 1 (map + fibs (rest fibs))))))`"}]]))

{"$schema" "http://megalodon:8080/schemas/FSM-ID/FSM-VERSION/start/transitions" "$id" "TODO" "id" ["start" "meeting"] "document" "how are you ?"}

;; nearly working
;; TODO:
;; - move code-review-schema to top-level of code-review-workflow
;; - put $refs back into xition schemas
;; - put fsm schema into context so it can be referred to locally from xitions ?
;; - or store schema separately and add to context so we can ref it from the xititions
;; - pass whole schema to request
;; - get it all working
;; - try uri->schema in c2 - needs some investigation

;;------------------------------------------------------------------------------

(comment

  (def u->s
    {{:type :url, :origin "http://jules.com", :path "/schemas/test"}
     {"$schema" "https://json-schema.org/draft/2020-12/schema"
    ;;"$id" "http://jules.com/schemas/test"
      "$defs" {"foo" {"type" "string"}}}})

  (defn uri->schema [c p uri]
    (if-let [m2 (u->s (m3.uri/uri-base uri))]
      [(-> (m3.validate/make-context
            (-> c
                (select-keys [:uri->schema :trace? :draft :id-key])
                (assoc :id-uri (m3.uri/uri-base uri)))
            m2)
           (assoc :id-uri (m3.uri/uri-base uri))
           (update :uri->path assoc (m3.uri/uri-base uri) []))
       []
       m2]
      ((m3.validate/uri->continuation m3.validate/uri-base->dir) c p uri)))

  (m3.validate/validate
   {:draft :draft7
    :uri->schema uri->schema}
   {"$ref" "http://jules.com/schemas/test#/$defs/foo"}
   {}
   "test"))

;;------------------------------------------------------------------------------

(deftest fms-test
  (let [fsm {"id" "test-fsm" "version" 0}
        state {"id" "test-state-A"}
        xition-1 {"id" ["test-state-A" "test-state-B"] "schema" {"type" "string"}}
        xition-2 {"id" ["test-state-A" "test-state-C"] "schema" {"type" "number"}}]
    (testing "xition-schema"
      (let [actual (xition-schema fsm xition-1)
            expected
            {"$schema" "https://json-schema.org/draft/2020-12/schema"
             "$$id" "https://claij.org/schemas/test-fsm/0/test-state-A.test-state-B"
             "properties"
             {"$schema" {"type" "string"}
              "$id" {"type" "string"}
              "id" {"const" ["test-state-A" "test-state-B"]}
              "document" {"type" "string"}}}]
        (is (= expected actual))
        (is      (:valid? (validate {} expected {} {"id" ["test-state-A" "test-state-B"] "document" "test"})))
        (is (not (:valid? (validate {} expected {} {"id" ["test-state-A" "test-state-B"] "document" 0}))))))
    (testing "state-schema"
      (let [actual
            (state-schema fsm state [xition-1 xition-2])
            expected
            {"$schema" "https://json-schema.org/draft/2020-12/schema"
             "$$id" "https://claij.org/schemas/test-fsm/0/test-state-A"
             "oneOf"
             [{"properties"
               {"$schema" {"type" "string"}
                "$id" {"type" "string"}
                "id" {"const" ["test-state-A" "test-state-B"]}
                "document" {"type" "string"}}}
              {"properties"
               {"$schema" {"type" "string"}
                "$id" {"type" "string"}
                "id" {"const" ["test-state-A" "test-state-C"]}
                "document" {"type" "number"}}}]}]
        (is (= expected actual))
        (is      (:valid? (validate {} expected {} {"id" ["test-state-A" "test-state-B"] "document" "test"})))
        (is (not (:valid? (validate {} expected {} {"id" ["test-state-A" "test-state-B"] "document" 0}))))
        (is      (:valid? (validate {} expected {} {"id" ["test-state-A" "test-state-C"] "document" 0})))
        (is (not (:valid? (validate {} expected {} {"id" ["test-state-A" "test-state-C"] "document" "test"}))))))))
