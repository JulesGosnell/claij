(require '[m3.validate :as m3])

(def result
  (m3/validate {}
               {"$schema" "https://json-schema.org/draft/2020-12/schema"
                "type" "string"}
               {}
               "hello"))

(println "Result:" result)
