(ns claij.hat
  "Hat system for FSM state capability decoration.
   
   A hat is a reusable capability that can be attached to FSM states.
   Each hat generates an FSM fragment (states + xitions + prompts) 
   that gets merged into the parent FSM.
   
   Hat contract:
   - A hat-maker is a function: (fn [state-id config]) -> hat-fn
   - A hat-fn is a function: (fn [context]) -> [context' fragment]
   - Fragment is a map with optional keys: \"states\", \"xitions\", \"prompts\"
   
   Static vs Dynamic hats:
   - Static hat: ignores context, returns fixed fragment (e.g., REPL)
   - Dynamic hat: inspects context, may start services, returns context-dependent fragment (e.g., MCP)"
  (:require
   [clojure.tools.logging :as log]))

;;==============================================================================
;; Hat-maker Contract
;;==============================================================================
;; 
;; A hat-maker is a function with signature:
;;   (fn [state-id config]) -> hat-fn
;;
;; Where hat-fn has signature:
;;   (fn [context]) -> [context' fragment]
;;
;; Fragment structure:
;;   {"states"  [...]   ;; states to add
;;    "xitions" [...]   ;; transitions to add  
;;    "prompts" [...]}  ;; prompts to add to target state
;;
;;==============================================================================

(defn make-hat
  "Create a hat-fn from a hat-maker.
   
   This is the core contract function. Given a hat-maker, state-id and config,
   returns a hat-fn that can be called with context to produce [context' fragment].
   
   Parameters:
   - hat-maker: function (fn [state-id config]) -> hat-fn
   - state-id: the state this hat is being applied to
   - config: optional configuration map for the hat
   
   Returns: hat-fn (fn [context]) -> [context' fragment]"
  [hat-maker state-id config]
  (hat-maker state-id config))

;;==============================================================================
;; Example Static Hat (for testing/documentation)
;;==============================================================================

(defn static-echo-hat-maker
  "Example static hat-maker. Returns same fragment regardless of context.
   
   Creates a loopback state that echoes requests back."
  [state-id config]
  (let [service-id (str state-id "-echo")]
    (fn [context]
      ;; Static: ignores context, returns fixed fragment
      [context
       {"states" [{"id" service-id
                   "action" "echo-service"}]
        "xitions" [{"id" [state-id service-id]
                    "schema" [:map ["request" :string]]}
                   {"id" [service-id state-id]
                    "schema" [:map ["response" :string]]}]
        "prompts" [(str "You can echo messages via " service-id)]}])))

;;==============================================================================
;; Example Dynamic Hat (for testing/documentation)
;;==============================================================================

(defn dynamic-counter-hat-maker
  "Example dynamic hat-maker. Output depends on context.
   
   If context has :counter/value, uses it in schema description.
   Otherwise, starts counter and returns loose schema."
  [state-id config]
  (let [service-id (str state-id "-counter")]
    (fn [context]
      (if-let [counter-val (:counter/value context)]
        ;; Dynamic: context has counter, build specific schema
        [context
         {"states" [{"id" service-id "action" "counter-service"}]
          "xitions" [{"id" [state-id service-id]
                      "schema" [:map ["increment" [:int {:min 1 :max counter-val}]]]}
                     {"id" [service-id state-id]
                      "schema" [:map ["new-value" :int]]}]
          "prompts" [(str "Counter available (max increment: " counter-val ")")]}]
        ;; Dynamic: no counter yet, initialize and return loose schema
        [(assoc context :counter/value 100)
         {"states" [{"id" service-id "action" "counter-service"}]
          "xitions" [{"id" [state-id service-id]
                      "schema" [:map ["increment" :int]]}
                     {"id" [service-id state-id]
                      "schema" [:map ["new-value" :int]]}]
          "prompts" ["Counter service initializing..."]}]))))

;;==============================================================================
;; Hat Registry
;;==============================================================================

(defn make-hat-registry
  "Create a new hat registry (a map of hat-name -> hat-maker)."
  []
  {})

(defn register-hat
  "Register a hat-maker in a registry. Returns updated registry."
  [registry hat-name hat-maker]
  (assoc registry hat-name hat-maker))

(defn get-hat-maker
  "Get a hat-maker from a registry by name."
  [registry hat-name]
  (get registry hat-name))

;;==============================================================================
;; Fragment Merging
;;==============================================================================

(defn merge-fragment
  "Merge a hat-generated fragment into an FSM.
   
   Fragment can have:
   - \"states\" - vector of states to add
   - \"xitions\" - vector of transitions to add  
   - \"prompts\" - vector of prompts to add to target state
   
   Returns updated FSM."
  [fsm fragment target-state-id]
  (let [new-states (get fragment "states" [])
        new-xitions (get fragment "xitions" [])
        new-prompts (get fragment "prompts" [])]
    (cond-> fsm
      ;; Add new states
      (seq new-states)
      (update "states" into new-states)

      ;; Add new transitions
      (seq new-xitions)
      (update "xitions" into new-xitions)

      ;; Add prompts to target state
      (seq new-prompts)
      (update "states"
              (fn [states]
                (mapv (fn [state]
                        (if (= (get state "id") target-state-id)
                          (update state "prompts"
                                  (fn [ps] (into (or ps []) new-prompts)))
                          state))
                      states))))))

;;==============================================================================
;; Don Hats
;;==============================================================================

(defn- parse-hat-declaration
  "Parse a hat declaration into [hat-name config].
   Handles both string form and map form.
   
   \"mcp\" -> [\"mcp\" nil]
   {\"mcp\" {:services [...]}} -> [\"mcp\" {:services [...]}]"
  [decl]
  (cond
    (string? decl) [decl nil]
    (map? decl) (first decl)
    :else (throw (ex-info "Invalid hat declaration" {:declaration decl}))))

(defn- don-state-hat
  "Don a single hat on a state. Returns [context' fsm']."
  [context fsm registry state-id hat-decl]
  (let [[hat-name config] (parse-hat-declaration hat-decl)
        hat-maker (get-hat-maker registry hat-name)]
    (if hat-maker
      (let [hat-fn (make-hat hat-maker state-id config)
            [ctx' fragment] (hat-fn context)]
        (log/info "Donning hat" hat-name "on state" state-id)
        [ctx' (merge-fragment fsm fragment state-id)])
      (do
        (log/warn "Unknown hat:" hat-name "on state" state-id)
        [context fsm]))))

(defn- don-state-hats
  "Don all hats declared on a single state. Returns [context' fsm']."
  [context fsm registry state]
  (let [state-id (get state "id")
        hat-decls (get state "hats" [])]
    (reduce
     (fn [[ctx fsm'] decl]
       (don-state-hat ctx fsm' registry state-id decl))
     [context fsm]
     hat-decls)))

(defn- don-fsm-hats
  "Don hats declared at FSM level (linking hats). Returns [context' fsm']."
  [context fsm registry]
  (let [hat-decls (get fsm "hats" [])]
    (reduce
     (fn [[ctx fsm'] [hat-name state-ids]]
       (let [hat-maker (get-hat-maker registry hat-name)]
         (if hat-maker
           ;; FSM-level hats receive vector of state-ids
           (let [hat-fn (hat-maker state-ids nil)
                 [ctx' fragment] (hat-fn ctx)]
             (log/info "Donning FSM-level hat" hat-name "for states" state-ids)
             ;; For FSM-level hats, prompts go to first state in list
             [ctx' (merge-fragment fsm' fragment (first state-ids))])
           (do
             (log/warn "Unknown FSM-level hat:" hat-name)
             [ctx fsm']))))
     [context fsm]
     hat-decls)))

(defn don-hats
  "Don all hats declared on an FSM.
   
   Processes:
   1. State-level hats (loopback capabilities)
   2. FSM-level hats (linking capabilities)
   
   Parameters:
   - context: the FSM context
   - fsm: the FSM definition
   - registry: map of hat-name -> hat-maker
   
   Returns [context' fsm'] where:
   - context' has any hat-added resources (e.g., :mcp/bridge)
   - fsm' has hat fragments merged in"
  [context fsm registry]
  (let [states (get fsm "states" [])
        ;; First, don state-level hats
        [ctx' fsm'] (reduce
                     (fn [[ctx f] state]
                       (don-state-hats ctx f registry state))
                     [context fsm]
                     states)
        ;; Then, don FSM-level hats
        [ctx'' fsm''] (don-fsm-hats ctx' fsm' registry)]
    [ctx'' fsm'']))

;;==============================================================================
;; Stop Hooks
;;==============================================================================

(defn add-stop-hook
  "Add a stop hook to the context. Stop hooks are called when the FSM stops.
   
   Parameters:
   - context: the FSM context
   - hook-fn: function (fn [context]) that performs cleanup
   
   Returns updated context with hook added to [:hats :stop-hooks]."
  [context hook-fn]
  (update-in context [:hats :stop-hooks] (fnil conj []) hook-fn))

(defn run-stop-hooks
  "Run all stop hooks in reverse order (LIFO).
   
   Parameters:
   - context: the FSM context with [:hats :stop-hooks]
   
   Returns context after all hooks have run."
  [context]
  (let [hooks (get-in context [:hats :stop-hooks] [])]
    (log/info "Running" (count hooks) "stop hooks")
    (reduce (fn [ctx hook]
              (try
                (hook ctx)
                (catch Exception e
                  (log/error e "Error in stop hook")
                  ctx)))
            context
            (reverse hooks))))
