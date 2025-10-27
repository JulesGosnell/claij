(require '[claij.new.validation :as v])

(def test-schema
  {:type "object"
   :required ["answer" "state"]
   :properties {:answer {:type "string"}
                :state {:type "string"}}})

(println "Test 1: Valid response")
(prn (v/validate-response {:answer "Hello" :state "ready"} test-schema))

(println "\nTest 2: Invalid - missing required field")
(prn (v/validate-response {:answer "Hello"} test-schema))

(println "\nTest 3: Invalid - wrong type")
(prn (v/validate-response {:answer 42 :state "ready"} test-schema))

(println "\nTest 4: With explicit $schema (draft-07)")
(def schema-with-draft
  {:$schema "http://json-schema.org/draft-07/schema"
   :type "object"
   :required ["answer"]
   :properties {:answer {:type "string"}}})
(prn (v/validate-response {:answer "Hello"} schema-with-draft))
