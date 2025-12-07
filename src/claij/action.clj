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
  "Define a curried action factory with config schema validation.
   
   Usage:
   (def-action my-action
     \"Documentation string\"
     [:map [\"timeout\" :int]]        ;; config schema (validated at def-fsm time)
     [config fsm ix state]            ;; config-time params
     (fn [context event trail handler] ;; returns runtime function
       ...))
   
   The defined var has metadata:
   - :action/name - the action name as string
   - :action/config-schema - Malli schema for config validation
   - :doc - standard Clojure docstring (works with clojure.repl/doc)
   
   The factory validates config before returning the runtime function.
   Throws ExceptionInfo on config validation failure.
   
   Note: Runtime input/output validation is FSM's responsibility via
   transition schemas. Actions don't validate events or outputs."
  [name doc config-schema params & body]
  (let [action-name (str name)]
    `(def ~(with-meta name
             {:action/name action-name
              :action/config-schema config-schema
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
