(ns claij.mcp.schema
  "JSON Schema validation helpers for MCP subsystem.
   
   MCP uses the official JSON Schema from the MCP protocol spec.
   This is kept separate from the Malli-based core FSM system."
  (:require
   [clojure.tools.logging :as log]
   [m3.validate :refer [validate $schema->m2]]))

(defn valid-json-schema?
  "Validate that a value is a well-formed JSON Schema using m3."
  [{m3-id "$schema" m2-id "$id" :as schema}]
  (let [{v? :valid? es :errors} (validate {} ($schema->m2 m3-id) {} schema)]
    (if v?
      true
      (do
        (log/errorf "Invalid JSON Schema: %s - %s" m2-id (pr-str es))
        false))))

(defmacro def-json-schema
  "Define a JSON Schema constant, validating it at load time."
  [name schema]
  `(def ~name
     (let [s# ~schema]
       (assert (valid-json-schema? s#) "Invalid JSON Schema")
       s#)))

(defn valid-document?
  "Validate a document against a JSON Schema using m3."
  [schema doc]
  (let [{v? :valid? es :errors} (validate {} schema {} doc)]
    (if v?
      true
      (do
        (log/errorf "Invalid document: %s - %s" (pr-str doc) (pr-str es))
        false))))
