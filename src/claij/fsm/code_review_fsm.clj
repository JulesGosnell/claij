(ns claij.fsm.code-review-fsm
  (:require
   [malli.core :as m]
   [malli.registry :as mr]
   [claij.malli :refer [def-fsm base-registry]]
   [claij.fsm :as fsm]
   [claij.actions :as actions]))

;;------------------------------------------------------------------------------
;; Code Review FSM Schema and Definition

(def code-review-schemas
  "Schema definitions for code review events.
   Plain map for emit-for-llm analysis and inlining.
   Use string keys for refs and map entries for LLM JSON compatibility."
  {;; Code structure with language info
   "code" [:map {:closed true
                 :description "Source code with programming language metadata"}
           ["language" {:description "Programming language information"}
            [:map {:closed true}
             ["name" {:description "Language name (e.g. 'clojure', 'python', 'javascript')"} :string]
             ["version" {:optional true :description "Language version (e.g. '1.11', '3.12')"} :string]]]
           ["text" {:description "The actual source code"} :string]]

   ;; General notes field
   "notes" [:string {:description "General notes or observations about the code"}]

   ;; List of specific issues
   "comments" [:vector {:description "List of specific issues or suggestions"}
               [:string {:description "A specific comment about an issue found"}]]

   ;; List of concerns to review
   "concerns" [:vector {:description "List of code quality concerns to focus on"}
               [:string {:description "A specific concern to evaluate"}]]

   ;; LLM specification
   "llm" [:map {:closed true
                :description "LLM provider and model specification"}
          ["provider" {:description "The LLM provider"}
           [:enum "anthropic" "google" "openai" "x-ai"]]
          ["model" {:description "The specific model to use"}
           [:enum "claude-opus-4.5" "gemini-3-pro-preview" "gpt-5.2-chat" "grok-code-fast-1"]]]

   ;; List of available LLMs (min 1)
   "llms" [:vector {:min 1 :description "List of available LLMs to choose from"}
           [:ref "llm"]]

   ;; Entry event: start → mc
   "entry" [:map {:closed true
                  :description "Initial request to start a code review"}
            ["id" [:= ["start" "mc"]]]
            ["document" {:description "The code or document to review"} :string]
            ["llms" {:description "Available LLMs for the review"} [:ref "llms"]]
            ["concerns" {:description "Quality concerns to evaluate"} [:ref "concerns"]]]

   ;; Request event: mc → reviewer
   "request" [:map {:closed true
                    :description "MC's request to a reviewer for code analysis"}
              ["id" [:= ["mc" "reviewer"]]]
              ["code" {:description "The code to review"} [:ref "code"]]
              ["notes" {:description "Context or instructions for the reviewer"} [:ref "notes"]]
              ["concerns" {:description "Specific concerns for this review (max 3)"}
               [:vector {:max 3} :string]]
              ["llm" {:description "Which LLM should perform this review"} [:ref "llm"]]]

   ;; Response event: reviewer → mc
   "response" [:map {:closed true
                     :description "Reviewer's analysis and feedback"}
               ["id" [:= ["reviewer" "mc"]]]
               ["code" {:description "The code (possibly modified with improvements)"} [:ref "code"]]
               ["notes" {:optional true :description "General observations about the review"} [:ref "notes"]]
               ["comments" {:description "Specific issues or suggestions found"} [:ref "comments"]]]

   ;; Summary event: mc → end
   "summary" [:map {:closed true
                    :description "Final summary after all reviews complete"}
              ["id" [:= ["mc" "end"]]]
              ["code" {:description "The final reviewed code"} [:ref "code"]]
              ["notes" {:description "Summary of the review process and findings"} [:ref "notes"]]]})

(def code-review-registry
  "Malli registry for validation. Composes base-registry with code-review-schemas."
  (mr/composite-registry
   base-registry
   code-review-schemas))

(def-fsm
  code-review-fsm
  {"id" "code-review"
   "schemas" code-review-schemas
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
     "schema" [:ref "entry"]}
    {"id" ["mc" "reviewer"]
     "prompts" []
     "schema" [:ref "request"]}
    {"id" ["reviewer" "mc"]
     "prompts" []
     "schema" [:ref "response"]}
    {"id" ["mc" "end"]
     "prompts" []
     "schema" [:ref "summary"]}]})

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
   Starts a code review FSM session with the provided code.
   
   Returns [context trail] tuple on success, or :timeout if timeout reached.
   
   Options:
     :timeout-ms - Timeout in milliseconds (default: 5 minutes)
     :llms - Vector of LLM configs to use (default: all available)
     :concerns - Vector of concerns to evaluate (default: example-code-review-concerns)"
  [& body]
  (let [code-str (pr-str (cons 'do body))]
    `(let [code-review-actions# {"llm" #'fsm/llm-action "end" #'actions/end-action}
           context# {:id->action code-review-actions#}
           {:keys [~'submit ~'await ~'stop]} (fsm/start-fsm context# code-review-fsm)
           ;; Available LLMs - must match schema enum exactly
           llms# [{"provider" "anthropic" "model" "claude-opus-4.5"}
                  {"provider" "google" "model" "gemini-3-pro-preview"}
                  {"provider" "openai" "model" "gpt-5.2-chat"}
                  {"provider" "x-ai" "model" "grok-code-fast-1"}]
           ;; Construct entry message with document and llms
           entry-msg# {"id" ["start" "mc"]
                       "document" (str "Please review this code: " ~code-str)
                       "llms" llms#
                       "concerns" example-code-review-concerns}]
       (try
         (~'submit entry-msg#)
         (~'await (* 5 60 1000))
         (finally
           (~'stop))))))
