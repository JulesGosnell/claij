(ns claij.fsm-test
  (:require
   [clojure.tools.logging :as log]
   [clojure.data.json :refer [write-str]]
   [clojure.pprint :refer [pprint]]
   [clojure.test :refer [deftest testing is]]
   [m3.uri :refer [parse-uri]]
   [m3.validate :refer [validate]]
   [claij.util :refer [def-m2 index-by ->key]]
   [claij.llm.open-router :refer [open-router-async unpack ppr-str]]
   [claij.fsm :refer [def-fsm make-fsm state-schema xition-schema schema-base-uri uri->schema]]))

;;------------------------------------------------------------------------------
;; how do we know when a trail is finished
;; an action on the terminal state...
;; can we define an fsm with e.g. start->meeting->stop
;; can we define an fsm schema ?
;; the fsm itself should be json and have a schema
;; think about terminaology for states and transitions - very important to get it right - tense ?

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
    ;; (testing "state-schema: "
    ;;   (testing "with values"
    ;;     (let [actual
    ;;           (state-schema fsm state [xition-1 xition-2])
    ;;           expected
    ;;           {"$schema" "https://json-schema.org/draft/2020-12/schema"
    ;;            "$$id" "https://claij.org/schemas/test-fsm/0/test-state-A"
    ;;            "oneOf"
    ;;            [{"properties"
    ;;              {"$schema" {"type" "string"}
    ;;               "$id" {"type" "string"}
    ;;               "id" {"const" ["test-state-A" "test-state-B"]}
    ;;               "document" {"type" "string"}}}
    ;;             {"properties"
    ;;              {"$schema" {"type" "string"}
    ;;               "$id" {"type" "string"}
    ;;               "id" {"const" ["test-state-A" "test-state-C"]}
    ;;               "document" {"type" "number"}}}]}]
    ;;       (is (= expected actual))
    ;;       (is      (:valid? (validate {} expected {} {"id" ["test-state-A" "test-state-B"] "document" "test"})))
    ;;       (is (not (:valid? (validate {} expected {} {"id" ["test-state-A" "test-state-B"] "document" 0}))))
    ;;       (is      (:valid? (validate {} expected {} {"id" ["test-state-A" "test-state-C"] "document" 0})))
    ;;       (is (not (:valid? (validate {} expected {} {"id" ["test-state-A" "test-state-C"] "document" "test"}))))))
    ;;   (testing "with refs"
    ;;     (let [actual
    ;;           (state-schema fsm state [xition-1 xition-2])
    ;;           expected
    ;;           {"$schema" "https://json-schema.org/draft/2020-12/schema"
    ;;            "$$id" "https://claij.org/schemas/test-fsm/0/test-state-A"
    ;;            "oneOf"
    ;;            [{"properties"
    ;;              {"$schema" {"type" "string"}
    ;;               "$id" {"type" "string"}
    ;;               "id" {"const" ["test-state-A" "test-state-B"]}
    ;;               "document" {"type" "string"}}}
    ;;             {"properties"
    ;;              {"$schema" {"type" "string"}
    ;;               "$id" {"type" "string"}
    ;;               "id" {"const" ["test-state-A" "test-state-C"]}
    ;;               "document" {"type" "number"}}}]}]
    ;;       (is (= expected actual))
    ;;       (is      (:valid? (validate {} expected {} {"id" ["test-state-A" "test-state-B"] "document" "test"})))
    ;;       (is (not (:valid? (validate {} expected {} {"id" ["test-state-A" "test-state-B"] "document" 0}))))
    ;;       (is      (:valid? (validate {} expected {} {"id" ["test-state-A" "test-state-C"] "document" 0})))
    ;;       (is (not (:valid? (validate {} expected {} {"id" ["test-state-A" "test-state-C"] "document" "test"})))))))
    (testing "$ref to remote schema"
      (let [c2
            {:draft :draft2020-12
             :uri->schema
             (partial
              uri->schema
              {(parse-uri (str schema-base-uri "/test-schema"))
               {"$defs" {"a-string" {"type" "string"}
                         "a-number" {"type" "number"}}}})}]
        (is      (:valid? (validate c2 {"$ref" (str schema-base-uri "/test-schema#/$defs/a-string")} {} "test")))
        (is (not (:valid? (validate c2 {"$ref" (str schema-base-uri "/test-schema#/$defs/a-string")} {} 0))))
        (is      (:valid? (validate c2 {"$ref" (str schema-base-uri "/test-schema#/$defs/a-number")} {} 0)))
        (is (not (:valid? (validate c2 {"$ref" (str schema-base-uri "/test-schema#/$defs/a-number")} {} "test"))))))))

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
     {"id" {"const" ["mc" "reviewer"]}
      "code" {"$ref" "#/$defs/code"}
      "notes" {"$ref" "#/$defs/notes"}}
     "additionalProperties" false
     "required" ["id" "code" "notes"]}

    "response"
    {"description" "use this to respond with your comments during a code review"
     "type" "object"
     "properties"
     {"id" {"const" ["reviewer" "mc"]}
      "code" {"$ref" "#/$defs/code"}
      "notes" {"$ref" "#/$defs/notes"}
      "comments" {"$ref" "#/$defs/comments"}}
     "additionalProperties" false
     "required" ["id" "code" "comments"]}

    "summary"
    {"description" "use this to summarise and exit a code review loop"
     "type" "object"
     "properties"
     {"id" {"const" ["mc" "end"]}
      "code" {"$ref" "#/$defs/code"}
      "notes" {"$ref" "#/$defs/notes"}}
     "additionalProperties" false
     "required" ["id" "code" "notes"]}}

   ;; "oneOf" [{"$ref" "#/$defs/request"}
   ;;          {"$ref" "#/$defs/response"}
   ;;          {"$ref" "#/$defs/summary"}]
   })

(def-fsm
  code-review-fsm
  {"schema" code-review-schema
   "id" "code-review"
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
     "schema"
     {"description" "use this to summarise and exit a code review loop"
      "type" "object"
      "properties"
      {"id" {"const" ["" "mc"]}
       "document" {"type" "string"}}
      "additionalProperties" false
      "required" ["id" "document"]}}
    {"id" ["mc" "reviewer"]
     "prompts" []
     "schema" {"$ref" "#/$defs/request"}}
    {"id" ["reviewer" "mc"]
     "prompts" []
     "schema" {"$ref" "#/$defs/response"}}
    {"id" ["mc" "end"]
     "prompts" []
     "schema" {"$ref" "#/$defs/summary"}}]})

(def system-prompts
  [{"role" "system"
    "content" "You are a cog in a larger machine. All your requests and responses must be received/given in JSON. You will be provided with relevant self-explanatory schemas. Please adhere strictly to your output schema. Do not add any additional properties."}])


(defn make-prompts [{fsm-schema "schema" fsm-prompts "prompts" :as _fsm} {ix-schema "schema" ix-prompts "prompts" :as _ix} {state-prompts "prompts"} memory ox-schema ix-document]
  (concat
   [{"role" "system"
     "content" (str
                "All your requests and responses will be JSON with accompanying explanatory JSON schemas."
                "You are being given the following reference JSON schema. Later schemas may refer to $defs in this one:" (write-str fsm-schema) "."
                "Requests will arrive as [schema, document] pairs - The schema describes the document.You must respond to the contents of the document")}]
   fsm-prompts
   ix-prompts
   state-prompts
   memory
   [{"role" "user"
     "content"
     (str
      "Your response must be a single JSON document that is STRICTLY CONFORMANT (please pay particular attention to the \"id\" which must be present as a pair of strings) to this schema:"
      (write-str ox-schema))}
    {"role" "user"
     "content" (write-str [ix-schema ix-document])}]))

(defn llm-action
  ([prompts ox-schema handler]
   (open-router-async
     ;; "anthropic" "claude-sonnet-4.5" ;; markdown
     ;; "x-ai" "grok-code-fast-1" ;; markdown - should work
    ;; "openai"    "gpt-5-codex" ;; didn't conform - should work
    "openai"    "gpt-4o" ;; didn't conform - should work
    ;; "google" "gemini-2.5-flash" ;; should work
    ox-schema
    prompts
    (fn [output]
      (if-let [es (handler output)]
        (log/error es)
        nil))))
  ([fsm ix state inputs ox-schema handler]
   (llm-action
    (make-prompts fsm ix state ox-schema inputs)
    ox-schema
    handler)))

(def code-review-actions
  {"llm" llm-action})

(comment
  (make-fsm code-review-actions code-review-fsm "mc")
  (def c (first *1))
  (clojure.core.async/>!!
   c
   [[{"properties" {"id" {"type" "array" "item" {"type" "string"}} "document" {"type" "string"}}}
     {"id" ["" "mc"] "document" "I have this piece of Clojure code to calculate the fibonacci series - can we improve it through a code review?: `(def fibs (lazy-seq (cons 0 (cons 1 (map + fibs (rest fibs))))))`"}]]))

;; TODO:
;; - or store schema separately and add to context so we can ref it from the xititions
;; - pass whole schema to request
;; - get it all working
;; - throw a retrospective flag on end state to queue this workflow for a trtro

;;------------------------------------------------------------------------------

;; (clj-http.client/post
;;  (str api-url "/chat/completions")
;;  {:async? true
;;   :headers headers
;;   :body
;;   (clojure.data.json/write-str
;;    {:model (str provider "/" model)
;;     :response_format
;;     {:type "json_schema"
;;      :json_schema
;;      {:name "weather"
;;       :strict true
;;       :schema {"type" "object",
;;                "properties"
;;                {"location"
;;                 {"type" "string", "description" "City or location name"},
;;                 "temperature"
;;                 {"type" "number", "description" "Temperature in Celsius"},
;;                 "conditions"
;;                 {"type" "string", "description" "Weather conditions description"}},
;;                "required" ["location" "temperature" "conditions"],
;;                "additionalProperties" false}}}
;;     :messages {"role" "user", "content" "What's the weather like in London?"}})}
;;  (fn [{b :body}] (let [{[{{c "content"} "message"}] "choices"} (clojure.data.json/read-str b)] (handler (clojure.data.json/read-str (strip-md-json c)))))
;;  (fn [exception] (println "Error:" (.getMessage exception))))

;; (defn unpack [{b :body}]
;;   (let [{[{{c "content"} "message"}] "choices"} (clojure.data.json/read-str (clojure.string/trim b))]
;;     c
;;     ;;(clojure.data.json/read-str (strip-md-json c))
;;     ))

(defn trace [m v]
  (prn m v)
  v)

(deftest structured-data-integration-test
  (let [schema
        {"$id" "https://claij.org/schemas/structured-data-integration-test"
         "type" "object",
         "properties"
         {"location"
          {"type" "string", "description" "City or location name"},
          "temperature"
          {"type" "number", "description" "Temperature in Celsius"},
          "conditions"
          {"type" "string", "description" "Weather conditions description"}},
         "required" ["location" "temperature" "conditions"],
         "additionalProperties" false}
        prompts
        [{"role" "user", "content" "What's the weather like in London?"}]]
    (doseq [[provider model] [["openai" "gpt-4o"]
                              ;; ["x-ai" "grok-code-fast-1"]
                              ;; ["x-ai" "grok-4"]
                              ;; ["google" "gemini-2.5-flash"]
                              ;; failing
                              ;;["openai" "gpt-5-pro"]
                              ;;["anthropic" "claude-sonnet-4.5"] ;; https://forum.bubble.io/t/anthropic-json-mode-tools-via-the-api-connector-tutorial/331283
                              ]]
      (testing "weather"
        (testing (str provider "/" model)
          (is (:valid? (validate {:draft :draft7} schema {} (let [p (promise)] (open-router-async provider model schema prompts (partial deliver p)) @p)))))))))

;; ["openai" "gpt-4o"]
;; - no oneOfs
;; - no const arrays

;; ["x-ai" "grok-4"]

;; (deftest code-review-schema-test
;;   (testing "code-review"
;;     (doseq [[provider model]
;;             [
;;              ["openai" "gpt-5-pro"]
;;              ["google" "gemini-2.5-flash"]
;;              ["x-ai" "grok-4"]
;;              ["anthropic" "claude-sonnet-4.5"]
;;              ]]
;;       (testing (str provider "/" model)
;;         (let [schema code-review-schema
;;               prompts
;;               [{"role" "system"
;;                 "content" (str "Your output must be expressed as JSON and MUST BE STRICTLY CONFORMANT (please pay particular attention to the \"id\" which must be present as a pair of strings) to this schema: " (write-str code-review-schema))}
;;                {"role" "user"
;;                 "content" (str "Your input is expressed as JSON conforming to this schema: " (write-str {"type" "object" "properties" {"id" {"const" ["" "mc"]} "document" {"type" "string"}}}))}
;;                {"role" "user"
;;                 "content" (str "Here is your input - please respond to it as instructed: " (write-str {"id" ["" "mc"] "document" "I have this piece of Clojure code to calculate the fibonacci series - I'd like you to request a code review to improve it?: `(def fibs (lazy-seq (cons 0 (cons 1 (map + fibs (rest fibs))))))`"}))}]]
;;           (testing (str provider "/" model)
;;             (let [p (promise)]
;;               (log/info @(open-router-async provider model schema prompts (partial deliver p) (partial deliver p)))
;;               (is (:valid? (validate {:draft :draft7} schema {} @p))))))))))

;; need another test with conversational history plus code to integrate this:

(deftest code-review-schema-test
  (testing "code-review"
    (doseq [[provider model]
            [;; ["openai" "gpt-5-pro"]
             ;; ["google" "gemini-2.5-flash"]
             ;; ["x-ai" "grok-4"]
             ["anthropic" "claude-sonnet-4.5"]
             ]]
      (testing (str provider "/" model)
        (let [schema code-review-schema
              prompts (make-prompts
                       code-review-fsm
                       ((index-by (->key "id") (code-review-fsm "xitions")) ["" "mc"])
                       ((index-by (->key "id") (code-review-fsm "states"))  "mc")
                       []
                       {"oneOf" [{"$ref" "#/$defs/request"}
                                 {"$ref" "#/$defs/summary"}]}
                       {"id" ["" "mc"] "document" "I have this piece of Clojure code to calculate the fibonacci series - can we improve it through a code review?: `(def fibs (lazy-seq (cons 0 (cons 1 (map + fibs (rest fibs))))))`"})]
          (testing (str provider "/" model)
            (let [p (promise)]
              (log/info @(open-router-async provider model schema prompts (partial deliver p) (partial deliver p)))
              (is (:valid? (validate {:draft :draft7} schema {} @p))))))))))

;; the oneOf should not be in the fsm level schema but added by state-schema
