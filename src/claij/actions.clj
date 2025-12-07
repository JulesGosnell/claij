(ns claij.actions
  (:require
   [clojure.tools.logging :as log]
   [malli.core :as m]
   [claij.action :refer [def-action action? action-name action-config-schema]]
   [claij.llm.open-router :refer [open-router-async]]
   [claij.fsm :refer [start-fsm llm-action]]
   [claij.store :as store]))

;;------------------------------------------------------------------------------
;; Action Implementations
;;------------------------------------------------------------------------------

(def-action end-action
  "Terminal action - delivers result to completion promise from context.
   
   Checks for :fsm/completion-promise in context (new API) and delivers [context trail].
   This is called automatically by the FSM when reaching an end state."
  [:map]
  [_config _fsm _ix _state]
  (fn [context _event trail _handler]
    (log/info "   [OK] End")
    (when-let [p (:fsm/completion-promise context)]
      (deliver p [context trail]))))

(def-action fork-action
  "Placeholder for FSM forking (NOT IMPLEMENTED)"
  [:map]
  [_config _fsm _ix _state]
  (fn [context _event _trail handler]
    (log/warn "   Fork: NOT IMPLEMENTED - this is a placeholder")
    (handler context {"id" ["fork" "end"]
                      "success" false
                      "output" "FSM forking is not yet implemented"})))

(def-action generate-action
  "Placeholder for FSM generation (NOT IMPLEMENTED)"
  [:map]
  [_config _fsm _ix _state]
  (fn [context _event _trail handler]
    (log/warn "   Generate: NOT IMPLEMENTED - this is a placeholder")
    (handler context {"id" ["generate" "end"]
                      "success" false
                      "output" "FSM generation is not yet implemented"})))

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
          (handler context {"id" ["triage" "generate"]
                            "requirements" (str "No existing FSMs found. Need to implement: " user-text)}))
        ;; Query LLM for selection
        (open-router-async provider model prompts (partial handler context) {:schema (get ix "schema")})))))

(def-action reuse-action
  "Loads the chosen FSM, starts it, delegates the user's text, waits for result.
   
   Creates a child context with a custom end action to capture the result."
  [:map]
  [_config _fsm _ix _state]
  (fn [context event trail handler]
    ;; Extract the chosen FSM info from event, original text from trail
    (let [fsm-id (get event "fsm-id")
          fsm-version (get event "fsm-version")
          ;; Get original text from the first trail entry if available
          original-text (when (seq trail)
                          (get-in (first trail) ["content" 1 "document"]))

          {:keys [store provider model]} context]

      (log/info (str "   Reuse: " fsm-id " v" fsm-version))

      ;; Load FSM from store
      (let [loaded-fsm (store/fsm-load-version store fsm-id fsm-version)
            _ (when (nil? loaded-fsm)
                (throw (ex-info "FSM not found in store"
                                {:fsm-id fsm-id :fsm-version fsm-version})))

            ;; Start the child FSM with new API
            [submit await stop-fsm] (start-fsm context loaded-fsm)]

        ;; Construct entry message based on FSM requirements
        ;; For code-review FSM, include llms list and default concerns
        (let [entry-msg (if (= fsm-id "code-review")
                          {"id" ["start" "mc"]
                           "document" original-text
                           "llms" [{"provider" provider "model" model}]
                           "concerns" ["Simplicity: Can this be simpler?"
                                       "Performance: Consider efficiency and avoid reflection"
                                       "Functional style: Use pure functions and immutable data"
                                       "Idiomatic Clojure: Follow language conventions"
                                       "Clarity: Code should be easy to understand"]}
                          original-text)]

          (log/info (str "   [>>] Submitting to child FSM"))
          (submit entry-msg))

        ;; Wait for the child FSM to complete using new await API
        (let [result (await 60000)]
          (stop-fsm)

          (if (= result :timeout)
            (do
              (log/error "   [X] Child FSM timeout")
              (handler context {"id" ["reuse" "end"]
                                "success" false
                                "output" "Child FSM execution timed out"}))
            ;; Extract just the final event from child's trail to avoid deep nesting
            (let [[child-context child-trail] result
                  child-result (claij.fsm/last-event child-trail)]
              (log/info "   [OK] Child FSM complete")
              (handler context {"id" ["reuse" "end"]
                                "success" true
                                "output" child-result}))))))))

;;------------------------------------------------------------------------------
;; Central Action Registry
;;------------------------------------------------------------------------------

(def default-actions
  "Default action implementations available to all FSMs.
   
   Stores vars (not values) to preserve metadata for action? checks.
   This map can be extended or overridden via context at runtime."
  {"llm" #'llm-action
   "triage" #'triage-action
   "reuse" #'reuse-action
   "fork" #'fork-action
   "generate" #'generate-action
   "end" #'end-action})

;;------------------------------------------------------------------------------
;; Context Helpers
;;------------------------------------------------------------------------------

(defn with-actions
  "Add actions to a context, merging with any existing actions."
  [context actions]
  (update context :id->action merge actions))

(defn with-default-actions
  "Add the default actions to a context."
  [context]
  (with-actions context default-actions))

(defn make-context
  "Create a context for FSM execution.
   
   Required keys:
   - :store      - Database connection
   - :provider   - LLM provider (e.g. 'openai')
   - :model      - LLM model (e.g. 'gpt-4o')
   
   Optional keys:
   - :id->action      - Override action implementations (defaults to default-actions)"
  [opts]
  (merge {:id->action default-actions}
         opts))
