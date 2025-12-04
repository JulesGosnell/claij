(ns claij.fsm.code-review-fsm
  (:require
   [claij.util :refer [def-m2]]
   [claij.malli :refer [def-fsm]]
   [claij.fsm :refer [llm-action]]))

;;------------------------------------------------------------------------------
;; Code Review FSM Schema and Definition

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
    {"description" "specification of an LLM to use - MUST be one of the allowed combinations"
     "type" "object"
     "properties"
     {"provider" {"type" "string"
                  "enum" ["anthropic" "google" "openai" "x-ai"]}
      "model" {"type" "string"
               "enum" ["claude-sonnet-4" "gemini-2.5-flash" "gpt-4o" "grok-3-beta"]}}
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
      "CRITICAL - LLM SELECTION:"
      "- You MUST only use LLMs from the 'llms' list provided in the entry message"
      "- Copy the exact 'provider' and 'model' strings from that list"
      "- DO NOT invent or guess model names - only use what was provided"
      "- If you use a model not in the list, the request will fail"
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

;;------------------------------------------------------------------------------
;; LLM Configuration Registry

;;------------------------------------------------------------------------------
;; Prompt Construction

;;------------------------------------------------------------------------------
;; Example Concerns

(def example-code-review-concerns
  "Example concerns list distilled from CLAIJ coding guidelines.
   These can be used as a starting point for code review."
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

;;------------------------------------------------------------------------------
;; LLM Action

;;------------------------------------------------------------------------------
;; Review Macro

(defmacro review
  "Convenience macro to review code directly.
   Starts a code review FSM session with the provided code."
  [& body]
  (let [code-str (pr-str (cons 'do body))]
    `(let [end-action# (fn [context# _fsm# _ix# _state# [{[_input-schema# input-data# _output-schema#] "content"} & _tail#] _handler#]
                         (when-let [p# (:fsm/completion-promise context#)]
                           (deliver p# input-data#)))
           code-review-actions# {"llm" llm-action "end" end-action#}
           context# {:id->action code-review-actions#}
           [submit# await# stop-fsm#] (claij.fsm/start-fsm context# code-review-fsm)
           ;; Define available LLMs
           ;; TODO - extract this map to somewhere from whence it can be shared
           llms# [{"provider" "openai" "model" "gpt-5-codex"}
                  {"provider" "x-ai" "model" "grok-code-fast-1"}
                  {"provider" "anthropic" "model" "claude-sonnet-4.5"}
                  {"provider" "google" "model" "gemini-2.5-flash"}]
           ;; Construct entry message with document and llms
           entry-msg# {"id" ["start" "mc"]
                       "document" (str "Please review this code: " ~code-str)
                       "llms" llms#
                       "concerns" example-code-review-concerns}]
       (submit# entry-msg#)
       (let [result# (await# (* 5 60 1000))]
         (if (= result# :timeout)
           (println "Code review timed out")
           (clojure.pprint/pprint result#)))
       (stop-fsm#))))
