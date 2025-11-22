(ns claij.triage
  (:require
   [claij.util :refer [def-m2]]
   [claij.fsm :refer [def-fsm start-fsm]]
   [claij.actions :as actions]))

;;------------------------------------------------------------------------------
;; Triage Schema
;;------------------------------------------------------------------------------

(def-m2
  triage-schema

  {"$schema" "https://json-schema.org/draft/2020-12/schema"
   "$$id" "https://claij.org/schemas/triage-schema"
   "$version" 0

   "description" "Meta-FSM that triages user requests to appropriate FSMs"

   "type" "object"

   "$defs"
   {"entry"
    {"description" "User's plain English problem description"
     "type" "object"
     "properties"
     {"id" {"const" ["start" "triage"]}
      "document" {"type" "string"}}
     "additionalProperties" false
     "required" ["id" "document"]}

    "reuse"
    {"description" "Decision to reuse an existing FSM"
     "type" "object"
     "properties"
     {"id" {"const" ["triage" "reuse"]}
      "fsm-id" {"type" "string"}
      "fsm-version" {"type" "integer"}
      "rationale" {"type" "string"
                   "description" "Why this FSM was chosen"}}
     "additionalProperties" false
     "required" ["id" "fsm-id" "fsm-version" "rationale"]}

    "fork"
    {"description" "Decision to fork an existing FSM (NOT IMPLEMENTED YET)"
     "type" "object"
     "properties"
     {"id" {"const" ["triage" "fork"]}
      "fsm-id" {"type" "string"}
      "fsm-version" {"type" "integer"}
      "modifications" {"type" "string"
                       "description" "How to modify the FSM"}}
     "additionalProperties" false
     "required" ["id" "fsm-id" "fsm-version" "modifications"]}

    "generate"
    {"description" "Decision to generate a new FSM (NOT IMPLEMENTED YET)"
     "type" "object"
     "properties"
     {"id" {"const" ["triage" "generate"]}
      "requirements" {"type" "string"
                      "description" "Requirements for the new FSM"}}
     "additionalProperties" false
     "required" ["id" "requirements"]}

    "result"
    {"description" "Result from delegated FSM execution"
     "type" "object"
     "properties"
     {"id" {"enum" [["reuse" "end"]
                    ["fork" "end"]
                    ["generate" "end"]]}
      "success" {"type" "boolean"}
      "output" {"description" "Output from the delegated FSM"}}
     "additionalProperties" false
     "required" ["id" "success"]}}})

;;------------------------------------------------------------------------------
;; Triage FSM Definition
;;------------------------------------------------------------------------------

(def-fsm
  triage-fsm

  {"schema" triage-schema
   "id" "triage"
   "prompts" ["You are a triage system that routes user requests to appropriate FSMs"]

   "states"
   [{"id" "triage"
     "action" "triage"
     "prompts"
     ["You are analyzing a user's request to determine which FSM should handle it."
      "You will be given a list of available FSMs with their descriptions."
      "Choose the most appropriate option:"
      "- 'reuse': Use an existing FSM as-is if it matches the request well"
      "- 'fork': Modify an existing FSM (NOT YET IMPLEMENTED - do not choose this)"
      "- 'generate': Create a new FSM from scratch (NOT YET IMPLEMENTED - do not choose this)"
      "For now, ONLY choose 'reuse' and select the best matching FSM."]}

    {"id" "reuse"
     "action" "reuse"
     "prompts" []}

    {"id" "fork"
     "action" "fork"
     "prompts"
     ["FSM forking is not yet implemented. This is a placeholder."]}

    {"id" "generate"
     "action" "generate"
     "prompts"
     ["FSM generation is not yet implemented. This is a placeholder."]}

    {"id" "end"
     "action" "end"}]

   "xitions"
   [{"id" ["start" "triage"]
     "schema" {"$ref" "#/$defs/entry"}}

    {"id" ["triage" "reuse"]
     "prompts" []
     "schema" {"$ref" "#/$defs/reuse"}}

    {"id" ["triage" "fork"]
     "prompts" []
     "schema" {"$ref" "#/$defs/fork"}}

    {"id" ["triage" "generate"]
     "prompts" []
     "schema" {"$ref" "#/$defs/generate"}}

    {"id" ["reuse" "end"]
     "prompts" []
     "schema" {"$ref" "#/$defs/result"}}

    {"id" ["fork" "end"]
     "prompts" []
     "schema" {"$ref" "#/$defs/result"}}

    {"id" ["generate" "end"]
     "prompts" []
     "schema" {"$ref" "#/$defs/result"}}]})

;;------------------------------------------------------------------------------
;; Public API
;;------------------------------------------------------------------------------

(defn start-triage
  "Start the triage FSM with the given context.
   
   Context should contain:
   - :store      - Database connection  
   - :provider   - LLM provider (e.g. 'openai')
   - :model      - LLM model (e.g. 'gpt-4o')
   - :id->action - (Optional) Override default actions
   
   Returns [submit await stop-fsm] functions."
  [context]
  (start-fsm context triage-fsm))

(comment
  ;; Example usage:
  (require '[claij.actions :as actions])

  (let [context (actions/make-context
                 {:store nil ;; TODO: real store connection
                  :provider "openai"
                  :model "gpt-4o"})
        [submit await stop-fsm] (start-triage context)
        result (promise)]

    (submit "Please review my fibonacci code")

    ;; Wait for result
    @result

    (stop-fsm)))
