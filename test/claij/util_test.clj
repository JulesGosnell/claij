(ns claij.util-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [claij.util :refer [should-retry? make-retrier]]))

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
