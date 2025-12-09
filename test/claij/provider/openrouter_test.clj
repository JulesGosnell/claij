(ns claij.provider.openrouter-test
  "Tests for OpenRouter provider transforms."
  (:require
   [clojure.test :refer [deftest is testing]]
   [claij.provider.openrouter :as openrouter]))

(deftest openrouter->openrouter-test
  (testing "Request transform produces correct structure"
    (let [result (openrouter/openrouter->openrouter
                  "anthropic"
                  "claude-sonnet-4"
                  [{"role" "user" "content" "hello"}]
                  "test-api-key")]
      (is (= "https://openrouter.ai/api/v1/chat/completions" (:url result)))
      (is (= "Bearer test-api-key" (get-in result [:headers "Authorization"])))
      (is (= "application/json" (get-in result [:headers "content-type"])))
      (is (= "anthropic/claude-sonnet-4" (get-in result [:body :model])))
      (is (= [{"role" "user" "content" "hello"}] (get-in result [:body :messages])))))

  (testing "Model string combines provider and model"
    (let [result (openrouter/openrouter->openrouter "google" "gemini-2.0-flash" [] "key")]
      (is (= "google/gemini-2.0-flash" (get-in result [:body :model]))))))

(deftest openrouter->openrouter-response-test
  (testing "Extracts content from OpenRouter response"
    (let [response {:choices [{:message {:content "Hello there!"}}]}]
      (is (= "Hello there!" (openrouter/openrouter->openrouter-response response)))))

  (testing "Handles nested response structure"
    (let [response {:choices [{:message {:content "Response text"} :other "ignored"}]
                    :usage {:tokens 100}}]
      (is (= "Response text" (openrouter/openrouter->openrouter-response response))))))
