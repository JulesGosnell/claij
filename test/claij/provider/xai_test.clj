(ns claij.provider.xai-test
  "Tests for xAI (Grok) provider transforms."
  (:require
   [clojure.test :refer [deftest is testing]]
   [claij.provider.xai :as xai]))

(deftest translate-model-test
  (testing "Unknown models pass through unchanged"
    (is (= "grok-3-beta" (xai/translate-model "grok-3-beta")))
    (is (= "grok-2" (xai/translate-model "grok-2")))))

(deftest openrouter->xai-test
  (testing "Request transform produces correct structure"
    (let [result (xai/openrouter->xai
                  "grok-3-beta"
                  [{"role" "user" "content" "hello"}]
                  "test-api-key")]
      (is (= "https://api.x.ai/v1/chat/completions" (:url result)))
      (is (= "Bearer test-api-key" (get-in result [:headers "Authorization"])))
      (is (= "application/json" (get-in result [:headers "content-type"])))
      (is (= "grok-3-beta" (get-in result [:body :model])))
      (is (= [{"role" "user" "content" "hello"}] (get-in result [:body :messages])))))

  (testing "Messages pass through unchanged"
    (let [messages [{"role" "system" "content" "You are helpful"}
                    {"role" "user" "content" "hello"}
                    {"role" "assistant" "content" "hi"}]
          result (xai/openrouter->xai "grok-3-beta" messages "key")]
      (is (= messages (get-in result [:body :messages]))))))

(deftest xai->openrouter-test
  (testing "Extracts content from xAI response"
    (let [response {:choices [{:message {:content "Hello there!"}}]}]
      (is (= "Hello there!" (xai/xai->openrouter response)))))

  (testing "Handles response with metadata"
    (let [response {:choices [{:message {:content "Response"} :finish_reason "stop"}]
                    :usage {:prompt_tokens 10 :completion_tokens 20}}]
      (is (= "Response" (xai/xai->openrouter response))))))
