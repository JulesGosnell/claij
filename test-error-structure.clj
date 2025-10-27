(require '[m3.validate :as m3]
         '[clojure.pprint :as pp])

(def schema
  {"$schema" "http://json-schema.org/draft-07/schema#"
   "type" "object"
   "required" ["answer" "state"]
   "properties" {"answer" {"type" "string"}
                 "state" {"type" "string"}}})

(println "Test: Wrong type")
(def result (m3/validate {} schema {} {"answer" "Hello" "state" 42}))
(pp/pprint result)

(println "\n\nExtracting nested errors:")
(def first-error (-> result :errors first))
(println "First error message:" (:message first-error))
(def nested (-> first-error :errors first))
(println "Nested error message:" (:message nested))
(def double-nested (-> nested :errors first))
(println "Double-nested error message:" (:message double-nested))
