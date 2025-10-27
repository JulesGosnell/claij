(require '[m3.validate :as m3])

(println "Test with draft-07 $schema:")
(prn (m3/validate {}
                   {"$schema" "http://json-schema.org/draft-07/schema"
                    "type" "string"}
                   {}
                   "hello"))
