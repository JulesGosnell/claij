(ns claij.fsm.triage-fsm
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [claij.schema :refer [def-fsm]]
   [claij.action :refer [def-action]]
   [claij.fsm :refer [start-fsm]]
   [claij.llm :refer [call]]
   [claij.store :as store]))

;;------------------------------------------------------------------------------
;; Triage Schema
;;------------------------------------------------------------------------------

(def triage-schemas
  "Schema definitions for triage FSM events.
   Uses JSON Schema format."
  {;; Entry event: start → triage
   "entry" {"type" "object"
            "additionalProperties" false
            "required" ["id" "document"]
            "properties"
            {"id" {"const" ["start" "triage"]}
             "document" {"type" "string"}}}

   ;; Reuse existing FSM
   "reuse" {"type" "object"
            "additionalProperties" false
            "required" ["id" "fsm-id" "fsm-version" "rationale"]
            "properties"
            {"id" {"const" ["triage" "reuse"]}
             "fsm-id" {"type" "string"}
             "fsm-version" {"type" "integer"}
             "rationale" {"type" "string"}}}

   ;; Fork existing FSM (not implemented)
   "fork" {"type" "object"
           "additionalProperties" false
           "required" ["id" "fsm-id" "fsm-version" "modifications"]
           "properties"
           {"id" {"const" ["triage" "fork"]}
            "fsm-id" {"type" "string"}
            "fsm-version" {"type" "integer"}
            "modifications" {"type" "string"}}}

   ;; Generate new FSM (not implemented)
   "generate" {"type" "object"
               "additionalProperties" false
               "required" ["id" "requirements"]
               "properties"
               {"id" {"const" ["triage" "generate"]}
                "requirements" {"type" "string"}}}

   ;; Result from delegated FSM
   "result" {"type" "object"
             "additionalProperties" false
             "required" ["id" "success"]
             "properties"
             {"id" {"enum" [["reuse" "end"] ["fork" "end"] ["generate" "end"]]}
              "success" {"type" "boolean"}
              "output" {}}}})

;;------------------------------------------------------------------------------
;; Triage Action
;;------------------------------------------------------------------------------

(def-action triage-action
  "Loads all FSMs from store and asks LLM to choose the best one.
   
   Queries the store for FSM metadata and presents them to the LLM for selection."
  {"type" "object"}
  [_config _fsm ix _state]
  (fn [context event _trail handler]
    ;; Extract user's problem description from the event
    (let [{:keys [store]} context
          service (:llm/service context)
          model (:llm/model context)
          user-text (get event "document")

          ;; Query store for all FSM [id, version, description] triples
          available-fsms (store/fsm-list-all store)

          _ (log/info (str "   Triage: " (count available-fsms) " FSMs available"))

          ;; Format FSM list for LLM
          fsm-list (if (empty? available-fsms)
                     "No FSMs currently available in the store."
                     (str/join "\n"
                               (map (fn [{id "id" version "version" description "description"}]
                                      (str "- FSM: " id " (v" version "): " description))
                                    available-fsms)))

          ;; Build prompt for LLM
          prompts [{"role" "user"
                    "content" (str "User's request: " user-text "\n\n"
                                   "Available FSMs:\n" fsm-list "\n\n"
                                   "Choose the best FSM to handle this request. "
                                   "For now, only 'reuse' is supported - select an existing FSM that matches the request.")}]]

      (if (empty? available-fsms)
        ;; No FSMs available - fail gracefully
        (do
          (log/error "   Triage: No FSMs in store")
          (handler context {"id" ["triage" "generate"]
                            "requirements" (str "No existing FSMs found. Need to implement: " user-text)}))
        ;; Query LLM for selection
        (call service model prompts (partial handler context) {:schema (get ix "schema")})))))

;;------------------------------------------------------------------------------
;; Triage FSM Definition
;;------------------------------------------------------------------------------

(def-fsm
  triage-fsm

  {"id" "triage"
   "schemas" triage-schemas
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
   - :store        - Database connection  
   - :llm/service  - LLM service (e.g. 'anthropic', 'ollama:local')
   - :llm/model    - LLM model (e.g. 'claude-sonnet-4-20250514')
   - :llm/registry - (Optional) Service registry
   - :id->action   - (Optional) Override default actions
   
   Returns a map with :submit, :await, :stop, :input-schema, :output-schema."
  [context]
  (start-fsm context triage-fsm))

(comment
  ;; Example usage:
  (require '[claij.actions :as actions])

  (let [context (actions/make-context
                 {:store nil ;; TODO: real store connection
                  :llm/service "openrouter"
                  :llm/model "openai/gpt-4o"})
        {:keys [submit await stop]} (start-triage context)]

    (submit "Please review my fibonacci code")

    ;; Wait for result
    (await 60000)

    (stop)))
