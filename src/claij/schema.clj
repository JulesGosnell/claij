(ns claij.schema
  "JSON Schema utilities for CLAIJ.
   
   Provides:
   - Schema validation using m3 (JSON Schema validator)
   - $ref resolution for schema composition
   - def-fsm macro for FSM definition with validation
   
   Uses JSON Schema draft-2020-12 for validation (required for nested $ref resolution)."
  (:require
   [clojure.tools.logging :as log]
   [m3.validate :as m3]))

;;==============================================================================
;; Core Validation
;;==============================================================================

(defn validate
  "Validate a value against a JSON Schema using m3.
   
   Args:
     schema - JSON Schema as a Clojure map
     value  - Value to validate
     defs   - Optional map of $defs for $ref resolution
   
   Returns:
     {:valid? true} or {:valid? false :errors [...]}"
  ([schema value]
   (validate schema value {}))
  ([schema value defs]
   (let [;; Wrap schema with $defs if provided
         full-schema (if (seq defs)
                       (assoc schema "$defs" defs)
                       schema)
         result (m3/validate {:draft :draft2020-12} full-schema {} value)]
     (if (:valid? result)
       {:valid? true}
       {:valid? false
        :errors (mapv (fn [e]
                        {:path (:document-path e)
                         :message (:message e)})
                      (:errors result))}))))

(defn valid?
  "Check if value is valid against schema. Returns boolean."
  ([schema value]
   (valid? schema value {}))
  ([schema value defs]
   (:valid? (validate schema value defs))))

;;==============================================================================
;; Schema Building Helpers
;;==============================================================================

(defn string-schema
  "Create a string schema with optional constraints."
  ([] {"type" "string"})
  ([opts] (merge {"type" "string"} opts)))

(defn integer-schema
  "Create an integer schema with optional constraints."
  ([] {"type" "integer"})
  ([opts] (merge {"type" "integer"} opts)))

(defn number-schema
  "Create a number schema with optional constraints."
  ([] {"type" "number"})
  ([opts] (merge {"type" "number"} opts)))

(defn boolean-schema
  "Create a boolean schema."
  [] {"type" "boolean"})

(defn array-schema
  "Create an array schema with item type."
  ([items] {"type" "array" "items" items})
  ([items opts] (merge {"type" "array" "items" items} opts)))

(defn object-schema
  "Create an object schema with properties.
   
   Args:
     props - Map of property-name -> schema
     opts  - Optional map with :required (vector of required keys),
             :additional (boolean for additionalProperties),
             :description (string)"
  ([props] (object-schema props {}))
  ([props {:keys [required additional description]}]
   (cond-> {"type" "object"
            "properties" props}
     required (assoc "required" (vec required))
     (some? additional) (assoc "additionalProperties" additional)
     description (assoc "description" description))))

(defn enum-schema
  "Create an enum schema from values."
  [& values]
  {"enum" (vec values)})

(defn const-schema
  "Create a const schema (exact value match)."
  [value]
  {"const" value})

(defn ref-schema
  "Create a $ref to a definition in $defs."
  [def-name]
  {"$ref" (str "#/$defs/" def-name)})

(defn any-of-schema
  "Create an anyOf (union) schema - at least one schema must match.
   
   Prefer one-of-schema for discriminated unions (like FSM xition schemas)
   where exactly one schema should match based on a discriminator field."
  [& schemas]
  {"anyOf" (vec schemas)})

(defn one-of-schema
  "Create a oneOf (discriminated union) schema - exactly one schema must match.
   
   Use for FSM xition schemas where the 'id' const field acts as a discriminator,
   ensuring unambiguous schema matching to determine the correct output xition."
  [& schemas]
  {"oneOf" (vec schemas)})

(defn all-of-schema
  "Create an allOf (intersection) schema."
  [& schemas]
  {"allOf" (vec schemas)})

;;==============================================================================
;; FSM Schema Definitions
;;==============================================================================

(def fsm-definition-schema
  "JSON Schema for validating FSM definitions.
   Validates the structure of an FSM including states and transitions."
  {"$defs"
   {"prompt" {"type" "string"}

    "prompts" {"type" "array"
               "items" {"$ref" "#/$defs/prompt"}}

    "hat" {"anyOf" [{"type" "string"}
                    {"type" "object"}]}

    "state-hats" {"type" "array"
                  "items" {"$ref" "#/$defs/hat"}}

    "fsm-hat" {"type" "array"
               "items" [{"type" "string"}
                        {"type" "array" "items" {"type" "string"}}]
               "minItems" 2
               "maxItems" 2}

    "fsm-hats" {"type" "array"
                "items" {"$ref" "#/$defs/fsm-hat"}}

    "state" {"type" "object"
             "properties" {"id" {"type" "string"}
                           "description" {"type" "string"}
                           "action" {"type" "string"}
                           "config" {}
                           "prompts" {"$ref" "#/$defs/prompts"}
                           "hats" {"$ref" "#/$defs/state-hats"}}
             "required" ["id"]
             "additionalProperties" false}

    "xition-id" {"type" "array"
                 "items" {"type" "string"}
                 "minItems" 2
                 "maxItems" 2}

    "xition" {"type" "object"
              "properties" {"id" {"$ref" "#/$defs/xition-id"}
                            "label" {"type" "string"}
                            "description" {"type" "string"}
                            "prompts" {"$ref" "#/$defs/prompts"}
                            "schema" {}
                            "omit" {"type" "boolean"}}
              "required" ["id"]
              "additionalProperties" false}}

   "type" "object"
   "properties" {"id" {"type" "string"}
                 "description" {"type" "string"}
                 "version" {"type" "string"}
                 "schema" {}
                 "schemas" {"type" "object"}
                 "prompts" {"$ref" "#/$defs/prompts"}
                 "hats" {"$ref" "#/$defs/fsm-hats"}
                 "states" {"type" "array"
                           "items" {"$ref" "#/$defs/state"}}
                 "xitions" {"type" "array"
                            "items" {"$ref" "#/$defs/xition"}}}
   "required" ["id" "states" "xitions"]
   "additionalProperties" false})

(defn valid-fsm?
  "Check if an FSM definition is structurally valid.
   Returns boolean. Logs errors if invalid."
  [fsm]
  (let [{:keys [valid? errors]} (validate fsm-definition-schema fsm)]
    (when-not valid?
      (log/errorf "Invalid FSM: %s" (pr-str errors)))
    valid?))

(defmacro def-fsm
  "Define an FSM with compile-time structural validation.
   Validates that the FSM conforms to the FSM JSON Schema."
  [name fsm]
  `(def ~name
     (let [f# ~fsm]
       (assert (valid-fsm? f#) (str "Invalid FSM: " '~name))
       f#)))

;;==============================================================================
;; Schema Registry (for FSM schemas with $defs)
;;==============================================================================

(defn build-schema-registry
  "Build a combined $defs map from FSM schemas and additional context.
   
   Args:
     fsm     - FSM definition with optional \"schemas\" field
     context - Optional context with :schema/defs for additional definitions
   
   Returns:
     Map of definition-name -> JSON Schema for use as $defs"
  ([fsm] (build-schema-registry fsm {}))
  ([fsm context]
   (merge (get fsm "schemas" {})
          (get context :schema/defs {}))))

(defn validate-with-registry
  "Validate a value against a schema, using FSM's schema registry for $refs.
   
   Args:
     registry - Map of def-name -> JSON Schema (from build-schema-registry)
     schema   - The schema to validate against (may contain $refs)
     value    - The value to validate
   
   Returns:
     {:valid? true} or {:valid? false :errors [...]}"
  [registry schema value]
  (validate schema value registry))

;;==============================================================================
;; Schema Expansion for LLM Prompts
;;==============================================================================

(defn expand-refs
  "Recursively expand all $ref references in a schema using the registry.
   
   Use this to prepare schemas for LLM prompts where the LLM cannot
   resolve JSON Schema $refs.
   
   Args:
     schema   - A JSON Schema (may contain $ref)
     registry - Map of def-name -> JSON Schema
   
   Returns:
     Schema with all $refs expanded inline."
  [schema registry]
  (cond
    ;; Handle $ref
    (and (map? schema) (contains? schema "$ref"))
    (let [ref-path (get schema "$ref")
          ;; Extract def name from "#/$defs/name"
          def-name (when (string? ref-path)
                     (last (re-find #"#/\$defs/(.+)" ref-path)))]
      (if-let [resolved (get registry def-name)]
        (expand-refs resolved registry)
        schema))

    ;; Handle maps - recurse into values
    (map? schema)
    (reduce-kv
     (fn [acc k v]
       (assoc acc k (expand-refs v registry)))
     {}
     schema)

    ;; Handle vectors - recurse into elements
    (vector? schema)
    (mapv #(expand-refs % registry) schema)

    ;; Handle sequences - recurse into elements
    (sequential? schema)
    (map #(expand-refs % registry) schema)

    ;; Everything else - return as-is
    :else schema))
