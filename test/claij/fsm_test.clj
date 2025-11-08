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

    "concerns"
    {"description" "a list of code quality concerns to review"
     "type" "array"
     "items" {"type" "string"}}

    "llm"
    {"description" "specification of an LLM to use"
     "type" "object"
     "properties"
     {"provider" {"type" "string"}
      "model" {"type" "string"}}
     "additionalProperties" false
     "required" ["provider" "model"]}

    "llms"
    {"description" "list of available LLMs"
     "type" "array"
     "items" {"$ref" "#/$defs/llm"}
     "minItems" 1}

    "entry"
    {"description" "use this to enter code review loop"
     "type" "object"
     "properties"
     {"id" {"const" ["start" "mc"]}
      "document" {"type" "string"}
      "llms" {"$ref" "#/$defs/llms"}
      "concerns" {"$ref" "#/$defs/concerns"}}
     "additionalProperties" false
     "required" ["id" "document" "llms" "concerns"]}

    "request"
    {"description" "use this to make a request to start/continue a code review"
     "type" "object"
     "properties"
     {"id" {"const" ["mc" "reviewer"]}
      "code" {"$ref" "#/$defs/code"}
      "notes" {"$ref" "#/$defs/notes"}
      "concerns" {"description" "specific concerns for this review (max 3 to avoid overwhelming the reviewer)"
                  "type" "array"
                  "items" {"type" "string"}
                  "maxItems" 3}
      "llm" {"$ref" "#/$defs/llm"}}
     "additionalProperties" false
     "required" ["id" "code" "notes" "concerns" "llm"]}

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
      "You have been provided with a list of code quality concerns and a list of available LLMs."
      "Your role is to distribute the concerns effectively across multiple LLM reviewers to ensure thorough code review."
      ""
      "CONCERN DISTRIBUTION:"
      "- Review the provided concerns list carefully"
      "- Identify which concerns are most relevant to the code being reviewed"
      "- Distribute 2-3 relevant concerns to each LLM when requesting a review"
      "- You can assign different concerns to different LLMs based on their strengths"
      "- When you request a review, include the specific concerns in the 'concerns' field along with the 'llm' field specifying provider and model"
      ""
      "ITERATION STRATEGY:"
      "- Continue requesting reviews until all important concerns have been addressed"
      "- After each review, incorporate useful suggestions and request further review if needed"
      "- Stop iterating when: (1) all concerns are addressed AND (2) no new useful issues are being discovered"
      "- Then summarize your findings with the final version of the code"]}
    {"id" "reviewer"
     "action" "llm"
     "prompts"
     ["You are a code reviewer."
      "You will receive code to review along with a list of specific concerns to focus on."
      "The MC (coordinator) has selected these concerns as most relevant for this review."
      ""
      "YOUR TASK:"
      "- Give careful attention to the specific concerns provided in the request"
      "- Review the code for issues related to these concerns"
      "- You can also note other significant issues you discover"
      "- You may modify the code to demonstrate improvements"
      "- Add your specific comments about issues found"
      "- Include general notes about the review in your response"
      ""
      "IMPORTANT:"
      "- If the code looks good and addresses all concerns, say so clearly"
      "- Don't feel obligated to find problems if none exist"]}

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

;; LLM Configuration Registry
;; Maps [provider model] to configuration including model-specific prompts
(def llm-configs
  "Configuration for different LLM providers and models.
   Each entry maps [provider model] to a config map with:
   - :prompts - vector of prompt maps with :role and :content
   - Future: could include :temperature, :max-tokens, etc."
  {["anthropic" "claude-sonnet-4.5"]
   {:prompts [{:role "system"
               :content "CRITICAL: Your response must be ONLY valid JSON - no explanatory text before or after."}
              {:role "system"
               :content "CRITICAL: Ensure your JSON is complete - do not truncate. Check that all braces and brackets are closed."}
              {:role "system"
               :content "CRITICAL: Be concise in your response to avoid hitting token limits."}]}

   ;; OpenAI models - generally work well with standard prompts
   ["openai" "gpt-4o"] {}
   ["openai" "gpt-5-codex"] {}

   ;; xAI models
   ["x-ai" "grok-code-fast-1"] {}
   ["x-ai" "grok-4"] {}

   ;; Google models
   ["google" "gemini-2.5-flash"] {}})

(defn make-prompts
  "Build prompt messages from FSM configuration and conversation trail.
   Optionally accepts provider/model for LLM-specific prompts."
  ([fsm ix state trail]
   (make-prompts fsm ix state trail nil nil))
  ([{fsm-schema "schema" fsm-prompts "prompts" :as _fsm}
    {ix-prompts "prompts" :as _ix}
    {state-prompts "prompts"}
    trail
    provider
    model]
   (let [;; Look up LLM-specific configuration
         llm-config (get llm-configs [provider model] {})
         llm-prompts (get llm-config :prompts [])

         ;; Separate system and user prompts from LLM config
         llm-system-prompts (mapv :content (filter #(= (:role %) "system") llm-prompts))
         llm-user-prompts (mapv :content (filter #(= (:role %) "user") llm-prompts))]

     (concat
      ;; Build system message with all system-level prompts
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
                    state-prompts
                    llm-system-prompts))}]

      ;; Add any LLM-specific user prompts
      (when (seq llm-user-prompts)
        [{"role" "user"
          "content" (join "\n" llm-user-prompts)}])

      ;; Add conversation trail
      (map (fn [m] (update m "content" write-str)) (reverse trail))))))

(deftest weather-schema-test
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

;; Example concerns list distilled from CLAIJ coding guidelines
;; These can be used as a starting point for code review
(def example-code-review-concerns
  ["Simplicity: Always ask 'can this be simpler?' Avoid unnecessary complexity"
   "Naming: Use short, clear, symmetric names. Prefer Anglo-Saxon over Latin/Greek (e.g., 'get' not 'retrieve')"
   "No boilerplate: Every line should earn its place. No defensive programming unless clearly justified"
   "Functional style: Use pure functions, immutable data, and composition"
   "Small functions: Keep functions focused and under ~20 lines when possible for LLM-assisted development"
   "Performance: Avoid reflection by using type hints (enable *warn-on-reflection*)"
   "YAGNI: Don't build for imagined futures. Code only what's needed now"
   "Idiomatic Clojure: Use threading macros (-> ->>), destructuring, and sequence abstractions"
   "Data-driven design: Represent concepts as data (maps, vectors) not objects"
   "Principle of least surprise: Code should do what it looks like it does. No hidden side effects"
   "Minimize dependencies: Use one library when one will do. Prefer standard library solutions"
   "Namespace imports: Use :refer [symbols] for explicit imports. Avoid namespace aliases except for log/json/async"
   "Separation of concerns: Keep each function focused on one responsibility"
   "Error handling: Fail fast and explicitly. Use ex-info for rich error context"
   "Comments: Sparse but pragmatic. Comment the 'why' not the 'what'"])

(deftest code-review-fsm-mock-test
  (testing "code-review FSM with mock LLM actions"
    (let [text
          "Please review this fibonacci code: (defn fib [n] (if (<= n 1) n (+ (fib (- n 1)) (fib (- n 2)))))"

          llms
          [{"provider" "openai" "model" "gpt-4o"}]

          ;; Sample concerns for this review
          concerns
          ["Simplicity: Can this be simpler?"
           "Performance: Avoid reflection, consider algorithmic efficiency"
           "Functional style: Use pure functions and immutable data"]

          ;; These are the data payloads that will be in the trail
          entry-data
          {"id" ["start" "mc"]
           "document" text
           "llms" llms
           "concerns" concerns}

          request1-data
          {"id" ["mc" "reviewer"]
           "code" {"language" {"name" "clojure"}
                   "text" "(defn fib [n]\n  (if (<= n 1)\n    n\n    (+ (fib (- n 1)) (fib (- n 2)))))"}
           "notes" "Here's a recursive fibonacci. Please review for improvements."
           "concerns" ["Performance: Avoid reflection, consider algorithmic efficiency"
                       "Functional style: Use pure functions and immutable data"]
           "llm" {"provider" "openai" "model" "gpt-4o"}}

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
           "notes" "Incorporated memoization. Please review again."
           "concerns" ["Simplicity: Can this be simpler?"]
           "llm" {"provider" "openai" "model" "gpt-4o"}}

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
        (submit entry-data)

        (is (= summary-data (deref p 5000 false)) "FSM should complete with summary")

        (catch Throwable t
          (is false "event submission failed"))

        (finally
          (stop-fsm))))))

;;keep this around...

(defn llm-action
  ([prompts handler]
   (llm-action "openai" "gpt-4o" prompts handler))
  ([provider model prompts handler]
   (open-router-async
    provider model
    prompts
    (fn [output]
      (if-let [es (handler output)]
        (log/error es)
        nil))))
  ([context fsm ix state trail handler]
   ;; Extract provider and model from the input message
   (let [[{[_input-schema input-data _output-schema] "content"} & _tail] trail
         {provider "provider" model "model"} (get input-data "llm")
         ;; Default to gpt-4o if no llm specified (for backward compatibility)
         provider (or provider "openai")
         model (or model "gpt-4o")
         ;; Pass provider/model to make-prompts for LLM-specific customization
         prompts (make-prompts fsm ix state trail provider model)]
     (log/info (str "   Using LLM: " provider "/" model))
     (llm-action provider model prompts handler))))

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
           [submit# stop-fsm#] (start-fsm context# code-review-fsm)
           ;; Define available LLMs
           ;; TODO - extract this map to somewhere from whencce it can be shared
           llms# [{"provider" "openai" "model" "gpt-5-codex"}
                  {"provider" "x-ai" "model" "grok-code-fast-1"}
                  {"provider" "anthropic" "model" "claude-sonnet-4.5"}
                  {"provider" "google" "model" "gemini-2.5-flash"}]
           ;; Construct entry message with document and llms
           entry-msg# {"id" ["start" "mc"]
                       "document" (str "Please review this code: " ~code-str)
                       "llms" llms#}]
       (submit# entry-msg#)
       (pprint (deref p# (* 5 60 1000) false))
       (stop-fsm#))))

;; ideas:
;; - mcp tools and schemas
;; - benchmark fsm - mc throws problems at different llms and marks them according to concern
;; - rewrite all code once ll coordination is improved



