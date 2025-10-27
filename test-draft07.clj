(require '[m3.validate :as m3])

(def schema
  {"$schema" "http://json-schema.org/draft-07/schema#"
   "type" "object"
   "required" ["answer" "state"]
   "properties" {"answer" {"type" "string"}
                 "state" {"type" "string"}}})

(println "Test 1: Valid response with draft-07")
(prn (m3/validate {} schema {} {"answer" "Hello" "state" "ready"}))

(println "\nTest 2: Invalid - missing field")
(prn (m3/validate {} schema {} {"answer" "Hello"}))

(println "\nTest 3: Invalid - wrong type")
(prn (m3/validate {} schema {} {"answer" 42 "state" "ready"}))
