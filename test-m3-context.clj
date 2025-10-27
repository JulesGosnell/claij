(require '[m3.validate :as m3])

;; Test using the context approach (what claij currently does)
(def result-with-context
  (m3/validate {:draft :draft2020-12}
               {"type" "string"}
               {}
               "hello"))

(println "With context:" result-with-context)

;; Test validation of an object
(def object-result
  (m3/validate {:draft :draft2020-12}
               {"type" "object"
                "required" ["answer" "state"]
                "properties" {"answer" {"type" "string"}
                              "state" {"type" "string"}}}
               {}
               {"answer" "Hello" "state" "ready"}))

(println "Object result:" object-result)
