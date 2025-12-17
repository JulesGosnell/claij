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
