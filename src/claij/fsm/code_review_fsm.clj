(ns claij.fsm.code-review-fsm
  (:require
   [claij.schema :refer [def-fsm]]
   [claij.fsm :as fsm]
   [claij.actions :as actions]))

;;------------------------------------------------------------------------------
;; Code Review FSM Schema and Definition

(def code-review-schemas
  "Schema definitions for code review events.
   Uses JSON Schema format with $ref for references."
  {;; Code structure with language info
   "code" {"type" "object"
           "description" "Source code with programming language metadata"
           "additionalProperties" false
           "required" ["language" "text"]
           "properties"
           {"language" {"type" "object"
                        "description" "Programming language information"
                        "additionalProperties" false
                        "required" ["name"]
                        "properties"
                        {"name" {"type" "string"
                                 "description" "Language name (e.g. 'clojure', 'python', 'javascript')"}
                         "version" {"type" "string"
                                    "description" "Language version (e.g. '1.11', '3.12')"}}}
            "text" {"type" "string"
                    "description" "The actual source code"}}}

   ;; General notes field
   "notes" {"type" "string"
            "description" "General notes or observations about the code"}

   ;; List of specific issues
   "comments" {"type" "array"
               "description" "List of specific issues or suggestions"
               "items" {"type" "string"
                        "description" "A specific comment about an issue found"}}

   ;; List of concerns to review
   "concerns" {"type" "array"
               "description" "List of code quality concerns to focus on"
               "items" {"type" "string"
                        "description" "A specific concern to evaluate"}}

   ;; LLM specification
   "llm" {"type" "object"
          "description" "LLM service and model specification"
          "additionalProperties" false
          "required" ["service" "model"]
          "properties"
          {"service" {"type" "string"
                      "description" "The LLM service"
                      "enum" ["anthropic" "google" "openrouter" "ollama:local" "xai"]}
           "model" {"type" "string"
                    "description" "The specific model to use (native to service)"}}}

   ;; List of available LLMs (min 1)
   "llms" {"type" "array"
           "description" "List of available LLMs to choose from"
           "minItems" 1
           "items" {"$ref" "#/$defs/llm"}}

   ;; Entry event: start → chairman
   "entry" {"type" "object"
            "description" "Initial request to start a code review"
            "additionalProperties" false
            "required" ["id" "document" "llms" "concerns"]
            "properties"
            {"id" {"const" ["start" "chairman"]}
             "document" {"type" "string"
                         "description" "The code or document to review"}
             "llms" {"$ref" "#/$defs/llms"
                     "description" "Available LLMs for the review"}
             "concerns" {"$ref" "#/$defs/concerns"
                         "description" "Quality concerns to evaluate"}}}

   ;; Request event: chairman → reviewer
   "request" {"type" "object"
              "description" "Chairman's request to a reviewer for code analysis"
              "additionalProperties" false
              "required" ["id" "code" "notes" "concerns" "llm"]
              "properties"
              {"id" {"const" ["chairman" "reviewer"]}
               "code" {"$ref" "#/$defs/code"
                       "description" "The code to review"}
               "notes" {"$ref" "#/$defs/notes"
                        "description" "Context or instructions for the reviewer"}
               "concerns" {"type" "array"
                           "description" "Specific concerns for this review (max 3)"
                           "maxItems" 3
                           "items" {"type" "string"}}
               "llm" {"$ref" "#/$defs/llm"
                      "description" "Which LLM should perform this review"}}}

   ;; Response event: reviewer → chairman
   "response" {"type" "object"
               "description" "Reviewer's analysis and feedback"
               "additionalProperties" false
               "required" ["id" "code" "comments"]
               "properties"
               {"id" {"const" ["reviewer" "chairman"]}
                "code" {"$ref" "#/$defs/code"
                        "description" "The code (possibly modified with improvements)"}
                "notes" {"$ref" "#/$defs/notes"
                         "description" "General observations about the review"}
                "comments" {"$ref" "#/$defs/comments"
                            "description" "Specific issues or suggestions found"}}}

   ;; Summary event: chairman → end
   "summary" {"type" "object"
              "description" "Final summary after all reviews complete"
              "additionalProperties" false
              "required" ["id" "code" "notes"]
              "properties"
              {"id" {"const" ["chairman" "end"]}
               "code" {"$ref" "#/$defs/code"
                       "description" "The final reviewed code"}
               "notes" {"$ref" "#/$defs/notes"
                        "description" "Summary of the review process and findings"}}}})

(def-fsm
  code-review-fsm
  {"id" "code-review"
   "schemas" code-review-schemas
   "prompts" ["You are involved in a code review workflow"]
   "states"
   [{"id" "chairman"
     "action" "llm"
     "hats" ["mcp"]
     "prompts"
     ["You are the Chairman orchestrating a code review."
      "You have been provided with a list of code quality concerns and a list of available LLMs."
      "Your role is to distribute the concerns effectively across multiple LLM reviewers to ensure thorough code review."
      ""
      "TOOLS:"
      "- Use the MCP tools available to you to read files when needed"
      "- You can read source files to understand the code being reviewed"
      ""
      "CRITICAL - LLM SELECTION:"
      "- You MUST only use LLMs from the 'llms' list provided in the entry message"
      "- Copy the exact 'service' and 'model' strings from that list"
      "- DO NOT invent or guess model names - only use what was provided"
      "- If you use a model not in the list, the request will fail"
      ""
      "CONCERN DISTRIBUTION:"
      "- Review the provided concerns list carefully"
      "- Identify which concerns are most relevant to the code being reviewed"
      "- Distribute 2-3 relevant concerns to each LLM when requesting a review"
      "- You can assign different concerns to different LLMs based on their strengths"
      "- When you request a review, include the specific concerns in the 'concerns' field along with the 'llm' field specifying service and model"
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
      "The Chairman (coordinator) has selected these concerns as most relevant for this review."
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
   [{"id" ["start" "chairman"]
     "schema" {"$ref" "#/$defs/entry"}}
    {"id" ["chairman" "reviewer"]
     "prompts" []
     "schema" {"$ref" "#/$defs/request"}}
    {"id" ["reviewer" "chairman"]
     "prompts" []
     "schema" {"$ref" "#/$defs/response"}}
    {"id" ["chairman" "end"]
     "prompts" []
     "schema" {"$ref" "#/$defs/summary"}}]})

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
           ;; Context with default LLM for chairman
           context# {:id->action code-review-actions#
                     :llm/service "anthropic"
                     :llm/model "claude-sonnet-4-20250514"}
           {:keys [~'submit ~'await ~'stop]} (fsm/start-fsm context# code-review-fsm)
           ;; Available LLMs - must match schema enum exactly
           llms# [{"service" "anthropic" "model" "claude-sonnet-4-20250514"}
                  {"service" "openrouter" "model" "openai/gpt-4o"}
                  {"service" "xai" "model" "grok-3-beta"}]
           ;; Construct entry message with document and llms
           entry-msg# {"id" ["start" "chairman"]
                       "document" (str "Please review this code: " ~code-str)
                       "llms" llms#
                       "concerns" example-code-review-concerns}]
       (try
         (~'submit entry-msg#)
         (~'await (* 5 60 1000))
         (finally
           (~'stop))))))
