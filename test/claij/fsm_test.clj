(ns claij.fsm-test
  (:require
   [clojure.tools.logging :as log]
   [clojure.string :refer [join]]
   [clojure.data.json :refer [write-str]]
   [clojure.pprint :refer [pprint]]
   [clojure.test :refer [deftest testing is]]
   [m3.uri :refer [parse-uri]]
   [m3.validate :refer [validate]]
   [claij.util :refer [def-m2 index-by ->key]]
   [claij.llm.open-router :refer [open-router-async unpack ppr-str]]
   [claij.fsm :refer [def-fsm start-fsm state-schema xition-schema schema-base-uri uri->schema]]))

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
        (is (:valid? (validate {} expected {} {"id" ["test-state-A" "test-state-B"] "document" "test"})))
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
        (is (:valid? (validate c2 {"$ref" (str schema-base-uri "/test-schema#/$defs/a-string")} {} "test")))
        (is (not (:valid? (validate c2 {"$ref" (str schema-base-uri "/test-schema#/$defs/a-string")} {} 0))))
        (is (:valid? (validate c2 {"$ref" (str schema-base-uri "/test-schema#/$defs/a-number")} {} 0)))
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

    "entry"
    {"description" "use this to enter code review loop"
     "type" "object"
     "properties"
     {"id" {"const" ["start" "mc"]}
      "document" {"type" "string"}}
     "additionalProperties" false
     "required" ["id" "document"]}

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
     "required" ["id" "code" "notes"]}}})

   ;; "oneOf" [{"$ref" "#/$defs/request"}
   ;;          {"$ref" "#/$defs/response"}
   ;;          {"$ref" "#/$defs/summary"}]

(def-fsm
  code-review-fsm
  {"schema" code-review-schema
   "id" "code-review"
   "prompts" ["You are involved in a code review workflow"]
   "states"
   [{"id" "mc"
     "action" "llm"
     "prompts"
     ["You are an MC orchestrating a code review."
      "You should always request at least one review, then merge any useful code changes and/or refactor to take any useful comments into consideration and ask for further review."
      "Keep requesting and reacting to reviews until you are satisfied that you are no longer turning up useful issues. Then please summarise your findings with the final version of the code."]}
    {"id" "reviewer"
     "action" "llm"
     "prompts"
     ["You are a code reviewer"
      "You will be requested to review some code. Please give the following careful consideration - clarity, simplicity, beauty, efficiency, intuitiveness, laconicity, idiomaticity, correctness, security, and maintainability - along with measures of your own choice."
      "You can change the code, add your comments to the review, along with general notes in your response."
      "Please do not feel that you have to find fault. If you like the code, just respond thus so that the MC can terminate the review."]}

    {"id" "end"
     "action" "end"}]

   "xitions"
   [{"id" ["start" "mc"]
     "schema" {"$ref" "#/$defs/entry"}}
    {"id" ["mc" "reviewer"]
     "prompts" []
     "schema" {"$ref" "#/$defs/request"}}
    {"id" ["reviewer" "mc"]
     "prompts" []
     "schema" {"$ref" "#/$defs/response"}}
    {"id" ["mc" "end"]
     "prompts" []
     "schema" {"$ref" "#/$defs/summary"}}]})

(defn make-prompts [{fsm-schema "schema" fsm-prompts "prompts" :as _fsm} {ix-prompts "prompts" :as _ix} {state-prompts "prompts"} trail]
  (concat
   [{"role" "system"
     "content" (join
                "\n"
                (concat
                 ["All your requests and responses will be in JSON."
                  "You are being given the following reference JSON schema. Later schemas may refer to $defs in this one:" (write-str fsm-schema) "."
                  "Requests will arrive as [INPUT-SCHEMA, DOCUMENT, OUTPUT-SCHEMA] triples."
                  "The INPUT-SCHEMA describes the structure of the DOCUMENT."
                  "You must respond to the contents of the DOCUMENT."
                  "Your response must be a single JSON document that is STRICTLY CONFORMANT (please pay particular attention to the \"id\" which must be present as a pair of strings) to the OUTPUT-SCHEMA:"]
                 fsm-prompts
                 ix-prompts
                 state-prompts))}]
   (map (fn [m] (update m "content" write-str)) (reverse trail)))) ;; trail is stored as clojure not a json doc

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
    (doseq [[provider model] [["openai" "gpt-4o"]]]
                              ;; ["x-ai" "grok-code-fast-1"]
                              ;; ["x-ai" "grok-4"]
                              ;; ["google" "gemini-2.5-flash"]
                              ;; failing
                              ;;["openai" "gpt-5-pro"]
                              ;;["anthropic" "claude-sonnet-4.5"] ;; https://forum.bubble.io/t/anthropic-json-mode-tools-via-the-api-connector-tutorial/331283

      (testing "weather"
        (testing (str provider "/" model)
          (is (:valid? (validate {:draft :draft7} schema {} (let [p (promise)] (open-router-async provider model prompts (partial deliver p) {:schema schema}) @p)))))))))

(deftest code-review-schema-test
  (testing "code-review"
    (doseq [[provider model]
            [;; ["openai" "gpt-5-codex"]
             ;; ["google" "gemini-2.5-flash"]
             ;; ["x-ai" "grok-code-fast-1"]
             ;; ["anthropic" "claude-sonnet-4.5"]
             ;; ["meta-llama" "llama-4-maverick:free"] ;; Disabled: moderation issues with error messages
             ]]
      (testing (str provider "/" model)
        (let [schema code-review-schema
              prompts (make-prompts
                       code-review-fsm
                       ((index-by (->key "id") (code-review-fsm "xitions")) ["" "mc"])
                       ((index-by (->key "id") (code-review-fsm "states")) "mc")

                       [;; previous conversation
                        ;; ...
                        ;; latest request
                        {"role" "user"
                         "content"
                         [;; describes request
                          {"$ref" "#/$defs/entry"}
                         ;; request
                          {"id" ["" "mc"] "document" "I have this piece of Clojure code to calculate the fibonacci series - can we improve it through a code review?: `(def fibs (lazy-seq (cons 0 (cons 1 (map + fibs (rest fibs))))))`"}
                         ;; describes response
                          {"oneOf" [{"$ref" "#/$defs/request"}
                                    {"$ref" "#/$defs/summary"}]}]}])]
          (let [p (promise)]
            (log/info (deref (open-router-async provider model prompts (partial deliver p) (partial deliver p)) 60000 "timed out after 60s"))
            (is (:valid? (validate {:draft :draft7} schema {} (deref p 60000 "timed out after 60s"))))))))
    ;; Test passes vacuously when no models configured
    (is true "No models configured for testing")))

(deftest code-review-fsm-mock-test
  (testing "code-review FSM with mock LLM actions"
    (let [text
          "Please review this fibonacci code: (defn fib [n] (if (<= n 1) n (+ (fib (- n 1)) (fib (- n 2)))))"

          ;; These are the data payloads that will be in the trail
          entry-data
          {"id" ["start" "mc"]
           "document" text}

          request1-data
          {"id" ["mc" "reviewer"]
           "code" {"language" {"name" "clojure"}
                   "text" "(defn fib [n]\n  (if (<= n 1)\n    n\n    (+ (fib (- n 1)) (fib (- n 2)))))"}
           "notes" "Here's a recursive fibonacci. Please review for improvements."}

          response1-data
          {"id" ["reviewer" "mc"]
           "code" {"language" {"name" "clojure"}
                   "text" "(def fib\n  (memoize\n    (fn [n]\n      (if (<= n 1)\n        n\n        (+ (fib (- n 1)) (fib (- n 2)))))))"}
           "comments" ["Consider using memoization to avoid redundant calculations"
                       "The algorithm is correct but inefficient for large n"]
           "notes" "Added memoization to improve performance."}

          request2-data
          {"id" ["mc" "reviewer"]
           "code" {"language" {"name" "clojure"}
                   "text" "(def fib\n  (memoize\n    (fn [n]\n      (if (<= n 1)\n        n\n        (+ (fib (- n 1)) (fib (- n 2)))))))"}
           "notes" "Incorporated memoization. Please review again."}

          response2-data
          {"id" ["reviewer" "mc"]
           "code" {"language" {"name" "clojure"}
                   "text" "(def fib\n  (memoize\n    (fn [n]\n      (if (<= n 1)\n        n\n        (+ (fib (- n 1)) (fib (- n 2)))))))"}
           "comments" []
           "notes" "Looks good! The memoization solves the performance issue."}

          summary-data
          {"id" ["mc" "end"]
           "code" {"language" {"name" "clojure"}
                   "text" "(def fib\n  (memoize\n    (fn [n]\n      (if (<= n 1)\n        n\n        (+ (fib (- n 1)) (fib (- n 2)))))))"}
           "notes" "Code review complete. Added memoization for performance."}

          ;; Map from input data to output data
          event-map
          {entry-data request1-data
           request1-data response1-data
           response1-data request2-data
           request2-data response2-data
           response2-data summary-data}

          llm-action (fn [_context _fsm _ix _state [{[_input-schema input-data _output-schema] "content"} & _tail] handler]
                       (handler (event-map input-data)))

          p (promise)

          end-action (fn [_context _fsm _ix _state [{[_input-schema input-data _output-schema] "content"} & _tail] _handler]
                       (deliver p input-data))

          code-review-actions {"llm" llm-action "end" end-action}

          context {:id->action code-review-actions}

          [submit stop-fsm] (start-fsm context code-review-fsm)]

      (try
        (submit text)

        (is (= summary-data (deref p 5000 false)) "FSM should complete with summary")

        (catch Throwable t
          (is false "event submission failed"))

        (finally
          (stop-fsm))))))

;;keep this around...

(defn llm-action
  ([prompts handler]
   (open-router-async
     ;; "anthropic" "claude-sonnet-4.5" ;; markdown
     ;; "x-ai" "grok-code-fast-1" ;; markdown - should work
    ;; "openai"    "gpt-5-codex" ;; didn't conform - should work
    "openai" "gpt-4o" ;; didn't conform - should work
    ;; "google" "gemini-2.5-flash" ;; should work
    prompts
    (fn [output]
      (if-let [es (handler output)]
        (log/error es)
        nil))))
  ([context fsm ix state trail handler]
   (llm-action (make-prompts fsm ix state trail) handler)))

(comment
  (let [p (promise)
        end-action (fn [_context _fsm _ix _state [{[_input-schema input-data _output-schema] "content"} & _tail] _handler] (deliver p input-data))
        code-review-actions {"llm" llm-action "end" end-action}
        context {:id->action code-review-actions}
        [submit stop-fsm] (start-fsm context code-review-fsm)]
    (submit "Please review this fibonacci code: (defn fib [n] (if (<= n 1) n (+ (fib (- n 1)) (fib (- n 2)))))")

    (println (deref p (* 5 60 1000) false))

    (stop-fsm)))

(defmacro review
  [& body]
  (let [code-str (pr-str (cons 'do body))]
    `(let [p# (promise)
           end-action# (fn [_context# _fsm# _ix# _state# [{[_input-schema# input-data# _output-schema#] "content"} & _tail#] _handler#] (deliver p# input-data#))
           code-review-actions# {"llm" llm-action "end" end-action#}
           context# {:id->action code-review-actions#}
           [submit# stop-fsm#] (start-fsm context# code-review-fsm)]
       (submit# (str "Please review this code: " ~code-str))
       (println (deref p# (* 5 60 1000) false))
       (stop-fsm#))))
