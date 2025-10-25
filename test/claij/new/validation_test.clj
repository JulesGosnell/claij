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
      (is (not (:valid? result)))
      (is (re-find #"must be a map" (:error result)))))

  (testing "invalid schema - missing type"
    (let [result (validation/validate-schema {:properties {}})]
      (is (not (:valid? result)))
      (is (re-find #"type" (:error result)))))

  (testing "invalid schema - wrong type"
    (let [result (validation/validate-schema {:type "array" :properties {}})]
      (is (not (:valid? result)))
      (is (re-find #"object" (:error result)))))

  (testing "invalid schema - missing properties"
    (let [result (validation/validate-schema {:type "object"})]
      (is (not (:valid? result)))
      (is (re-find #"properties" (:error result))))))

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
      (is (re-find #"Missing required fields" (:error result)))
      (is (contains? (:missing-fields result) "state"))))

  (testing "multiple missing required fields"
    (let [response {}
          result (validation/validate-response response schema/base-schema)]
      (is (not (:valid? result)))
      (is (contains? (:missing-fields result) "answer"))
      (is (contains? (:missing-fields result) "state"))))

  (testing "wrong type for field"
    (let [response {:answer 42 :state "ready"}
          result (validation/validate-response response schema/base-schema)]
      (is (not (:valid? result)))
      (is (re-find #"expected string, got number" (:error result)))
      (is (= ["answer"] (:path result)))))

  (testing "response not a map"
    (let [result (validation/validate-response "not a map" schema/base-schema)]
      (is (not (:valid? result)))
      (is (re-find #"must be a JSON object" (:error result))))))

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
      (is (re-find #"confidence" (:error result)))
      (is (re-find #"number" (:error result))))))

(deftest test-validation-error-message
  (testing "formats missing fields error"
    (let [error {:error "Missing required fields: state, answer"
                 :missing-fields #{"state" "answer"}}
          message (validation/validation-error-message error)]
      (is (string? message))
      (is (re-find #"Missing required fields" message))))

  (testing "formats type error with path"
    (let [error {:error "Field 'answer': expected string, got number"
                 :path ["answer"]}
          message (validation/validation-error-message error)]
      (is (string? message))
      (is (re-find #"\$\.answer" message))))

  (testing "formats error without path"
    (let [error {:error "Response must be a JSON object"}
          message (validation/validation-error-message error)]
      (is (string? message))
      (is (re-find #"Response must be a JSON object" message)))))
