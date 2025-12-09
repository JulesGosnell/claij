(ns claij.action
  "Action definition macro and metadata helpers.
   
   This namespace is separate from claij.actions to avoid circular dependencies:
   - claij.actions requires claij.fsm (for start-fsm in reuse-action)
   - claij.fsm needs def-action (for llm-action)
   
   By putting def-action here, both can require it without cycles."
  (:require
   [malli.core :as m]))

;;------------------------------------------------------------------------------
;; def-action Macro
;;------------------------------------------------------------------------------

(defmacro def-action
  "Define a curried action factory with schema declarations.
   
   Usage (map form - preferred):
   (def-action my-action
     \"Documentation string\"
     {:config [:map [\"timeout\" :int]]  ;; config schema (validated at factory call)
      :input :any                        ;; input schema (what action accepts)
      :output :any}                      ;; output schema (what action produces)
     [config fsm ix state]               ;; config-time params
     (fn [context event trail handler]   ;; returns runtime function
       ...))
   
   Usage (legacy vector form - backward compatible):
   (def-action my-action
     \"Documentation string\"
     [:map [\"timeout\" :int]]           ;; config schema only, input/output default to :any
     [config fsm ix state]
     (fn [context event trail handler]
       ...))
   
   The defined var has metadata:
   - :action/name - the action name as string
   - :action/config-schema - Malli schema for config validation
   - :action/input-schema - Malli schema for action input (default :any)
   - :action/output-schema - Malli schema for action output (default :any)
   - :doc - standard Clojure docstring (works with clojure.repl/doc)
   
   The factory validates config before returning the runtime function.
   Throws ExceptionInfo on config validation failure.
   
   Note: Input/output schemas declare the action's CAPABILITY.
   FSM transition schemas declare the CONTRACT. Subsumption checking
   ensures action-input subsumes transition-schema (action accepts
   at least what transition provides)."
  [name doc schema-spec params & body]
  (let [action-name (str name)
        ;; Support both map form {:config ... :input ... :output ...}
        ;; and legacy vector form (config-schema only)
        schema-map? (and (map? schema-spec) (contains? schema-spec :config))
        config-schema (if schema-map? (:config schema-spec) schema-spec)
        input-schema (if schema-map? (get schema-spec :input :any) :any)
        output-schema (if schema-map? (get schema-spec :output :any) :any)]
    `(def ~(with-meta name
             {:action/name action-name
              :action/config-schema config-schema
              :action/input-schema input-schema
              :action/output-schema output-schema
              :doc doc})
       (fn [config# fsm# ix# state#]
         ;; Validate config at factory call time (start-fsm)
         (when-not (m/validate ~config-schema config#)
           (throw (ex-info (str "Action config validation failed: " ~action-name)
                           {:type :config-validation
                            :action ~action-name
                            :schema ~config-schema
                            :value config#
                            :explanation (m/explain ~config-schema config#)})))
         ;; Return runtime function with config-time params closed over
         (let [~params [config# fsm# ix# state#]]
           ~@body)))))

;;------------------------------------------------------------------------------
;; Action Metadata Helpers
;;------------------------------------------------------------------------------

(defn action?
  "Returns true if the var (or its value) is an action factory.
   Only recognizes f1 (factories), not f2 (runtime functions)."
  [v]
  (boolean (-> (if (var? v) v (resolve v)) meta :action/name)))

(defn action-name
  "Get the action name from an action var's metadata."
  [action-var]
  (-> action-var meta :action/name))

(defn action-config-schema
  "Get the config schema from an action var's metadata."
  [action-var]
  (-> action-var meta :action/config-schema))

(defn action-input-schema
  "Get the input schema from an action var's metadata.
   Returns :any if not explicitly declared."
  [action-var]
  (or (-> action-var meta :action/input-schema) :any))

(defn action-output-schema
  "Get the output schema from an action var's metadata.
   Returns :any if not explicitly declared."
  [action-var]
  (or (-> action-var meta :action/output-schema) :any))
