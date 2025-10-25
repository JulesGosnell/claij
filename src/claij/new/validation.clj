(ns claij.new.validation
  "Validation of JSON schemas and responses using M3.
  
  M3 is a pure Clojure JSON Schema validator that supports both CLJ and CLJS."
  (:require [clojure.set :refer [difference]]
            [clojure.string :refer [join]]
            [m3.validate :as m3]))

(defn validate-schema
  "Validate that a schema is well-formed.
  
  Returns:
  - {:valid? true} if valid
  - {:valid? false :error <message>} if invalid"
  [schema]
  (cond
    (not (map? schema))
    {:valid? false :error "Schema must be a map"}

    (not= "object" (:type schema))
    {:valid? false :error "Root schema type must be 'object'"}

    (not (contains? schema :properties))
    {:valid? false :error "Schema must have properties"}

    :else
    {:valid? true}))

(defn- normalize-schema-keys
  "Normalize schema to use string keys for M3 compatibility.
  
  M3 expects pure JSON Schema with string keys."
  [schema]
  (if (map? schema)
    (reduce-kv
     (fn [m k v]
       (let [k-str (if (keyword? k) (name k) (str k))
             v-norm (cond
                      (map? v) (normalize-schema-keys v)
                      (sequential? v) (mapv #(if (map? %) (normalize-schema-keys %) %) v)
                      :else v)]
         (assoc m k-str v-norm)))
     {}
     schema)
    schema))

(defn- normalize-response-keys
  "Normalize response to use string keys for M3 compatibility."
  [response]
  (if (map? response)
    (reduce-kv
     (fn [m k v]
       (assoc m (if (keyword? k) (name k) (str k)) v))
     {}
     response)
    response))

(defn validate-response
  "Validate that a response conforms to the schema using M3.
  
  Returns:
  - {:valid? true :response <response>} if valid
  - {:valid? false :error <message> :path <path>} if invalid"
  [response schema]
  (let [;; Normalize both to string keys for M3
        norm-schema (normalize-schema-keys schema)
        ;; Add $schema if not present - M3 uses this to determine the draft version
        norm-schema (if (contains? norm-schema "$schema")
                      norm-schema
                      (assoc norm-schema "$schema" "https://json-schema.org/draft/2020-12/schema"))
        norm-response (normalize-response-keys response)
        ;; M3 context - empty since $schema in the schema determines the draft
        m3-context {}
        ;; Validate with M3
        result (m3/validate m3-context norm-schema {} norm-response)]

    (if (:valid? result)
      {:valid? true :response response}
      ;; Extract more detailed error from nested structure
      (let [first-error (-> result :errors first)
            ;; Try to get nested error with more detail
            nested-errors (-> first-error :errors)
            detailed-error (when (seq nested-errors)
                             (-> nested-errors first :message))
            error-msg (or detailed-error (:message first-error))
            path (:document-path first-error [])]
        {:valid? false
         :error error-msg
         :path path}))))

(defn validation-error-message
  "Format a validation error for display to LLM or user.
  
  Takes a validation result (from validate-response) and formats it
  as a clear error message with path information."
  [{:keys [error path missing-fields]}]
  (cond
    missing-fields
    (str error " at root level")

    (seq path)
    (str error " at path: $." (join "." path))

    :else
    error))

(comment
  ;; Example usage
  (def test-schema
    {:type "object"
     :required ["answer" "state"]
     :properties {:answer {:type "string"}
                  :state {:type "string"}
                  :summary {:type "string"}}})

  ;; Valid response
  (validate-response {:answer "Hello" :state "ready"} test-schema)
  ;=> {:valid? true :response {...}}

  ;; Missing required field
  (validate-response {:answer "Hello"} test-schema)
  ;=> {:valid? false :error "..." ...}

  ;; Wrong type
  (validate-response {:answer 42 :state "ready"} test-schema)
  ;=> {:valid? false :error "..." ...}
  )
