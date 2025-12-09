(ns claij.provider.openai-test
  "Tests for OpenAI provider transforms."
  (:require
   [clojure.test :refer [deftest is testing]]
   [claij.provider.openai :as openai]))

(deftest translate-model-test
  (testing "Unknown models pass through unchanged"
    (is (= "gpt-4o" (openai/translate-model "gpt-4o")))
    (is (= "gpt-4-turbo" (openai/translate-model "gpt-4-turbo")))))

(deftest openrouter->openai-test
  (testing "Request transform produces correct structure"
    (let [result (openai/openrouter->openai
                  "gpt-4o"
                  [{"role" "user" "content" "hello"}]
                  "test-api-key")]
      (is (= "https://api.openai.com/v1/chat/completions" (:url result)))
      (is (= "Bearer test-api-key" (get-in result [:headers "Authorization"])))
      (is (= "application/json" (get-in result [:headers "content-type"])))
      (is (= "gpt-4o" (get-in result [:body :model])))
      (is (= [{"role" "user" "content" "hello"}] (get-in result [:body :messages])))))

  (testing "Messages pass through unchanged"
    (let [messages [{"role" "system" "content" "You are helpful"}
                    {"role" "user" "content" "hello"}
                    {"role" "assistant" "content" "hi"}]
          result (openai/openrouter->openai "gpt-4o" messages "key")]
      (is (= messages (get-in result [:body :messages]))))))

(deftest openai->openrouter-test
  (testing "Extracts content from OpenAI response"
    (let [response {:choices [{:message {:content "Hello there!"}}]}]
      (is (= "Hello there!" (openai/openai->openrouter response)))))

  (testing "Handles response with metadata"
    (let [response {:choices [{:message {:content "Response"} :finish_reason "stop"}]
                    :usage {:prompt_tokens 10 :completion_tokens 20}}]
      (is (= "Response" (openai/openai->openrouter response))))))
