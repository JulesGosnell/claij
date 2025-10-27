(require '[m3.validate :as m3])

(println "Test 1: Simple string with $schema")
(prn (m3/validate {}
                   {"$schema" "https://json-schema.org/draft/2020-12/schema"
                    "type" "string"}
                   {}
                   "hello"))

(println "\nTest 2: Object with $schema")
(prn (m3/validate {}
                   {"$schema" "https://json-schema.org/draft/2020-12/schema"
                    "type" "object"
                    "required" ["answer"]
                    "properties" {"answer" {"type" "string"}}}
                   {}
                   {"answer" "Hello"}))
