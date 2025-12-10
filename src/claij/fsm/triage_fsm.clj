(ns claij.fsm.triage-fsm
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [malli.registry :as mr]
   [claij.malli :refer [def-fsm base-registry]]
   [claij.action :refer [def-action]]
   [claij.fsm :refer [start-fsm]]
   [claij.llm :refer [call]]
   [claij.store :as store]))

;;------------------------------------------------------------------------------
;; Triage Schema
;;------------------------------------------------------------------------------

(def triage-schemas
  "Schema definitions for triage FSM events.
   Uses string keys for LLM JSON compatibility."
  {;; Entry event: start → triage
   "entry" [:map {:closed true}
            ["id" [:= ["start" "triage"]]]
            ["document" :string]]

   ;; Reuse existing FSM
   "reuse" [:map {:closed true}
            ["id" [:= ["triage" "reuse"]]]
            ["fsm-id" :string]
            ["fsm-version" :int]
            ["rationale" :string]]

   ;; Fork existing FSM (not implemented)
   "fork" [:map {:closed true}
           ["id" [:= ["triage" "fork"]]]
           ["fsm-id" :string]
           ["fsm-version" :int]
           ["modifications" :string]]

   ;; Generate new FSM (not implemented)
   "generate" [:map {:closed true}
               ["id" [:= ["triage" "generate"]]]
               ["requirements" :string]]

   ;; Result from delegated FSM
   "result" [:map {:closed true}
             ["id" [:enum ["reuse" "end"] ["fork" "end"] ["generate" "end"]]]
             ["success" :boolean]
             ["output" {:optional true} :any]]})

(def triage-registry
  "Malli registry for triage validation."
  (mr/composite-registry
   base-registry
   triage-schemas))

;;------------------------------------------------------------------------------
;; Triage Action
;;------------------------------------------------------------------------------

(def-action triage-action
  "Loads all FSMs from store and asks LLM to choose the best one.
   
   Queries the store for FSM metadata and presents them to the LLM for selection."
  [:map]
  [_config _fsm ix _state]
  (fn [context event _trail handler]
    ;; Extract user's problem description from the event
    (let [{:keys [store provider model]} context
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
        (call provider model prompts (partial handler context) {:schema (get ix "schema")})))))

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
     "schema" [:ref "entry"]}

    {"id" ["triage" "reuse"]
     "prompts" []
     "schema" [:ref "reuse"]}

    {"id" ["triage" "fork"]
     "prompts" []
     "schema" [:ref "fork"]}

    {"id" ["triage" "generate"]
     "prompts" []
     "schema" [:ref "generate"]}

    {"id" ["reuse" "end"]
     "prompts" []
     "schema" [:ref "result"]}

    {"id" ["fork" "end"]
     "prompts" []
     "schema" [:ref "result"]}

    {"id" ["generate" "end"]
     "prompts" []
     "schema" [:ref "result"]}]})

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
   
   Returns a map with :submit, :await, :stop, :input-schema, :output-schema."
  [context]
  (start-fsm context triage-fsm))

(comment
  ;; Example usage:
  (require '[claij.actions :as actions])

  (let [context (actions/make-context
                 {:store nil ;; TODO: real store connection
                  :provider "openai"
                  :model "gpt-4o"})
        {:keys [submit await stop]} (start-triage context)]

    (submit "Please review my fibonacci code")

    ;; Wait for result
    (await 60000)

    (stop)))
