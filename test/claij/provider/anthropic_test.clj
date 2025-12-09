(ns claij.provider.anthropic-test
  "Tests for Anthropic provider transforms."
  (:require
   [clojure.test :refer [deftest is testing]]
   [claij.provider.anthropic :as anthropic]))

(deftest translate-model-test
  (testing "Translates OpenRouter model names to Anthropic API names"
    (is (= "claude-sonnet-4-5" (anthropic/translate-model "claude-sonnet-4.5")))
    (is (= "claude-sonnet-4-20250514" (anthropic/translate-model "claude-sonnet-4")))
    (is (= "claude-opus-4-5" (anthropic/translate-model "claude-opus-4.5")))
    (is (= "claude-haiku-4-5" (anthropic/translate-model "claude-haiku-4.5"))))

  (testing "Unknown models pass through unchanged"
    (is (= "some-new-model" (anthropic/translate-model "some-new-model")))))

(deftest openrouter->anthropic-test
  (testing "Request transform produces correct structure"
    (let [result (anthropic/openrouter->anthropic
                  "claude-sonnet-4.5"
                  [{"role" "user" "content" "hello"}]
                  "test-api-key")]
      (is (= "https://api.anthropic.com/v1/messages" (:url result)))
      (is (= "test-api-key" (get-in result [:headers "x-api-key"])))
      (is (= "2023-06-01" (get-in result [:headers "anthropic-version"])))
      (is (= "application/json" (get-in result [:headers "content-type"])))
      (is (= "claude-sonnet-4-5" (get-in result [:body :model])))
      (is (= 4096 (get-in result [:body :max_tokens])))
      (is (= [{"role" "user" "content" "hello"}] (get-in result [:body :messages])))))

  (testing "System messages are extracted to top-level field"
    (let [result (anthropic/openrouter->anthropic
                  "claude-sonnet-4"
                  [{"role" "system" "content" "You are helpful"}
                   {"role" "user" "content" "hello"}]
                  "key")]
      (is (= "You are helpful" (get-in result [:body :system])))
      (is (= [{"role" "user" "content" "hello"}] (get-in result [:body :messages])))))

  (testing "Multiple system messages are combined"
    (let [result (anthropic/openrouter->anthropic
                  "claude-sonnet-4"
                  [{"role" "system" "content" "First instruction"}
                   {"role" "system" "content" "Second instruction"}
                   {"role" "user" "content" "hello"}]
                  "key")]
      (is (= "First instruction\n\nSecond instruction" (get-in result [:body :system])))))

  (testing "No system field when no system messages"
    (let [result (anthropic/openrouter->anthropic
                  "claude-sonnet-4"
                  [{"role" "user" "content" "hello"}]
                  "key")]
      (is (nil? (get-in result [:body :system]))))))

(deftest anthropic->openrouter-test
  (testing "Extracts content from Anthropic response"
    (let [response {:content [{:type "text" :text "Hello there!"}]}]
      (is (= "Hello there!" (anthropic/anthropic->openrouter response)))))

  (testing "Handles response with multiple content blocks"
    (let [response {:content [{:type "text" :text "First"}
                              {:type "text" :text "Second"}]
                    :usage {:input_tokens 10 :output_tokens 20}}]
      (is (= "First" (anthropic/anthropic->openrouter response))))))
