(ns claij.provider.google-test
  "Tests for Google (Gemini) provider transforms."
  (:require
   [clojure.test :refer [deftest is testing]]
   [claij.provider.google :as google]))

(deftest translate-model-test
  (testing "Unknown models pass through unchanged"
    (is (= "gemini-2.0-flash" (google/translate-model "gemini-2.0-flash")))
    (is (= "gemini-pro" (google/translate-model "gemini-pro")))))

(deftest openrouter->google-test
  (testing "Request transform produces correct structure"
    (let [result (google/openrouter->google
                  "gemini-2.0-flash"
                  [{"role" "user" "content" "hello"}]
                  "test-api-key")]
      (is (= "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"
             (:url result)))
      (is (= "test-api-key" (get-in result [:headers "x-goog-api-key"])))
      (is (= "application/json" (get-in result [:headers "content-type"])))
      (is (= [{:role "user" :parts [{:text "hello"}]}]
             (get-in result [:body :contents])))))

  (testing "Model name is embedded in URL"
    (let [result (google/openrouter->google "gemini-pro" [] "key")]
      (is (clojure.string/includes? (:url result) "gemini-pro:generateContent"))))

  (testing "Role 'assistant' is translated to 'model'"
    (let [result (google/openrouter->google
                  "gemini-2.0-flash"
                  [{"role" "user" "content" "hello"}
                   {"role" "assistant" "content" "hi there"}
                   {"role" "user" "content" "how are you"}]
                  "key")]
      (is (= [{:role "user" :parts [{:text "hello"}]}
              {:role "model" :parts [{:text "hi there"}]}
              {:role "user" :parts [{:text "how are you"}]}]
             (get-in result [:body :contents])))))

  (testing "System messages become systemInstruction"
    (let [result (google/openrouter->google
                  "gemini-2.0-flash"
                  [{"role" "system" "content" "You are helpful"}
                   {"role" "user" "content" "hello"}]
                  "key")]
      (is (= {:parts [{:text "You are helpful"}]}
             (get-in result [:body :systemInstruction])))
      (is (= [{:role "user" :parts [{:text "hello"}]}]
             (get-in result [:body :contents])))))

  (testing "No systemInstruction when no system messages"
    (let [result (google/openrouter->google
                  "gemini-2.0-flash"
                  [{"role" "user" "content" "hello"}]
                  "key")]
      (is (nil? (get-in result [:body :systemInstruction]))))))

(deftest google->openrouter-test
  (testing "Extracts content from Google response"
    (let [response {:candidates [{:content {:parts [{:text "Hello there!"}]}}]}]
      (is (= "Hello there!" (google/google->openrouter response)))))

  (testing "Handles response with metadata"
    (let [response {:candidates [{:content {:parts [{:text "Response"}]}
                                  :finishReason "STOP"}]
                    :usageMetadata {:promptTokenCount 10}}]
      (is (= "Response" (google/google->openrouter response))))))
