(require '[claij.new.validation :as v])

(def schema
  {:$schema "http://json-schema.org/draft-07/schema#"
   :type "object"
   :required ["answer" "state"]
   :properties {:answer {:type "string"}
                :state {:type "string"}}})

(println "Test 1: Missing required field")
(def r1 (v/validate-response {:answer "Hello"} schema))
(println "Error:" (:error r1))

(println "\nTest 2: Wrong type")
(def r2 (v/validate-response {:answer "Hello" :state 42} schema))
(println "Error:" (:error r2))
