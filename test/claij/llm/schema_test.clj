(ns claij.llm.schema-test
  "Tests for LLM API schema loaders."
  (:require
   [clojure.test :refer [deftest testing is]]
   [claij.llm.openai-schema :as openai]
   [claij.llm.anthropic-schema :as anthropic]
   [claij.llm.google-schema :as google]))

;;------------------------------------------------------------------------------
;; OpenAI Schema Tests

(deftest openai-schema-info-test
  (testing "schema-info returns valid metadata"
    (let [info (openai/schema-info)]
      (is (string? (:openapi-version info)))
      (is (string? (:api-title info)))
      (is (pos? (:schema-count info))))))

(deftest openai-get-component-schema-test
  (testing "get-component-schema retrieves schema by name"
    (let [schema (openai/get-component-schema "CreateChatCompletionResponse")]
      (is (map? schema))
      (is (contains? schema "type"))))

  (testing "get-component-schema returns nil for unknown schema"
    (is (nil? (openai/get-component-schema "NonExistentSchema")))))

(deftest openai-delayed-schemas-test
  (testing "delayed schemas can be dereferenced"
    (is (map? @openai/chat-completion-response-schema))
    (is (map? @openai/chat-completion-request-schema))
    (is (map? @openai/chat-completion-message-schema))
    (is (map? @openai/tool-call-schema))))

(deftest openai-simplified-schemas-test
  (testing "simplified schemas are valid JSON Schema objects"
    (is (= "object" (get openai/response-schema "type")))
    (is (= "object" (get openai/request-schema "type")))
    (is (= "object" (get openai/tool-call-item-schema "type")))
    (is (contains? openai/response-schema "required"))
    (is (contains? openai/request-schema "required"))))

;;------------------------------------------------------------------------------
;; Anthropic Schema Tests

(deftest anthropic-schema-info-test
  (testing "schema-info returns valid metadata"
    (let [info (anthropic/schema-info)]
      (is (string? (:openapi-version info)))
      (is (string? (:api-title info)))
      (is (pos? (:schema-count info))))))

(deftest anthropic-get-component-schema-test
  (testing "get-component-schema retrieves schema by name"
    (let [schema (anthropic/get-component-schema "Message")]
      (is (map? schema))))

  (testing "get-component-schema returns nil for unknown schema"
    (is (nil? (anthropic/get-component-schema "NonExistentSchema")))))

(deftest anthropic-simplified-schemas-test
  (testing "simplified schemas are valid JSON Schema objects"
    (is (= "object" (get anthropic/response-schema "type")))
    (is (= "object" (get anthropic/request-schema "type")))
    (is (= "object" (get anthropic/tool-use-block-schema "type")))
    (is (contains? anthropic/response-schema "required"))
    (is (contains? anthropic/request-schema "required"))))

;;------------------------------------------------------------------------------
;; Google Schema Tests

(deftest google-schema-info-test
  (testing "schema-info returns valid metadata"
    (let [info (google/schema-info)]
      (is (string? (:openapi-version info)))
      (is (string? (:api-title info)))
      (is (pos? (:schema-count info))))))

(deftest google-get-component-schema-test
  (testing "get-component-schema retrieves schema by name"
    (let [schema (google/get-component-schema "GenerateContentResponse")]
      (is (map? schema))))

  (testing "get-component-schema returns nil for unknown schema"
    (is (nil? (google/get-component-schema "NonExistentSchema")))))

(deftest google-simplified-schemas-test
  (testing "simplified schemas are valid JSON Schema objects"
    (is (= "object" (get google/response-schema "type")))
    (is (= "object" (get google/request-schema "type")))
    (is (= "object" (get google/function-call-schema "type")))
    (is (contains? google/request-schema "required"))))
