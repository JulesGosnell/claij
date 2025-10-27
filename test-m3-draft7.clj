(require '[m3.validate :as m3])

;; Test with draft7
(def result-draft7
  (m3/validate {:draft :draft7}
               {"type" "string"}
               {}
               "hello"))

(println "Draft7:" result-draft7)

;; Test object validation with draft7
(def object-result-draft7
  (m3/validate {:draft :draft7}
               {"type" "object"
                "required" ["answer" "state"]
                "properties" {"answer" {"type" "string"}
                              "state" {"type" "string"}}}
               {}
               {"answer" "Hello" "state" "ready"}))

(println "Object draft7:" object-result-draft7)
