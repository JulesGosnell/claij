(ns claij.new.validation-test
  (:require [clojure.test :refer [deftest is testing]]
            [claij.new.validation :as validation]
            [claij.new.schema :as schema]))

(deftest test-validate-schema
  (testing "valid schema"
    (let [result (validation/validate-schema schema/base-schema)]
      (is (:valid? result))))

  (testing "invalid schema - not a map"
    (let [result (validation/validate-schema "not a map")]
      (is (not (:valid? result)))))

  (testing "invalid schema - missing type"
    (let [result (validation/validate-schema {:properties {}})]
      (is (not (:valid? result)))))

  (testing "invalid schema - wrong type"
    (let [result (validation/validate-schema {:type "array" :properties {}})]
      (is (not (:valid? result)))))

  (testing "invalid schema - missing properties"
    (let [result (validation/validate-schema {:type "object"})]
      (is (not (:valid? result))))))

(deftest test-validate-response
  (testing "valid response"
    (let [response {:answer "Hello" :state "ready"}
          result (validation/validate-response response schema/base-schema)]
      (is (:valid? result))
      (is (= response (:response result)))))

  (testing "response with extra fields is valid"
    (let [response {:answer "Hello" :state "ready" :extra "field"}
          result (validation/validate-response response schema/base-schema)]
      (is (:valid? result))))

  (testing "missing required field"
    (let [response {:answer "Hello"}
          result (validation/validate-response response schema/base-schema)]
      (is (not (:valid? result)))
      ;; Just check that validation failed, not specific error
      (is (string? (:error result)))))

  (testing "multiple missing required fields"
    (let [response {}
          result (validation/validate-response response schema/base-schema)]
      (is (not (:valid? result)))
      ;; Just check that validation failed
      (is (string? (:error result)))))

  (testing "wrong type for field"
    (let [response {:answer 42 :state "ready"}
          result (validation/validate-response response schema/base-schema)]
      (is (not (:valid? result)))
      ;; Just check that validation failed
      (is (string? (:error result)))))

  (testing "response not a map"
    (let [result (validation/validate-response "not a map" schema/base-schema)]
      (is (not (:valid? result)))
      ;; Just check that validation failed
      (is (string? (:error result))))))

(deftest test-validate-response-with-extended-schema
  (testing "validates against extended schema"
    (let [extended-schema (schema/compose-schema
                           schema/base-schema
                           [{:properties {:summary {:type "string"}}}])
          response {:answer "Hello" :state "ready" :summary "Test"}
          result (validation/validate-response response extended-schema)]
      (is (:valid? result))))

  (testing "wrong type in extended field"
    (let [extended-schema (schema/compose-schema
                           schema/base-schema
                           [{:properties {:confidence {:type "number"}}}])
          response {:answer "Hello" :state "ready" :confidence "high"}
          result (validation/validate-response response extended-schema)]
      (is (not (:valid? result)))
      ;; Just check that validation failed
      (is (string? (:error result))))))

(deftest test-validation-error-message
  (testing "formats error message"
    (let [error {:error "Test error message"}
          message (validation/validation-error-message error)]
      (is (string? message))
      (is (= "Test error message" message))))

  (testing "formats error with path"
    (let [error {:error "Test error" :path ["answer"]}
          message (validation/validation-error-message error)]
      (is (string? message))
      (is (.contains message "$.answer"))))

  (testing "formats error without path"
    (let [error {:error "Generic error"}
          message (validation/validation-error-message error)]
      (is (string? message))
      (is (= "Generic error" message)))))
