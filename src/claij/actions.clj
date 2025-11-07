(ns claij.actions
  (:require
   [clojure.tools.logging :as log]
   [claij.llm.open-router :refer [open-router-async]]
   [claij.fsm :refer [start-fsm]]
   [claij.store :as store]))

;;------------------------------------------------------------------------------
;; Action Implementations
;;------------------------------------------------------------------------------

(defn llm-action
  "Standard LLM action - constructs prompts from FSM/state/trail and calls LLM.
   
   Takes context containing :provider and :model for LLM configuration."
  [{:keys [provider model]} fsm ix state trail handler]
  (let [{fsm-schema "schema" fsm-prompts "prompts"} fsm
        {ix-prompts "prompts"} ix
        {state-prompts "prompts"} state

        ;; Build prompt from FSM, transition, and state prompts
        system-prompts (concat (or fsm-prompts [])
                               (or ix-prompts [])
                               (or state-prompts []))

        ;; Extract user messages from trail
        user-messages (mapv (fn [{role "role" [_ _ output-schema] "content"}]
                              {"role" role
                               "content" (str output-schema)})
                            trail)

        ;; Combine system and user prompts
        all-prompts (concat
                     (map (fn [p] {"role" "system" "content" p}) system-prompts)
                     user-messages)

        schema (get ix "schema")]

    (open-router-async provider model all-prompts handler {:schema schema})))

(defn triage-action
  "Loads all FSMs from store and asks LLM to choose the best one.
   
   Queries the store for FSM metadata and presents them to the LLM for selection."
  [{:keys [store provider model]} fsm ix state trail handler]
  ;; Extract user's problem description from the trail
  (let [[{[_input-schema input-data _output-schema] "content"}] trail
        user-text (get input-data "document")

        ;; Query store for all FSM [id, version, description] triples
        available-fsms (store/fsm-list-all store)

        _ (log/info (str "   Triage: " (count available-fsms) " FSMs available"))

        ;; Format FSM list for LLM
        fsm-list (if (empty? available-fsms)
                   "No FSMs currently available in the store."
                   (clojure.string/join "\n"
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
        (handler {"id" ["triage" "generate"]
                  "requirements" (str "No existing FSMs found. Need to implement: " user-text)}))
      ;; Query LLM for selection
      (open-router-async provider model prompts handler {:schema (get ix "schema")}))))

(defn reuse-action
  "Loads the chosen FSM, starts it, delegates the user's text, waits for result.
   
   Creates a child context with a custom end action to capture the result."
  [context fsm ix state trail handler]
  ;; Extract the chosen FSM info and original user text
  (let [[{[_input-schema input-data _output-schema] "content"} original-request] trail
        fsm-id (get input-data "fsm-id")
        fsm-version (get input-data "fsm-version")
        original-text (get-in original-request ["content" 1 "document"])

        {:keys [store]} context]

    (log/info (str "   Reuse: " fsm-id " v" fsm-version))

    ;; Load FSM from store
    (let [loaded-fsm (store/fsm-load-version store fsm-id fsm-version)
          _ (when (nil? loaded-fsm)
              (throw (ex-info "FSM not found in store"
                              {:fsm-id fsm-id :fsm-version fsm-version})))

          ;; Create a promise to capture the child FSM's result
          child-result (promise)

          ;; Create child context with result-promise bound
          child-context (assoc context :result-promise child-result)

          ;; Start the child FSM
          [submit stop-fsm] (start-fsm child-context loaded-fsm)]

      ;; Submit the original user text to the child FSM
      (log/info (str "   [>>] Submitting to child FSM"))
      (submit original-text)

      ;; Wait for the child FSM to complete
      (let [result (deref child-result 60000 ::timeout)]
        (stop-fsm)

        (if (= result ::timeout)
          (do
            (log/error "   [X] Child FSM timeout")
            (handler {"id" ["reuse" "end"]
                      "success" false
                      "output" "Child FSM execution timed out"}))
          (do
            (log/info "   [OK] Child FSM complete")
            (handler {"id" ["reuse" "end"]
                      "success" true
                      "output" result})))))))

(defn fork-action
  "Placeholder for FSM forking (NOT IMPLEMENTED)"
  [context fsm ix state trail handler]
  (log/warn "   Fork: NOT IMPLEMENTED - this is a placeholder")
  (handler {"id" ["fork" "end"]
            "success" false
            "output" "FSM forking is not yet implemented"}))

(defn generate-action
  "Placeholder for FSM generation (NOT IMPLEMENTED)"
  [context fsm ix state trail handler]
  (log/warn "   Generate: NOT IMPLEMENTED - this is a placeholder")
  (handler {"id" ["generate" "end"]
            "success" false
            "output" "FSM generation is not yet implemented"}))

(defn end-action
  "Terminal action - delivers result to a promise from context.
   
   The result-promise should be bound in context under :result-promise key.
   This allows FSM delegation where child FSMs can have custom end actions."
  [{:keys [result-promise]} fsm ix state trail handler]
  (let [[{[_input-schema input-data _output-schema] "content"}] trail]
    (log/info "   [OK] End")
    (when result-promise
      (deliver result-promise input-data))
    ;; Call handler anyway to allow FSM to complete properly
    (when handler
      (handler input-data))))

;;------------------------------------------------------------------------------
;; Central Action Registry
;;------------------------------------------------------------------------------

(def default-actions
  "Default action implementations available to all FSMs.
   
   This map can be extended or overridden via context at runtime."
  {"llm" llm-action
   "triage" triage-action
   "reuse" reuse-action
   "fork" fork-action
   "generate" generate-action
   "end" end-action})

;;------------------------------------------------------------------------------
;; Context Helpers
;;------------------------------------------------------------------------------

(defn make-context
  "Create a context for FSM execution.
   
   Required keys:
   - :store      - Database connection
   - :provider   - LLM provider (e.g. 'openai')
   - :model      - LLM model (e.g. 'gpt-4o')
   
   Optional keys:
   - :id->action      - Override action implementations (defaults to default-actions)
   - :result-promise  - Promise to deliver final result to (used by end-action)"
  [{:keys [store provider model id->action result-promise] :as opts}]
  (merge {:id->action default-actions}
         opts))
