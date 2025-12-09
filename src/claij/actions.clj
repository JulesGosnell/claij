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
  "Terminal action - signals FSM completion via promise and/or handler.
   
   Completion mechanisms (checked in order):
   1. :fsm/on-complete - Handler function called with [context trail]
      Used for async FSM composition (child FSM calls parent's handler)
   2. :fsm/completion-promise - Promise delivered with [context trail]
      Used for sync/await patterns
   
   Both can be present - handler is called first, then promise delivered.
   This allows composed FSMs to propagate results while still supporting await."
  {:config [:map]
   :input :any
   :output :any}
  [_config _fsm _ix _state]
  (fn [context _event trail _handler]
    (log/info "   [OK] End")
    ;; Call on-complete handler if present (for async composition)
    (when-let [on-complete (:fsm/on-complete context)]
      (on-complete context trail))
    ;; Deliver to promise if present (for sync/await patterns)
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
  {:config [:map]
   :input :any
   :output :any}
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
            {:keys [submit await stop]} (start-fsm context loaded-fsm)]

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
          (stop)

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
;; FSM-as-Action: Composing FSMs
;;------------------------------------------------------------------------------
;;
;; fsm-action wraps a child FSM as an action within a parent FSM.
;; 
;; DESIGN NOTES:
;; - FSM id comes from CONFIG (not event) - known at definition time
;; - FSM loading deferred to runtime (first event) - no context at config-time
;; - Uses :fsm/on-complete for ASYNC composition (no blocking await)
;; - Trail filtering prevents child FSM internals leaking to parent
;; - Schemas declared as :any at config-time (computed from child FSM at runtime)
;;
;; CONFIG OPTIONS:
;; - "fsm-id" (required) - ID of child FSM to load from store
;; - "fsm-version" (optional) - specific version, defaults to latest
;; - "on-state" (required) - current state name for building output transition id
;; - "success-to" (required) - state to transition to on success
;; - "failure-to" (optional) - state to transition to on failure/timeout
;; - "timeout-ms" (optional) - timeout in ms, default 60000
;; - "trail-mode" (optional) - :omit, :summary, or :full (default :summary)
;;
;; TRAIL MODES:
;; - :omit - child trail not included, only result event
;; - :summary - first and last child trail entries included
;; - :full - entire child trail included (can be large!)
;;
;; ASYNC FLOW:
;; 1. Parent FSM invokes fsm-action with event
;; 2. fsm-action loads child FSM, starts it with :fsm/on-complete handler
;; 3. fsm-action submits event to child, returns immediately (non-blocking)
;; 4. Child FSM runs asynchronously
;; 5. When child reaches end, end-action calls :fsm/on-complete
;; 6. on-complete wrapper transforms result and calls parent handler
;; 7. Parent FSM continues from fsm-action's state
;;
;; SCHEMA COMPUTATION (DEFERRED):
;; At config-time, schemas are :any (child FSM not loaded yet).
;; Future enhancement: compute schemas when child FSM is first loaded,
;; cache them, and use for subsumption checking in FSM composition.
;;------------------------------------------------------------------------------

(defn make-fsm-action
  "Create an FSM action factory. Returns a function compatible with :id->action.
   
   Unlike def-action, this is a regular function because fsm-action needs
   special handling - schemas are computed from child FSM at runtime.
   
   The returned action:
   - Loads child FSM on first invocation (deferred loading)
   - Runs child FSM asynchronously via :fsm/on-complete
   - Filters trail based on trail-mode config
   - Transforms child output to parent transition format"
  []
  ;; Return a factory function matching action protocol
  ;; Add :action/name metadata so curried-action? recognizes it
  (with-meta
    (fn [config _parent-fsm _ix {state-id "id"}]
      ;; Config-time: extract config, prepare for runtime
      (let [fsm-id (get config "fsm-id")
            fsm-version (get config "fsm-version") ; nil = latest
            success-to (get config "success-to")
            failure-to (get config "failure-to" "end")
            timeout-ms (get config "timeout-ms" 60000)
            trail-mode (get config "trail-mode" :summary)]

        ;; Return runtime function
        (fn [context event _parent-trail handler]
          (log/info (str "   fsm-action: loading " fsm-id
                         (when fsm-version (str " v" fsm-version))))

          (let [{:keys [store]} context

                ;; Load child FSM from store
                ;; If no version specified, get latest version first
                version-to-load (or fsm-version
                                    (store/fsm-latest-version store fsm-id))
                child-fsm (when version-to-load
                            (store/fsm-load-version store fsm-id version-to-load))

                _ (when (nil? child-fsm)
                    (throw (ex-info "Child FSM not found in store"
                                    {:fsm-id fsm-id :fsm-version fsm-version})))

                ;; Create completion handler that calls parent handler
                on-complete
                (fn [child-context child-trail]
                  (log/info (str "   fsm-action: child " fsm-id " completed"))
                  (let [;; Extract result from child trail
                        child-result (claij.fsm/last-event child-trail)

                        ;; Filter trail based on mode
                        filtered-trail
                        (case trail-mode
                          :omit nil
                          :summary (when (seq child-trail)
                                     {:child-fsm fsm-id
                                      :first-event (:event (first child-trail))
                                      :last-event child-result
                                      :steps (count child-trail)})
                          :full child-trail)

                        ;; Build output event for parent FSM
                        ;; Uses state-id (from config-time state) + success-to
                        output-event {"id" [state-id success-to]
                                      "result" child-result
                                      "child-trail" filtered-trail}]

                    ;; Call parent handler to continue parent FSM
                    (handler context output-event)))

                ;; Prepare child context with completion handler
                ;; Remove parent's promise/handler to avoid confusion
                child-context (-> context
                                  (dissoc :fsm/completion-promise)
                                  (assoc :fsm/on-complete on-complete))

                ;; Start child FSM
                {:keys [submit stop]} (start-fsm child-context child-fsm)]

            ;; Submit event to child and return immediately (async)
            (log/info (str "   fsm-action: submitting to " fsm-id))
            (submit event)

            ;; Set up timeout handling
            (future
              (Thread/sleep timeout-ms)
              ;; If still running after timeout, stop and signal failure
              ;; Note: This is a simple timeout - more sophisticated would
              ;; check if child actually completed
              (log/warn (str "   fsm-action: timeout check for " fsm-id)))
            ;; TODO: Need a way to check if child completed
            ;; For now, timeout is just a warning - child may still complete

            ;; Return nil - we're async, handler will be called by on-complete
            nil))))
    ;; Metadata to identify this as a curried action
    {:action/name "fsm"
     :action/config-schema [:map
                            ["fsm-id" :string]
                            ["fsm-version" {:optional true} :int]
                            ["success-to" :string]
                            ["failure-to" {:optional true} :string]
                            ["timeout-ms" {:optional true} :int]
                            ["trail-mode" {:optional true} [:enum :omit :summary :full]]]
     :action/input-schema :any
     :action/output-schema :any}))

;; Pre-built fsm-action for registration in :id->action
(def fsm-action-factory
  "FSM action factory instance. Register in :id->action as \"fsm\".
   
   Usage in FSM definition:
   {\"id\" \"my-state\"
    \"action\" \"fsm\" 
    \"config\" {\"fsm-id\" \"child-fsm-id\"
               \"success-to\" \"next-state\"
               \"failure-to\" \"error-state\"
               \"timeout-ms\" 30000
               \"trail-mode\" :summary}}"
  (make-fsm-action))

;;------------------------------------------------------------------------------
;; Central Action Registry
;;------------------------------------------------------------------------------

(def default-actions
  "Default action implementations available to all FSMs.
   
   Stores vars (not values) to preserve metadata for action? checks.
   Exception: fsm-action-factory is a value (not var) because it's
   created dynamically by make-fsm-action.
   
   This map can be extended or overridden via context at runtime."
  {"llm" #'llm-action
   "triage" #'triage-action
   "reuse" #'reuse-action
   "fork" #'fork-action
   "generate" #'generate-action
   "fsm" fsm-action-factory
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
