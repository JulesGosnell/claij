(ns claij.test-m3-issue
  "Demonstrating m3 validation issue"
  (:require [m3.validate :as m3]))

(println "\n=== Test 1: Simple string validation (from m3 README) ===")
(try
  (def result1 (m3/validate {} {"type" "string"} {} "hello"))
  (println "Success! Result:")
  (prn result1)
  (catch Exception e
    (println "ERROR:")
    (println (.getMessage e))
    (println "\nStack trace:")
    (.printStackTrace e)))

(println "\n=== Test 2: With draft-07 specified ===")
(try
  (def result2 (m3/validate {:draft :draft7} {"type" "string"} {} "hello"))
  (println "Success! Result:")
  (prn result2)
  (catch Exception e
    (println "ERROR:")
    (println (.getMessage e))))

(println "\n=== Test 3: Array validation (from m3 README) ===")
(try
  (def result3 (m3/validate {} {"type" "array" "items" {"type" "string"}} {} ["hello" "goodbye"]))
  (println "Success! Result:")
  (prn result3)
  (catch Exception e
    (println "ERROR:")
    (println (.getMessage e))))

(println "\n=== Test 4: Object validation (our use case) ===")
(try
  (def schema {"type" "object"
               "required" ["answer" "state"]
               "properties" {"answer" {"type" "string"}
                             "state" {"type" "string"}}})
  (def response {"answer" "Hello" "state" "ready"})

  (def result4 (m3/validate {:draft :draft7} schema {} response))
  (println "Success! Result:")
  (prn result4)
  (catch Exception e
    (println "ERROR:")
    (println (.getMessage e))
    (println "\nStack trace:")
    (.printStackTrace e)))

(println "\n=== Done ===")
