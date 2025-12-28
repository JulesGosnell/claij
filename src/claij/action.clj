(ns claij.action
  "Action definition macro and metadata helpers.
   
   This namespace is separate from claij.actions to avoid circular dependencies:
   - claij.actions requires claij.fsm (for start-fsm in reuse-action)
   - claij.fsm needs def-action (for llm-action)
   
   By putting def-action here, both can require it without cycles."
  (:require
   [claij.schema :as schema]))

(defn stringify-keys
  "Recursively convert keyword keys to strings for JSON Schema validation.
   Public because it's used in def-action macro which expands in other namespaces."
  [m]
  (cond
    (map? m) (into {} (map (fn [[k v]] [(if (keyword? k) (name k) k) (stringify-keys v)]) m))
    (sequential? m) (mapv stringify-keys m)
    :else m))

;;------------------------------------------------------------------------------
;; def-action Macro
;;------------------------------------------------------------------------------

(defmacro def-action
  "Define a curried action factory with schema declarations.
   
   Usage (map form - preferred):
   (def-action my-action
     \"Documentation string\"
     {:config {\"type\" \"object\" ...}  ;; config schema (validated at factory call)
      :input true                        ;; input schema (what action accepts, true = any)
      :output true}                      ;; output schema (what action produces, true = any)
     [config fsm ix state]               ;; config-time params
     (fn [context event trail handler]   ;; returns runtime function
       ...))
   
   Usage (legacy form - backward compatible):
   (def-action my-action
     \"Documentation string\"
     {\"type\" \"object\" ...}           ;; config schema only, input/output default to true
     [config fsm ix state]
     (fn [context event trail handler]
       ...))
   
   The defined var has metadata:
   - :action/name - the action name as string
   - :action/config-schema - JSON Schema for config validation
   - :action/input-schema - JSON Schema for action input (default true = any)
   - :action/output-schema - JSON Schema for action output (default true = any)
   - :doc - standard Clojure docstring (works with clojure.repl/doc)
   
   The factory validates config before returning the runtime function.
   Throws ExceptionInfo on config validation failure.
   
   Note: Input/output schemas declare the action's CAPABILITY.
   FSM transition schemas declare the CONTRACT."
  [name doc schema-spec params & body]
  (let [action-name (str name)
        ;; Support both map form {:config ... :input ... :output ...}
        ;; and legacy form (config-schema only)
        schema-map? (and (map? schema-spec) (contains? schema-spec :config))
        config-schema (if schema-map? (:config schema-spec) schema-spec)
        input-schema (if schema-map? (get schema-spec :input true) true)
        output-schema (if schema-map? (get schema-spec :output true) true)]
    `(def ~(with-meta name
             {:action/name action-name
              :action/config-schema config-schema
              :action/input-schema input-schema
              :action/output-schema output-schema
              :doc doc})
       (fn [config# fsm# ix# state#]
         ;; Normalize config: keyword keys -> string keys for consistency
         (let [config# (stringify-keys config#)
               ;; Validate config at factory call time (start-fsm)
               result# (schema/validate ~config-schema config#)]
           (when-not (:valid? result#)
             (throw (ex-info (str "Action config validation failed: " ~action-name)
                             {:type :config-validation
                              :action ~action-name
                              :schema ~config-schema
                              :value config#
                              :errors (:errors result#)})))
           ;; Return runtime function with config-time params closed over
           (let [~params [config# fsm# ix# state#]]
             ~@body))))))

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
   Returns true (any) if not explicitly declared."
  [action-var]
  (or (-> action-var meta :action/input-schema) true))

(defn action-output-schema
  "Get the output schema from an action var's metadata.
   Returns true (any) if not explicitly declared."
  [action-var]
  (or (-> action-var meta :action/output-schema) true))
