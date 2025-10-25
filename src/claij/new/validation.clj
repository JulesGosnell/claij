(ns claij.new.validation
  "Validation of JSON schemas and responses.
  
  Currently uses basic hand-rolled validation.
  TODO: Integrate with M3 for proper JSON Schema validation."
  (:require [clojure.set :refer [difference]]
            [clojure.string :refer [join]]))
  ;; (:require [m3.validate :as m3])  ; TODO: Enable M3 integration

(defn validate-schema
  "Validate that a schema is well-formed.
  
  Basic validation - checks structure.
  TODO: Use M3 for proper JSON Schema validation.
  
  Returns:
  - {:valid? true} if valid
  - {:valid? false :error <message>} if invalid"
  [schema]
  (cond
    (not (map? schema))
    {:valid? false :error "Schema must be a map"}

    (not (:type schema))
    {:valid? false :error "Schema must have :type field"}

    (not= "object" (:type schema))
    {:valid? false :error "Root schema type must be 'object'"}

    (not (:properties schema))
    {:valid? false :error "Schema must have :properties"}

    (not (map? (:properties schema)))
    {:valid? false :error "Schema :properties must be a map"}

    :else
    {:valid? true}))

(defn validate-response
  "Validate that a response conforms to the schema.
  
  Basic validation - checks required fields and types.
  TODO: Use M3 for proper JSON Schema validation with detailed error paths.
  
  Returns:
  - {:valid? true :response <response>} if valid
  - {:valid? false :error <message> :path <path>} if invalid"
  [response schema]
  (cond
    (not (map? response))
    {:valid? false
     :error "Response must be a JSON object"
     :path []}

    :else
    (let [required-fields (set (:required schema))
          response-keys (set (map name (keys response)))
          missing (difference required-fields response-keys)]

      (if (seq missing)
        {:valid? false
         :error (str "Missing required fields: " (join ", " missing))
         :path []
         :missing-fields missing}

        ;; Check types of provided fields
        (let [property-specs (:properties schema)
              type-errors
              (for [[field-name field-value] response
                    :let [;; Try both keyword and string keys
                          spec (or (get property-specs field-name)
                                   (get property-specs (name field-name))
                                   (get property-specs (keyword (name field-name))))]
                    :when spec
                    :let [expected-type (:type spec)
                          actual-type (cond
                                        (string? field-value) "string"
                                        (number? field-value) "number"
                                        (boolean? field-value) "boolean"
                                        (vector? field-value) "array"
                                        (map? field-value) "object"
                                        (nil? field-value) "null"
                                        :else "unknown")]
                    :when (and expected-type (not= expected-type actual-type))]
                {:field field-name
                 :expected expected-type
                 :actual actual-type
                 :path [(name field-name)]})]

          (if (seq type-errors)
            (let [first-error (first type-errors)]
              {:valid? false
               :error (format "Field '%s': expected %s, got %s"
                              (name (:field first-error))
                              (:expected first-error)
                              (:actual first-error))
               :path (:path first-error)})
            {:valid? true :response response}))))))

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

;; TODO: M3 Integration
;; When ready to integrate M3:
;; 1. Uncomment the require above
;; 2. Replace validate-schema and validate-response with M3 calls:
;;    (m3/validate {:draft :draft7} schema {} document)
;; 3. Update tests to match M3's error structure
;; 4. Handle M3-specific edge cases

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
  ;=> {:valid? false :error "Missing required fields: state" ...}

  ;; Wrong type
  (validate-response {:answer 42 :state "ready"} test-schema)
  ;=> {:valid? false :error "Field 'answer': expected string, got number" ...}
  )
