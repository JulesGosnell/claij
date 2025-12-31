(ns claij.util-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [claij.util :refer [trace json->clj clj->json index-by map-values ->key
                       should-retry? make-retrier assert-env-var
                       strip-keys-recursive]]))

;;==============================================================================
;; Pure utility function tests
;;==============================================================================

(deftest trace-test
  (testing "trace returns second argument unchanged"
    (is (= "hello" (trace "label" "hello")))
    (is (= 42 (trace "num" 42)))
    (is (= {:a 1} (trace "map" {:a 1})))
    (is (= [1 2 3] (trace "vec" [1 2 3])))
    (is (nil? (trace "nil" nil)))))

(deftest json->clj-test
  (testing "parses JSON with keyword keys"
    (is (= {:name "test"} (json->clj "{\"name\": \"test\"}")))
    (is (= {:a 1 :b 2} (json->clj "{\"a\": 1, \"b\": 2}")))
    (is (= {:nested {:key "value"}} (json->clj "{\"nested\": {\"key\": \"value\"}}"))))

  (testing "handles arrays"
    (is (= [1 2 3] (json->clj "[1, 2, 3]")))
    (is (= [{:a 1} {:b 2}] (json->clj "[{\"a\": 1}, {\"b\": 2}]"))))

  (testing "handles primitives"
    (is (= "hello" (json->clj "\"hello\"")))
    (is (= 42 (json->clj "42")))
    (is (= true (json->clj "true")))
    (is (nil? (json->clj "null")))))

(deftest clj->json-test
  (testing "serializes maps"
    (is (= "{\"name\":\"test\"}" (clj->json {:name "test"})))
    (is (= "{\"a\":1,\"b\":2}" (clj->json {:a 1 :b 2}))))

  (testing "serializes vectors"
    (is (= "[1,2,3]" (clj->json [1 2 3]))))

  (testing "round-trips correctly"
    (let [data {:name "test" :count 42 :items [1 2 3]}]
      (is (= data (json->clj (clj->json data)))))))

(deftest index-by-test
  (testing "indexes collection by function"
    (is (= {1 {:id 1 :name "a"}
            2 {:id 2 :name "b"}}
           (index-by :id [{:id 1 :name "a"} {:id 2 :name "b"}]))))

  (testing "handles empty collection"
    (is (= {} (index-by :id []))))

  (testing "last value wins for duplicates"
    (is (= {1 {:id 1 :name "second"}}
           (index-by :id [{:id 1 :name "first"} {:id 1 :name "second"}]))))

  (testing "works with string keys"
    (is (= {"a" {"key" "a" "val" 1}
            "b" {"key" "b" "val" 2}}
           (index-by #(get % "key") [{"key" "a" "val" 1} {"key" "b" "val" 2}])))))

(deftest map-values-test
  (testing "transforms values with key available"
    (is (= {:a "a:1" :b "b:2"}
           (map-values (fn [k v] (str (name k) ":" v)) {:a 1 :b 2}))))

  (testing "can ignore key"
    (is (= {:a 2 :b 4}
           (map-values (fn [_k v] (* v 2)) {:a 1 :b 2}))))

  (testing "handles empty map"
    (is (= {} (map-values (fn [k v] v) {})))))

(deftest ->key-test
  (testing "creates key accessor function"
    (let [get-name (->key "name")
          get-id (->key "id")]
      (is (= "test" (get-name {"name" "test" "id" 1})))
      (is (= 1 (get-id {"name" "test" "id" 1})))))

  (testing "returns nil for missing key"
    (let [get-missing (->key "missing")]
      (is (nil? (get-missing {"other" "value"}))))))

(deftest assert-env-var-test
  (testing "assert-env-var"
    (testing "returns value for existing env var"
      ;; PATH exists in virtually all Unix environments
      (is (string? (assert-env-var "PATH")))
      (is (not-empty (assert-env-var "PATH"))))

    (testing "throws for missing env var"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Missing environment variable"
                            (assert-env-var "DEFINITELY_NOT_A_REAL_VAR_98765"))))

    (testing "exception contains var name in data"
      (try
        (assert-env-var "FAKE_VAR_12345")
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= "FAKE_VAR_12345" (:var (ex-data e)))))))))

;;==============================================================================
;; Retry utility tests
;;==============================================================================

(deftest should-retry-test
  (testing "should-retry? predicate"
    (is (true? (should-retry? 0 3)) "First attempt should retry")
    (is (true? (should-retry? 1 3)) "Second attempt should retry")
    (is (true? (should-retry? 2 3)) "Third attempt should retry")
    (is (false? (should-retry? 3 3)) "Fourth attempt should not retry")
    (is (false? (should-retry? 4 3)) "Beyond max should not retry")))

(deftest make-retrier-basic-test
  (testing "Retrier executes operation when under limit"
    (let [retrier (make-retrier 3)
          counter (atom 0)
          operation #(swap! counter inc)]

      ;; First attempt (0)
      (retrier 0 operation)
      (is (= 1 @counter) "Operation executed on first attempt")

      ;; Second attempt (1)
      (retrier 1 operation)
      (is (= 2 @counter) "Operation executed on second attempt")

      ;; Third attempt (2)
      (retrier 2 operation)
      (is (= 3 @counter) "Operation executed on third attempt")))

  (testing "Retrier does not execute operation when at limit"
    (let [retrier (make-retrier 3)
          counter (atom 0)
          operation #(swap! counter inc)]

      ;; At limit (3)
      (retrier 3 operation)
      (is (= 0 @counter) "Operation not executed at limit")))

  (testing "Retrier calls on-max-retries when limit exceeded"
    (let [retrier (make-retrier 3)
          operation #(throw (Exception. "Should not be called"))
          max-called (atom false)
          on-max #(reset! max-called true)]

      (retrier 3 operation on-max)
      (is @max-called "on-max-retries callback was called")))

  (testing "Retrier works without on-max-retries callback"
    (let [retrier (make-retrier 3)
          operation #(throw (Exception. "Should not be called"))]

      ;; Should not throw even without callback
      (is (nil? (retrier 3 operation)) "Returns nil when no callback provided"))))

(deftest make-retrier-return-values-test
  (testing "Retrier returns operation result when retrying"
    (let [retrier (make-retrier 3)]
      (is (= :success (retrier 0 (constantly :success))))
      (is (= 42 (retrier 1 (constantly 42))))))

  (testing "Retrier returns on-max-retries result when limit exceeded"
    (let [retrier (make-retrier 3)]
      (is (= :failed (retrier 3
                              (constantly :should-not-happen)
                              (constantly :failed))))))

  (testing "Retrier returns nil when limit exceeded without callback"
    (let [retrier (make-retrier 3)]
      (is (nil? (retrier 3 (constantly :should-not-happen)))))))

(deftest make-retrier-async-simulation-test
  (testing "Simulates async retry pattern like open-router-async"
    (let [retrier (make-retrier 3)
          attempts (atom [])

          ;; Simulates recursive async calls
          mock-async-call
          (fn mock-call [attempt-num]
            (retrier
             attempt-num
              ;; operation: simulate async call that might fail
             (fn []
               (swap! attempts conj attempt-num)
               (if (< attempt-num 2)
                  ;; Simulate failure by recursively calling
                 (mock-call (inc attempt-num))
                  ;; Success on third attempt
                 :success))
              ;; on-max-retries
             (fn [] :max-retries-exceeded)))]

      (is (= :success (mock-async-call 0)))
      (is (= [0 1 2] @attempts) "Made 3 attempts before success")))

  (testing "Simulates exhausting retries"
    (let [retrier (make-retrier 3)
          attempts (atom [])

          mock-async-call
          (fn mock-call [attempt-num]
            (retrier
             attempt-num
              ;; operation: always fail
             (fn []
               (swap! attempts conj attempt-num)
               (mock-call (inc attempt-num)))
              ;; on-max-retries
             (fn [] :max-retries-exceeded)))]

      (is (= :max-retries-exceeded (mock-async-call 0)))
      (is (= [0 1 2] @attempts) "Made exactly 3 attempts"))))

;;==============================================================================
;; JSON Schema sanitization
;;==============================================================================

(deftest strip-keys-recursive-test
  (testing "Removes specified keys at top level"
    (is (= {"type" "object" "properties" {}}
           (strip-keys-recursive #{"additionalProperties"}
                                 {"type" "object" "additionalProperties" false "properties" {}}))))
  
  (testing "Removes multiple keys at top level"
    (is (= {"type" "object"}
           (strip-keys-recursive #{"additionalProperties" "$schema"}
                                 {"type" "object" 
                                  "additionalProperties" false 
                                  "$schema" "http://json-schema.org/draft-07/schema#"}))))
  
  (testing "Recursively removes from nested objects"
    (is (= {"type" "object"
            "properties" {"name" {"type" "string"}
                          "config" {"type" "object"
                                    "properties" {"timeout" {"type" "integer"}}}}}
           (strip-keys-recursive
            #{"additionalProperties" "$schema"}
            {"type" "object"
             "additionalProperties" false
             "$schema" "http://json-schema.org/draft-07/schema#"
             "properties" {"name" {"type" "string" "additionalProperties" false}
                           "config" {"type" "object"
                                     "additionalProperties" false
                                     "properties" {"timeout" {"type" "integer"}}}}}))))
  
  (testing "Handles arrays"
    (is (= {"type" "array" "items" {"type" "string"}}
           (strip-keys-recursive
            #{"additionalProperties" "$schema"}
            {"type" "array" "additionalProperties" false "items" {"type" "string" "$schema" "foo"}}))))
  
  (testing "Preserves non-targeted fields"
    (is (= {"type" "object"
            "required" ["name"]
            "properties" {"name" {"type" "string" "description" "The name"}}}
           (strip-keys-recursive
            #{"additionalProperties"}
            {"type" "object"
             "required" ["name"]
             "additionalProperties" false
             "properties" {"name" {"type" "string" "description" "The name"}}}))))
  
  (testing "Handles empty keys set"
    (is (= {"a" 1 "b" 2}
           (strip-keys-recursive #{} {"a" 1 "b" 2}))))
  
  (testing "Handles primitives"
    (is (= "hello" (strip-keys-recursive #{"x"} "hello")))
    (is (= 42 (strip-keys-recursive #{"x"} 42)))
    (is (= nil (strip-keys-recursive #{"x"} nil)))))
