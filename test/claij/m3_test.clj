(ns claij.m3-test
  "Tests for m3 JSON Schema validation, particularly $ref fragment resolution.
   
   These tests explore how m3 handles local fragment references like {'$ref' '#/$defs/...'}
   to understand what context is needed for proper resolution."
  (:require
   [clojure.test :refer [deftest testing is]]
   [m3.validate :refer [validate make-context]]))

;;------------------------------------------------------------------------------
;; Test 1: Direct validation with $defs - BASELINE
;;------------------------------------------------------------------------------

(deftest simple-def-validation-test
  (testing "Validating directly against a schema with $defs (no $ref)"
    (let [root-schema
          {"$schema" "https://json-schema.org/draft/2020-12/schema"
           "$defs"
           {"a-string" {"type" "string"}
            "a-number" {"type" "number"}}
           "type" "object"
           "properties"
           {"name" {"$ref" "#/$defs/a-string"}
            "age" {"$ref" "#/$defs/a-number"}}}

          valid-doc {"name" "Alice" "age" 30}
          invalid-doc {"name" "Bob" "age" "thirty"}]

      (testing "Valid document passes"
        (is (:valid? (validate {:draft :draft2020-12} root-schema {} valid-doc))))

      (testing "Invalid document fails"
        (is (not (:valid? (validate {:draft :draft2020-12} root-schema {} invalid-doc))))))))

;;------------------------------------------------------------------------------
;; Test 2: Validating with isolated $ref - THE PROBLEM
;;------------------------------------------------------------------------------

(deftest isolated-ref-validation-test
  (testing "Validating against an isolated $ref without root schema access"
    (let [root-schema
          {"$schema" "https://json-schema.org/draft/2020-12/schema"
           "$defs"
           {"a-string" {"type" "string"}}}

          ;; This is what we're doing in FSM - just the ref, no $defs context
          isolated-ref-schema {"$ref" "#/$defs/a-string"}

          valid-doc "hello"
          invalid-doc 42]

      (testing "With no context - $ref resolution fails"
        (let [result (validate {:draft :draft2020-12} isolated-ref-schema {} valid-doc)]
          (println "\nTest 2a - No context:")
          (println "  Result:" result)
          ;; This will likely fail because m3 can't resolve #/$defs/a-string
          (is (not (:valid? result)) "Expected failure: $ref cannot be resolved without root schema")))

      (testing "With uri->schema providing root - $ref should resolve"
        ;; This is closer to what we're trying in fsm.clj
        (let [context {:draft :draft2020-12
                       :uri->schema (fn [_ctx _path uri]
                                      (println "\nTest 2b - uri->schema called with:" uri)
                                      ;; Return context, path, and schema
                                      ;; But this doesn't help with local fragment refs
                                      nil)}
              result (validate context isolated-ref-schema {} valid-doc)]
          (println "  Result:" result)
          (is (not (:valid? result)) "Expected failure: uri->schema doesn't help with local fragments"))))))

;;------------------------------------------------------------------------------
;; Test 3: Using make-context to build proper resolution - THE SOLUTION?
;;------------------------------------------------------------------------------

(deftest make-context-test
  (testing "Using make-context to build full validation context with $defs"
    (let [root-schema
          {"$schema" "https://json-schema.org/draft/2020-12/schema"
           "$$id" "https://example.org/my-schema"
           "$defs"
           {"a-string" {"type" "string"}
            "a-number" {"type" "number"}}}

          ;; Build a context from the root schema
          c2 (make-context {:draft :draft2020-12} root-schema)

          ;; Now try validating with just a $ref
          isolated-ref-schema {"$ref" "#/$defs/a-string"}

          valid-doc "hello"
          invalid-doc 42]

      (println "\nTest 3 - Context from root schema:")
      (println "  Context keys:" (keys c2))
      (println "  :uri->path present?" (contains? c2 :uri->path))
      (println "  :path->uri present?" (contains? c2 :path->uri))

      (testing "Validating with proper context"
        (let [result (validate c2 isolated-ref-schema {} valid-doc)]
          (println "  Valid doc result:" result)
          (is (:valid? result) "Should pass: context provides $defs resolution")))

      (testing "Invalid doc should still fail"
        (let [result (validate c2 isolated-ref-schema {} invalid-doc)]
          (println "  Invalid doc result:" result)
          (is (not (:valid? result)) "Should fail: doc doesn't match type"))))))

;;------------------------------------------------------------------------------
;; Test 4: Complex nested $refs - REAL WORLD SCENARIO
;;------------------------------------------------------------------------------

(deftest nested-refs-test
  (testing "Complex schema with nested $defs and multiple $refs"
    (let [root-schema
          {"$schema" "https://json-schema.org/draft/2020-12/schema"
           "$$id" "https://example.org/person-schema"
           "$defs"
           {"name" {"type" "string" "minLength" 1}
            "positive-int" {"type" "integer" "minimum" 0}
            "address"
            {"type" "object"
             "properties"
             {"street" {"$ref" "#/$defs/name"}
              "number" {"$ref" "#/$defs/positive-int"}}
             "required" ["street" "number"]}
            "person"
            {"type" "object"
             "properties"
             {"name" {"$ref" "#/$defs/name"}
              "age" {"$ref" "#/$defs/positive-int"}
              "address" {"$ref" "#/$defs/address"}}
             "required" ["name" "age"]}}}

          ;; Build context from root
          c2 (make-context {:draft :draft2020-12} root-schema)

          ;; Validate against a nested ref
          person-ref {"$ref" "#/$defs/person"}

          valid-person
          {"name" "Alice"
           "age" 30
           "address" {"street" "Main St" "number" 123}}

          invalid-person-1
          {"name" "Bob"
           "age" -5 ;; negative age
           "address" {"street" "Oak Ave" "number" 456}}

          invalid-person-2
          {"name" "Charlie"
           "age" 25
           "address" {"street" "Elm St" "number" "seven"}}] ;; string instead of int

      (println "\nTest 4 - Nested refs:")

      (testing "Valid nested structure passes"
        (let [result (validate c2 person-ref {} valid-person)]
          (println "  Valid person result:" (:valid? result))
          (is (:valid? result))))

      (testing "Invalid nested field (negative age) fails"
        (let [result (validate c2 person-ref {} invalid-person-1)]
          (println "  Invalid person 1 result:" (:valid? result))
          (is (not (:valid? result)))))

      (testing "Invalid nested field (string number) fails"
        (let [result (validate c2 person-ref {} invalid-person-2)]
          (println "  Invalid person 2 result:" (:valid? result))
          (is (not (:valid? result))))))))