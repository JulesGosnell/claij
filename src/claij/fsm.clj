(ns claij.fsm
  (:require
   [clojure.string :refer [join]]
   [clojure.tools.logging :as log]
   [clojure.core.async :refer [chan go-loop alts! >!! close!]]
   [malli.core :as m]
   [malli.error :as me]
   [malli.registry :as mr]
   [claij.util :refer [index-by ->key map-values make-retrier]]
   [claij.malli :refer [def-fsm fsm-registry base-registry expand-refs-for-llm]]
   [claij.action :refer [def-action action-input-schema action-output-schema]]
   [claij.llm :refer [call]]
   [claij.hat :as hat]))

;;------------------------------------------------------------------------------
;; Action Dispatch Helpers
;;------------------------------------------------------------------------------

(defn- curried-action?
  "Check if an action is a curried action (has :action/name metadata).
   Works with both vars (from def-action) and functions with metadata
   (from make-fsm-action and similar dynamic factories).
   Returns false for legacy 7-arg actions."
  [action-or-var]
  (boolean (-> action-or-var meta :action/name)))

(defn- invoke-action
  "Invoke an action, handling both curried and legacy signatures.
   
   Curried actions (def-action or functions with :action/name metadata):
     (action config fsm ix state) -> f2
     (f2 context event trail handler)
   
   Legacy actions (defn):
     (action context fsm ix state event trail handler)
   
   DEPRECATED: Legacy 7-arg actions will be removed in a future version.
   Use def-action from claij.action namespace instead."
  [action-name action-or-var context fsm ix state event trail handler]
  (if (curried-action? action-or-var)
    ;; Curried: get the action function, call factory, then call f2
    ;; Handle both vars (from def-action) and direct functions (from make-fsm-action)
    (let [action (if (var? action-or-var) @action-or-var action-or-var)
          config (get state "config" {})
          f2 (action config fsm ix state)]
      (f2 context event trail handler))
    ;; Legacy: call directly with all 7 args
    (do
      (log/warn (str "DEPRECATED: Legacy 7-arg action '" action-name "'. "
                     "Migrate to def-action from claij.action namespace."))
      (let [action (if (var? action-or-var) @action-or-var action-or-var)]
        (action context fsm ix state event trail handler)))))

;; the plan
;; to transition we need a json document - a "proposal"
;; this either comes from a request (us->llm) or a response (llm->us)
;; the fsm is direction agnostic
;; each xition carries a schema
;; the document will be validated against one of these schemas (Malli :or)
;; it will make the xition of which it validates against the schema
;; when making a request, we will send the :or of xition schemas from our state
;; the llm will make a response that conforms to the xition it wants to make
;; if it validates, we will make the xition, otherwise we go back to the llm til it gets it right

;; a transition ensures that you hve all the correct parameter for the following state
;; the state is one of:
;; -  human
;; - an llm
;; - an mcp service
;; - a repl
;; - a yet-to-be-invented extension
;; the state takes its input and provides an output

;; thoughts
;; - if the action is going to cause a request to kick off to an llvm then we need to pass in everything that it might need...
;; - fsm

;;------------------------------------------------------------------------------
;; FSM Registry Building
;;------------------------------------------------------------------------------

(defn build-fsm-registry
  "Build a composite Malli registry for an FSM.
   
   Combines (in priority order, later overrides earlier):
   1. base-registry - Default Malli schemas + CLAIJ base types
   2. FSM schemas - Types defined in fsm[\"schemas\"]
   3. Context registry - Domain-specific schemas from context[:malli/registry]
   
   This should be called once at FSM start and stored in context.
   
   Args:
     fsm - The FSM definition (may have \"schemas\" field)
     context - Optional context with :malli/registry for additional schemas
   
   Returns:
     A composite Malli registry for schema resolution"
  ([fsm] (build-fsm-registry fsm {}))
  ([fsm context]
   (let [fsm-schemas (get fsm "schemas")
         context-registry (get context :malli/registry)]
     (mr/composite-registry
      base-registry
      (or fsm-schemas {})
      (or context-registry {})))))

;;------------------------------------------------------------------------------
;; Malli Event Validation
;;------------------------------------------------------------------------------

(defn validate-event
  "Validate an event against a Malli schema with registry support.
   
   Args:
     registry - Malli registry (composite or map) for resolving refs
     schema   - The schema to validate against (may contain [:ref \"key\"])
     event    - The event data to validate
   
   Returns:
     {:valid? true} or {:valid? false :errors [humanized-errors]}"
  [registry schema event]
  (let [opts (when registry {:registry registry})]
    (try
      (if (m/validate schema event opts)
        {:valid? true}
        {:valid? false
         :errors (me/humanize (m/explain schema event opts))})
      (catch Exception e
        {:valid? false
         :errors [(str "Schema validation error: " schema event (.getMessage e))]}))))

;;------------------------------------------------------------------------------
;; Schema Utilities
;;------------------------------------------------------------------------------

;;------------------------------------------------------------------------------
;; State→Action Schema Bridge (Story #62)
;;------------------------------------------------------------------------------
;;
;; These functions bridge from FSM states to action schemas, enabling
;; schema propagation from action metadata up to transition validation.
;;
;; Flow: state["action"] → context[:id->action] → action-var → metadata
;;
;; NOTE: These must be defined BEFORE resolve-schema since it uses them.
;;------------------------------------------------------------------------------

(defn state-action
  "Get the action var/fn for a state from context.
   Returns nil if action not found."
  [context {action-name "action" :as _state}]
  (when action-name
    (get-in context [:id->action action-name])))

(defn state-action-input-schema
  "Get the input schema for a state's action.
   
   Looks up the action in context and extracts :action/input-schema from metadata.
   Returns :any if:
   - State has no action
   - Action not found in context
   - Action has no input schema declared
   
   Works at all three times (config/start/runtime) - same code path."
  [context state]
  (if-let [action (state-action context state)]
    (action-input-schema action)
    :any))

(defn state-action-output-schema
  "Get the output schema for a state's action.
   
   Looks up the action in context and extracts :action/output-schema from metadata.
   Returns :any if:
   - State has no action  
   - Action not found in context
   - Action has no output schema declared
   
   Works at all three times (config/start/runtime) - same code path."
  [context state]
  (if-let [action (state-action context state)]
    (action-output-schema action)
    :any))

(defn resolve-schema
  "Resolve a transition schema, supporting dynamic schema generation and fallback.
   
   Resolution order:
   1. If schema is a string, looks up (get-in context [:id->schema schema-key])
      to get a schema function, then calls (schema-fn context xition).
   2. If schema is nil and state+direction provided, falls back to action schema.
   3. Otherwise returns schema unchanged (or true if nil with no fallback).
   
   Parameters:
   - context: FSM context with :id->schema and :id->action
   - xition: The transition being resolved
   - schema: The schema to resolve (string, nil, or Malli schema)
   - state: (optional) State for action schema fallback
   - direction: (optional) :input or :output for fallback direction
   
   NOTE: Future optimization opportunity - when we have the event and the
   resolved schema is a Malli :or, we could narrow it further based on
   event content. Currently we only narrow to the selected transition,
   not within a transition's schema."
  ([context xition schema]
   (resolve-schema context xition schema nil nil))
  ([context xition schema state direction]
   (cond
     ;; String schema -> dynamic lookup
     (string? schema)
     (if-let [schema-fn (get-in context [:id->schema schema])]
       (schema-fn context xition)
       (do
         (log/warn "No schema function found for key:" schema)
         true))

     ;; Nil schema with state+direction -> fallback to action schema
     (and (nil? schema) state direction)
     (case direction
       :input (state-action-input-schema context state)
       :output (state-action-output-schema context state)
       :any)

     ;; Nil schema without fallback -> permissive
     (nil? schema)
     true

     ;; Everything else (Malli schema) -> return as-is
     :else
     schema)))

(defn state-schema
  "Make the schema for a state - to be valid for a state, you must be valid for one (only) of its output xitions.
   Resolves dynamic schemas via context :id->schema lookup.
   Returns a Malli :or schema."
  [context {fid "id" fv "version" fs "schema" :as _fsm} {sid "id" :as _state} xs]
  (into [:or]
        (mapv (fn [{s "schema" :as xition}]
                (resolve-schema context xition s))
              xs)))

(defn xform [context {fsm-schema "schema" :as fsm} {[from to] "id" ix-schema "schema" ix-omit? "omit" :as ix} {a "action" :as state} ox-and-cs event trail]
  (try
    (log/info (str "=> [" from " -> " to "]"))

    (let [s-schema (state-schema context fsm state (map first ox-and-cs))
          id->x-and-c (index-by (comp (->key "id") first) ox-and-cs)
          retry-count (atom 0)
          max-retries 3
          retrier (make-retrier max-retries)

          ;; Handler validates output and retries on failure
          handler
          (fn handler
            ([new-context output-event] (handler new-context output-event trail))
            ([new-context {ox-id "id" :as output-event} current-trail]
             (try
               (let [[{ox-schema-raw "schema" :as ox} c] (id->x-and-c ox-id)
                     _ (when (nil? ox) (log/error "no output xition:" (prn-str ox-id)))
                     ox-schema (resolve-schema new-context ox ox-schema-raw)
                     ;; Use pre-built registry from context (built in start-fsm)
                     registry (get new-context :malli/registry)
                     ;; Use Malli validation with combined registry
                     {v? :valid? es :errors}
                     (validate-event registry ox-schema output-event)]
                 (if v?
                     ;; SUCCESS - Put context, event, and trail on channel
                   (do
                     (log/info (str "   [OK] Validation: " ox-id))
                     (let [;; One entry per transition: {:from :to :event}
                           ;; Record the OUTPUT transition - event validates against ox-schema
                           ;; ox-id = [current-state, next-state]
                           ;; Check omit on OUTPUT transition (ox), not input (ix)
                           [ox-from ox-to] ox-id
                           ox-omit? (get ox "omit")
                           new-trail (if ox-omit?
                                       (vec current-trail)
                                       (conj (vec current-trail)
                                             {:from ox-from
                                              :to ox-to
                                              :event output-event}))]
                       (>!! c {:context new-context
                               :event output-event
                               :trail new-trail}))
                     nil)
                     ;; FAILURE - Retry with error feedback
                   (retrier
                    @retry-count
                       ;; Retry operation
                    (fn []
                      (log/error (str "   [X] Validation Failed: " ox-id es " (attempt " (inc @retry-count) "/" max-retries ")"))
                      (swap! retry-count inc)
                      (let [error-msg (str "Your response failed schema validation.\n\n"
                                           "Validation errors: " (pr-str es) "\n\n"
                                           "Please review the schema and provide a corrected response.")

;; Error entry: records the failed attempt
                            ;; Use ox-id since that's the transition we tried to make
                            [err-from err-to] ox-id
                            error-trail (conj (vec current-trail)
                                              {:from err-from
                                               :to err-to
                                               :event output-event
                                               :error {:message error-msg
                                                       :errors es
                                                       :attempt @retry-count}})]
                        (log/info "      [>>] Sending validation error feedback to LLM")
                           ;; Call action again with error in trail
                        (if-let [action (get-in context [:id->action a])]
                          (invoke-action a action new-context fsm ix state event error-trail handler)
                          (handler new-context (get-in (peek error-trail) [:error :message]) error-trail))))
                       ;; Max retries exceeded
                    (fn []
                      (log/error (str "   [X] Max Retries Exceeded: Validation failed after " max-retries " attempts"))
                      (log/error (str "   Final output: " (pr-str output-event)))
                      (log/debug (str "   Validation errors: " (pr-str es)))))))
               (catch Throwable t
                 (log/error t "Error in handler processing")))))]

      ;; Call action with event and trail separately
      (if-let [action (get-in context [:id->action a])]
        (do
          (log/info (str "   Action: " a))
          (invoke-action a action context fsm ix state event trail handler))
        (handler context event)))
    (catch Throwable t
      (log/error t "Error in xform"))))

;; TODO: FSM Structure Validation
;; =============================
;; FSM structural validation should happen at load/definition time (in def-fsm macro),
;; not at runtime in start-fsm. Required validations:
;;
;; 1. FSM must contain "start" and "end" states in the states array
;; 2. There must be at least one transition from "start" (entry point)
;; 3. There must be at least one transition to "end" (exit point)
;; 4. All transition "id" components must reference valid state IDs
;; 5. All states must be reachable (graph connectivity check)
;; 6. No orphaned states (states with no incoming or outgoing transitions)
;;
;; This validation should be part of the def-fsm macro expansion or a separate
;; validate-fsm-structure function called during FSM definition.

;; TODO: Multiple Start Transitions
;; =================================
;; Current implementation assumes exactly one transition from "start".
;; However, FSMs may have multiple start transitions for different entry points:
;;   ["start" "initial-review"]
;;   ["start" "emergency-fix"]
;;   ["start" "automated-scan"]
;;
;; Potential solutions:
;; 1. Require explicit start state parameter: (start-fsm actions fsm "initial-review")
;; 2. Support named entry points with multiple submit functions
;; 3. Default to first transition, allow override
;; 4. Make it an error - FSM must have exactly one start transition
;;
;; Decision needed on the desired semantics.

(defn fsm-schemas
  "Extract input and output schemas from an FSM definition without starting it.
   
   This is a pure function for design-time analysis - use it for:
   - FSM composition and type checking with subsumes?
   - Validating FSM compatibility before wiring them together
   - Static analysis of FSM interfaces
   
   Context is optional but needed for dynamic schema resolution:
   - :id->schema - Map of schema-key to schema-fn for dynamic schemas
   - :malli/registry - Additional Malli schemas
   
   Returns:
   - :input-schema - Malli :or schema of all transitions FROM 'start'
   - :output-schema - Malli :or schema of all transitions TO 'end'
   
   Usage:
     (let [{:keys [input-schema output-schema]} (fsm-schemas {} my-fsm)]
       (when-not (subsumes? next-fsm-input output-schema)
         (throw (ex-info \"FSM output incompatible with next FSM input\" {}))))"
  ([fsm] (fsm-schemas {} fsm))
  ([context {xs "xitions" :as fsm}]
   (let [;; Group transitions by source and destination
         sid->ox (group-by (comp first (->key "id")) xs)
         sid->ix (group-by (comp second (->key "id")) xs)

         ;; Compute input-schema: :or of all xition schemas FROM "start"
         start-xitions (get sid->ox "start")
         input-schema (into [:or]
                            (mapv (fn [{s "schema" :as xition}]
                                    (resolve-schema context xition s))
                                  start-xitions))

         ;; Compute output-schema: :or of all xition schemas TO "end"
         end-xitions (get sid->ix "end")
         output-schema (into [:or]
                             (mapv (fn [{s "schema" :as xition}]
                                     (resolve-schema context xition s))
                                   end-xitions))]
     {:input-schema input-schema
      :output-schema output-schema})))

;;------------------------------------------------------------------------------
;; FSM Composition Design Notes
;;------------------------------------------------------------------------------
;;
;; PROBLEM: How to compose FSMs - use one FSM as an action within another?
;;
;; DESIGN DECISIONS (as of 2024-12):
;;
;; 1. CONFIG-TIME CONTEXT
;;    - Actions receive context at config-time (not just runtime)
;;    - Context provides: :store (DB), :malli/registry, :id->action
;;    - This enables fsm-action to load child FSM and compute schemas
;;
;; 2. ACTION SCHEMAS vs TRANSITION SCHEMAS
;;    - Actions declare their CAPABILITY (often :any = "I handle anything")
;;    - Transitions declare the CONTRACT (specific types)
;;    - Subsumption: action-input must subsume transition-schema
;;      (action accepts at least what transition provides)
;;    - Example: llm-action accepts :any, but transition constrains to [:map ...]
;;
;; 3. DYNAMIC/LAZY ACTIONS (MCP, etc)
;;    - Use :any for both input and output at config-time
;;    - Runtime validation still happens per-call
;;    - Accept this as fundamental limitation of dynamic systems
;;    - Future: could tighten at start-time, but MCP varies per-call
;;
;; 4. FSM-ACTION SCHEMAS
;;    - Input schema = child FSM's input-schema (from fsm-schemas)
;;    - Output schema = child FSM's output-schema
;;    - Computed at config-time when child FSM is loaded
;;
;; DEFERRED PROBLEMS:
;; - Registry refs in action schemas: actions use primitives or :any for now
;; - Circular FSM references: need cycle detection, not implemented yet
;;
;; ASYNC COMPOSITION:
;; To compose FSMs asynchronously (child runs, then parent continues):
;; - start-fsm accepts optional :fsm/on-complete handler in context
;; - end-action calls this handler instead of (only) delivering to promise
;; - Handler receives [context trail], can filter trail before propagating
;; - Trail filtering prevents child FSM internals leaking to parent
;;
;; See: fsm-action in claij.actions for implementation
;;------------------------------------------------------------------------------

(defn start-fsm
  "Start an FSM with context and FSM definition. The start state is automatically 
   inferred from the entry transition (the transition with 'start' as the source state).
   
   Context should contain:
   - :id->action - Map of action-id to action function
   - Other keys as needed by actions (e.g. :store, :provider, :model)
   
   Optional context keys:
   - [:hats :registry] - Map of hat-name -> hat-maker. If present, don-hats is called
                         to expand any hat declarations before starting.
   
   Returns a map with:
   - :submit - Function to submit input data to the FSM
   - :await - Function to wait for FSM completion, optionally with timeout-ms
   - :stop - Function to stop the FSM
   - :input-schema - Malli :or schema of all transitions FROM 'start'
   - :output-schema - Malli :or schema of all transitions TO 'end'
   
   For design-time schema access without starting the FSM, use fsm-schemas instead.
   
   Usage:
     (let [{:keys [submit await stop]} (start-fsm context fsm)]
       (submit input-data)
       (let [result (await 5000)]  ; Wait up to 5 seconds
         (if (= result :timeout)
           (println \"FSM timed out\")
           (println \"FSM completed:\" result))))
   
   With hats:
     (let [registry (-> (hat/make-hat-registry)
                        (hat/register-hat \"mcp\" mcp-hat-maker))
           context (assoc-in context [:hats :registry] registry)
           {:keys [submit await]} (start-fsm context fsm)]
       ...)
   
   NOTE: Currently assumes exactly one transition from 'start'. See TODO above."
  [context {ss "states" xs "xitions" :as fsm}]
  (let [;; Don hats if registry present (expands hat declarations -> states/xitions)
        [context fsm] (if-let [registry (get-in context [:hats :registry])]
                        (do
                          (log/info "Donning hats...")
                          (hat/don-hats context fsm registry))
                        [context fsm])

        ;; Re-bind ss and xs from potentially modified fsm
        ss (get fsm "states")
        xs (get fsm "xitions")

        ;; Build FSM registry once at startup - includes base + FSM schemas + any context registry
        fsm-registry (build-fsm-registry fsm context)

        ;; Create completion promise (internal to start-fsm)
        completion-promise (promise)
        context (-> context
                    (assoc :fsm/completion-promise completion-promise)
                    (assoc :malli/registry fsm-registry))

        ;; Create channels and mappings
        id->x (index-by (->key "id") xs)
        id->s (index-by (->key "id") ss)
        xid->c (map-values (fn [_k _v] (chan)) id->x)
        all-channels (vals xid->c)
        xs->x-and-cs (fn [_ xs] (mapv (juxt identity (comp xid->c (->key "id"))) xs))
        sid->ix-and-cs (map-values xs->x-and-cs (group-by (comp second (->key "id")) xs))
        sid->ox-and-cs (map-values xs->x-and-cs (group-by (comp first (->key "id")) xs))

        ;; Extract entry transition info
        [[{[_ start-state :as entry-xition-id] "id" :as entry-xition}]] (sid->ox-and-cs "start")
        entry-channel (xid->c entry-xition-id)

        ;; Get schemas via fsm-schemas (pure function, can be called at design time too)
        {:keys [input-schema output-schema]} (fsm-schemas context fsm)

        ;; Error handling for channel operations
        safe-channel-operation (fn [op ch]
                                 (try
                                   (op ch)
                                   (catch Exception e
                                     (log/error e "Error operating on channel"))))

        ;; Create interface functions
        submit (fn [input-data]
                 ;; Send event to entry channel
                 ;; Only add to trail if entry transition doesn't have omit=true
                 ;; Use entry-xition-id (["start" first-state]) for the from/to, not input-data
                 (let [[from to] entry-xition-id
                       entry-omit? (get entry-xition "omit")
                       initial-trail (if entry-omit?
                                       []
                                       [{:from from :to to :event input-data}])
                       message {:context context
                                :event input-data
                                :trail initial-trail}]
                   (safe-channel-operation #(>!! entry-channel %) message)
                   (log/info (str "   Submitted document to FSM: " start-state))))

        await-fn (fn
                   ([] (deref completion-promise))
                   ([timeout-ms] (deref completion-promise timeout-ms :timeout)))

        stop-fsm (fn []
                   (log/info (str "stopping fsm: " (or (get fsm "id") "unnamed")))
                   ;; Run stop hooks (for hat cleanup like MCP bridge)
                   (hat/run-stop-hooks context)
                   (doseq [c all-channels]
                     (safe-channel-operation close! c)))]

    (log/info (str "starting fsm: " (or (get fsm "id") "unnamed")
                   " (" (count ss) " states, " (count xs) " transitions)"))

    ;; Start all the state loops
    (doseq [{id "id" :as s} ss]
      (let [ix-and-cs (sid->ix-and-cs id)
            ox-and-cs (sid->ox-and-cs id)
            ic->ix (into {} (mapv (fn [[x c]] [c x]) ix-and-cs))
            cs (keys ic->ix)]
        (when (seq cs)
          (go-loop []
            (let [[{ctx :context event :event trail :trail :as msg} ic] (alts! cs)]
              (when msg
                (let [ix (ic->ix ic)]
                  (xform ctx fsm ix s ox-and-cs event trail)
                  (recur))))))))

    ;; Return interface as map
    {:submit submit
     :await await-fn
     :stop stop-fsm
     :input-schema input-schema
     :output-schema output-schema}))

(defn last-event
  "Extract the final event from a trail.
   
   Takes a trail (vector of audit entries) and returns the event from the
   most recent entry. Each entry has {:from :to :event}.
   
   Usage:
     (let [[context trail] (await)]
       (last-event trail))  ; Returns the final event"
  [trail]
  (:event (peek trail)))

(defn run-sync
  "Run an FSM synchronously, blocking until completion.
   
   Starts the FSM, submits the input data, and waits for completion.
   Returns [context trail] tuple containing final context and execution trail.
   
   Optional timeout-ms parameter (default 30000ms/30s) prevents indefinite blocking.
   Returns :timeout keyword if timeout is reached.
   
   Usage:
     (let [[context trail] (run-sync fsm ctx input-data)]
       (println \"Final event:\" (last-event trail)))
     
     ;; With timeout check
     (let [result (run-sync fsm ctx input-data 5000)]
       (if (= result :timeout)
         (println \"FSM timed out\")
         (let [[context trail] result]
           (println \"FSM completed:\" (last-event trail)))))"
  ([fsm context input-data]
   (run-sync fsm context input-data 30000))
  ([fsm context input-data timeout-ms]
   (let [{:keys [submit await stop]} (start-fsm context fsm)]
     (try
       (submit input-data)
       (await timeout-ms)
       (finally
         (stop))))))

;;------------------------------------------------------------------------------
;; Action Lifting
;;------------------------------------------------------------------------------

(defn lift
  "Lift a pure function into an FSM action.
   
   The function f receives the event and returns the output event.
   The output event must include an \"id\" field for transition routing.
   
   Usage in FSM state definition:
     {\"id\" \"processor\" \"action\" \"my-action\"}
   
   With context:
     {:id->action {\"my-action\" (lift inc-value)}}
   
   Where inc-value might be:
     (defn inc-value [event]
       (-> event
           (update \"value\" inc)
           (assoc \"id\" [\"processor\" \"end\"])))
   
   Options (optional second arg):
     :name - Action name for logging/debugging (default: \"lifted\")
   
   The lifted action:
   - Passes context through unchanged
   - Calls (f event) to compute output
   - Calls (handler context output)"
  ([f] (lift f {}))
  ([f {:keys [name] :or {name "lifted"}}]
   (with-meta
     (fn [_config _fsm _ix _state]
       (fn [context event _trail handler]
         (log/debug (str "lift[" name "]: processing event"))
         (let [output (f event)]
           (handler context output))))
     {:action/name name
      :action/config-schema :any
      :action/input-schema :any
      :action/output-schema :any})))

;;------------------------------------------------------------------------------
;; FSM Chaining
;;------------------------------------------------------------------------------

(defn chain
  "Chain multiple FSMs together in sequence.
   
   Output of FSM-n feeds into input of FSM-n+1.
   Returns control functions for the entire chain.
   
   Args:
     context - Shared context for all FSMs (with :id->action etc)
     fsm1, fsm2, ... - Two or more FSM definitions
   
   Returns:
     {:start  - fn [] starts all FSMs, returns chain handle
      :stop   - fn [] stops all FSMs in reverse order
      :submit - fn [input] submits to first FSM
      :await  - fn [& [timeout-ms]] waits for final FSM completion}
   
   The chain automatically wires FSM outputs to next FSM inputs:
   - FSM-1 completes → extracts last-event → submits to FSM-2
   - FSM-2 completes → extracts last-event → submits to FSM-3
   - ... and so on
   
   Example - chain three increment FSMs:
     (let [inc-fsm (make-inc-fsm)
           {:keys [start stop submit await]} (chain ctx inc-fsm inc-fsm inc-fsm)]
       (start)
       (submit {\"id\" [\"start\" \"process\"] \"value\" 0})
       (let [[_ trail] (await 5000)]
         (println (last-event trail)))  ; => {\"value\" 3 ...}
       (stop))
   
   Note: Each FSM in the chain must have compatible schemas:
   - FSM-n output-schema should be compatible with FSM-n+1 input-schema
   - Use fsm-schemas to check compatibility at design time"
  [context & fsms]
  (when (< (count fsms) 2)
    (throw (ex-info "chain requires at least 2 FSMs" {:count (count fsms)})))

  (let [;; State to hold started FSM handles
        fsm-handles (atom [])
        started? (atom false)]

    ;; Return interface - using letfn would cause issues, so we define simple fns
    {:start
     (fn start-chain []
       (when @started?
         (throw (ex-info "Chain already started" {})))

       (let [n (count fsms)
             handles (vec (for [fsm fsms]
                            (start-fsm context fsm)))]

         ;; Wire FSM-i completion to FSM-i+1 submit
         ;; We do this by wrapping the await in a thread that forwards
         (doseq [i (range (dec n))]
           (let [current-handle (nth handles i)
                 next-handle (nth handles (inc i))]
             (future
               (let [result ((:await current-handle))]
                 (when (and result (not= result :timeout))
                   (let [[_ctx trail] result
                         output (last-event trail)]
                     (when output
                       ((:submit next-handle) output))))))))

         (reset! fsm-handles handles)
         (reset! started? true)
         handles))

     :stop
     (fn stop-chain []
       (when @started?
         (doseq [handle (reverse @fsm-handles)]
           ((:stop handle)))
         (reset! started? false)
         (reset! fsm-handles [])))

     :submit
     (fn submit-chain [input]
       (when-not @started?
         (throw (ex-info "Chain not started - call start first" {})))
       ((:submit (first @fsm-handles)) input))

     :await
     (fn await-chain
       ([]
        (when-not @started?
          (throw (ex-info "Chain not started - call start first" {})))
        ((:await (last @fsm-handles))))
       ([timeout-ms]
        (when-not @started?
          (throw (ex-info "Chain not started - call start first" {})))
        ((:await (last @fsm-handles)) timeout-ms)))}))

;;------------------------------------------------------------------------------
;; LLM Action Support
;;------------------------------------------------------------------------------

(def llm-configs
  "Configuration for different LLM providers and models.
   Each entry maps [provider model] to a config map with:
   - :prompts - vector of prompt maps with :role and :content
   - Future: could include :temperature, :max-tokens, etc."
  {["anthropic" "claude-sonnet-4.5"]
   {:prompts [{:role "system"
               :content "CRITICAL: Your response must be ONLY valid EDN - no explanatory text before or after."}
              {:role "system"
               :content "CRITICAL: Use string keys like \"id\" not keyword keys like :id."}
              {:role "system"
               :content "CRITICAL: Be concise in your response to avoid hitting token limits."}]}

   ;; OpenAI models - generally work well with standard prompts
   ["openai" "gpt-5.2-chat"] {}

   ;; xAI models
   ["x-ai" "grok-code-fast-1"] {}
   ["x-ai" "grok-4"] {}

   ;; Google models
   ["google" "gemini-3-pro-preview"] {}})

(defn trail->prompts
  "Convert audit trail to LLM conversation prompts.
   
   Each audit entry contains {:from :to :event} where event triggered transition from→to.
   
   Role is determined by what PRODUCED the event:
   - If 'from' state has action='llm' → assistant (LLM produced this)
   - Otherwise → user (request to LLM)
   
   Parameters:
   - context: FSM context (for schema resolution, includes :malli/registry)
   - fsm: The FSM definition (for schema lookups and state actions)
   - trail: The audit trail (vector, oldest-first)
   
   Returns: Sequence of prompt messages with refs expanded for LLM consumption."
  [context {xs "xitions" ss "states" :as fsm} trail]
  (let [;; Get registry from context (built in start-fsm), or build from FSM for backwards compat
        registry (or (get context :malli/registry)
                     (build-fsm-registry fsm context))
        ;; Index for lookups
        id->x (index-by (->key "id") xs)
        id->s (index-by (->key "id") ss)
        ;; Get output transitions from a state
        state-output-xitions (fn [state-id]
                               (filter (fn [{[from _to] "id"}] (= from state-id)) xs))
        ;; Helper to expand refs for LLM (refs are Clojure constructs, LLM can't resolve them)
        expand (fn [schema] (expand-refs-for-llm schema registry))]
    (mapcat
     (fn [{:keys [from to event error]}]
       (let [;; Look up source state to determine role
             source-state (id->s from)
             from-is-llm? (= "llm" (get source-state "action"))
             role (if from-is-llm? "assistant" "user")
             ;; Get the transition and resolve its schema (handles string lookups)
             ix (id->x [from to])
             ix-schema-raw (resolve-schema context ix (get ix "schema"))
             ix-schema (expand ix-schema-raw)
             ;; Compute output schema (Malli :or from destination state's outgoing transitions)
             ;; Resolve each schema then expand refs
             output-xitions (state-output-xitions to)
             s-schema-raw (into [:or] (mapv #(resolve-schema context % (get % "schema")) output-xitions))
             s-schema (expand s-schema-raw)]
         (cond
           ;; Error entry - always user message with error feedback
           error
           [{"role" "user" "content" [:string (:message error) ix-schema]}]

           ;; Normal entry - role based on what produced it
           :else
           [{"role" role "content" [ix-schema event s-schema]}])))
     trail)))

(defn make-prompts
  "Build prompt messages from FSM configuration and conversation trail.
   Optionally accepts provider/model for LLM-specific prompts."
  ([fsm ix state trail]
   (make-prompts fsm ix state trail nil nil))
  ([{fsm-schemas "schemas" fsm-prompts "prompts" :as _fsm}
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
      ;; Build system message with clear tuple-3 explanation - matches POC style
      [{"role" "system"
        "content" (join
                   "\n"
                   (concat
                    ["We are living in a Clojure world."
                     "All communications will be in EDN (Extensible Data Notation) format."
                     ""
                     "REFERENCE SCHEMAS:"
                     (pr-str fsm-schemas)
                     ""
                     "YOUR REQUEST:"
                     "- will contain [INPUT-SCHEMA INPUT-DOCUMENT OUTPUT-SCHEMA] triples."
                     "- INPUT-SCHEMA: Malli schema describing the INPUT-DOCUMENT"
                     "- INPUT-DOCUMENT: The actual data to process"
                     "- OUTPUT-SCHEMA: Malli schema your response MUST conform to"
                     ""
                     "YOUR RESPONSE - the OUTPUT-DOCUMENT:"
                     "- Must be ONLY valid EDN (no markdown, no backticks, no explanation)"
                     "- Must use string keys like \"id\" not keyword keys like :id"
                     "- The OUTPUT-SCHEMA will offer you a set (possibly only one) of choices/sub-schemas"
                     "- Your OUTPUT-DOCUMENT must conform strictly to one of these - it is a document NOT a schema itself"
                     "- Each sub-schema will contain a discriminator called \"id\". You must include this"
                     "- You must include all non-optional fields with a valid value"
                     ""
                     "CONTEXT:"]
                    fsm-prompts
                    ix-prompts
                    state-prompts
                    llm-system-prompts))}]

      ;; Add any LLM-specific user prompts
      (when (seq llm-user-prompts)
        [{"role" "user"
          "content" (join "\n" llm-user-prompts)}])

      ;; Add conversation trail
      (map (fn [m] (update m "content" pr-str)) trail)))))

(def-action llm-action
  "FSM action: call LLM with prompts built from FSM config and trail.
   
   Config schema (all optional):
   - \"provider\" - LLM provider (anthropic, google, openai, xai)
   - \"model\" - Model identifier string
   
   Provider/model precedence: config → event → context → defaults
   
   Passes the output schema (Malli :or of valid output transitions) to call
   for structured output enforcement."
  [:map
   ["provider" {:optional true} [:enum "anthropic" "google" "openai" "xai"]]
   ["model" {:optional true} :string]]
  [config {xs "xitions" :as fsm} ix {sid "id" :as state}]
  (fn [context event trail handler]
    (log/info "llm-action entry, trail-count:" (count trail))
    (let [{event-provider "provider" event-model "model"} (get event "llm")
          {config-provider "provider" config-model "model"} config
          ;; Precedence: config → event → context → defaults
          provider (or config-provider event-provider (:llm/provider context) "google")
          model (or config-model event-model (:llm/model context) "gemini-3-pro-preview")

          ;; Compute output transitions from current state
          output-xitions (filter (fn [{[from _to] "id"}] (= from sid)) xs)
          output-schema (state-schema context fsm state output-xitions)

          ;; Get registry for expanding refs
          registry (or (get context :malli/registry)
                       (build-fsm-registry fsm context))

          ;; Build prompt trail from history
          prompt-trail (trail->prompts context fsm trail)

          ;; CRITICAL: When trail is empty (first call), we need to send the initial event!
          ;; POC sends [input-schema input-doc output-schema] as user message
          ;; We do the same here - use entry transition schema as input-schema
          initial-message (when (empty? trail)
                            (let [ix-schema-raw (resolve-schema context ix (get ix "schema"))
                                  ix-schema (expand-refs-for-llm ix-schema-raw registry)
                                  out-schema (expand-refs-for-llm output-schema registry)]
                              [{"role" "user"
                                "content" (pr-str [ix-schema event out-schema])}]))

          ;; Combine: trail prompts + initial message if needed
          full-trail (if initial-message
                       initial-message
                       prompt-trail)

          prompts (make-prompts fsm ix state full-trail provider model)]
      (log/info (str "   Using LLM: " provider "/" model " with " (count prompts) " prompts"))
      (call
       provider model
       prompts
       (fn [output]
         (log/info "llm-action got output, id:" (get output "id") ":" (pr-str output))
         (handler context output))
       {:schema output-schema
        :error (fn [error-info]
                 (log/error "LLM action failed:" (pr-str error-info))
                 ;; Return error to handler so FSM can see it
                 (handler context {"id" "error" "error" error-info}))}))))

;; a correlation id threaded through the data
;; a monitor or callback on each state
;; start and end states which are just labels
;; sub-fsms = like a sub-routine - can be embedded in a state or a transition - hmmm - schemas must match ?
