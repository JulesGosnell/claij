(ns claij.llm.service-test
  "Tests for LLM service abstraction."
  (:require
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [claij.llm.service :as svc]
   [claij.model :as model]))

;;------------------------------------------------------------------------------
;; Test Data
;;------------------------------------------------------------------------------

(def simple-messages
  [{"role" "user" "content" "Hello, how are you?"}])

(def simple-messages-kw
  [{:role "user" :content "Hello, how are you?"}])

(def messages-with-system
  [{"role" "system" "content" "You are a helpful assistant."}
   {"role" "user" "content" "Hello!"}])

(def multi-turn-messages
  [{"role" "system" "content" "You are a Clojure expert."}
   {"role" "user" "content" "What is a lazy sequence?"}
   {"role" "assistant" "content" "A lazy sequence is..."}
   {"role" "user" "content" "Can you show an example?"}])

(def test-registry
  {"ollama:local" {:strategy "openai-compat"
                   :url "http://prognathodon:11434/v1/chat/completions"
                   :auth nil}
   "openrouter" {:strategy "openai-compat"
                 :url "https://openrouter.ai/api/v1/chat/completions"
                 :auth {:type :bearer :env "OPENROUTER_API_KEY"}}
   "anthropic" {:strategy "anthropic"
                :url "https://api.anthropic.com/v1/messages"
                :auth {:type :x-api-key :env "ANTHROPIC_API_KEY"}}
   "google" {:strategy "google"
             :url "https://generativelanguage.googleapis.com/v1beta/models"
             :auth {:type :x-goog-api-key :env "GOOGLE_API_KEY"}}
   "xai" {:strategy "openai-compat"
          :url "https://api.x.ai/v1/chat/completions"
          :auth {:type :bearer :env "XAI_API_KEY"}}})

;;------------------------------------------------------------------------------
;; Auth Resolution Tests
;;------------------------------------------------------------------------------

(deftest test-resolve-auth
  (testing "nil auth returns nil"
    (is (nil? (svc/resolve-auth nil))))

  (testing "bearer auth"
    (with-redefs [svc/get-env (constantly "test-token")]
      (is (= {"Authorization" "Bearer test-token"}
             (svc/resolve-auth {:type :bearer :env "TEST_KEY"})))))

  (testing "x-api-key auth"
    (with-redefs [svc/get-env (constantly "anthro-key")]
      (is (= {"x-api-key" "anthro-key"}
             (svc/resolve-auth {:type :x-api-key :env "ANTHROPIC_API_KEY"})))))

  (testing "x-goog-api-key auth"
    (with-redefs [svc/get-env (constantly "goog-key")]
      (is (= {"x-goog-api-key" "goog-key"}
             (svc/resolve-auth {:type :x-goog-api-key :env "GOOGLE_API_KEY"}))))))

;;------------------------------------------------------------------------------
;; OpenAI-Compatible Strategy Tests
;;------------------------------------------------------------------------------

(deftest test-openai-compat-strategy
  (testing "Ollama request (no auth)"
    (let [req (svc/make-request "openai-compat"
                                "http://prognathodon:11434/v1/chat/completions"
                                nil
                                "mistral:7b"
                                simple-messages nil)
          body (json/parse-string (:body req) true)]
      (is (= "http://prognathodon:11434/v1/chat/completions" (:url req)))
      (is (= "application/json" (get-in req [:headers "Content-Type"])))
      (is (nil? (get-in req [:headers "Authorization"])))
      (is (= "mistral:7b" (:model body)))
      (is (= simple-messages-kw (:messages body)))))

  (testing "OpenRouter request (with bearer auth)"
    (with-redefs [svc/get-env (constantly "or-key-123")]
      (let [req (svc/make-request "openai-compat"
                                  "https://openrouter.ai/api/v1/chat/completions"
                                  {:type :bearer :env "OPENROUTER_API_KEY"}
                                  (model/openrouter-model :anthropic)
                                  simple-messages nil)
            body (json/parse-string (:body req) true)]
        (is (= "https://openrouter.ai/api/v1/chat/completions" (:url req)))
        (is (= "Bearer or-key-123" (get-in req [:headers "Authorization"])))
        (is (= (model/openrouter-model :anthropic) (:model body))))))

  (testing "xAI request"
    (with-redefs [svc/get-env (constantly "xai-key")]
      (let [req (svc/make-request "openai-compat"
                                  "https://api.x.ai/v1/chat/completions"
                                  {:type :bearer :env "XAI_API_KEY"}
                                  (model/direct-model :xai)
                                  simple-messages nil)
            body (json/parse-string (:body req) true)]
        (is (= "https://api.x.ai/v1/chat/completions" (:url req)))
        (is (= "Bearer xai-key" (get-in req [:headers "Authorization"])))
        (is (= (model/direct-model :xai) (:model body)))))))

;;------------------------------------------------------------------------------
;; Anthropic Strategy Tests
;;------------------------------------------------------------------------------

(deftest test-anthropic-strategy
  (testing "Simple message"
    (with-redefs [svc/get-env (constantly "anthro-key")]
      (let [req (svc/make-request "anthropic"
                                  "https://api.anthropic.com/v1/messages"
                                  {:type :x-api-key :env "ANTHROPIC_API_KEY"}
                                  (model/direct-model :anthropic)
                                  simple-messages nil)
            body (json/parse-string (:body req) true)]
        (is (= "https://api.anthropic.com/v1/messages" (:url req)))
        (is (= "anthro-key" (get-in req [:headers "x-api-key"])))
        (is (= "2023-06-01" (get-in req [:headers "anthropic-version"])))
        (is (= (model/direct-model :anthropic) (:model body)))
        (is (= 4096 (:max_tokens body)))
        (is (nil? (:system body)))
        (is (= simple-messages-kw (:messages body))))))

  (testing "Message with system prompt extracted"
    (with-redefs [svc/get-env (constantly "anthro-key")]
      (let [req (svc/make-request "anthropic"
                                  "https://api.anthropic.com/v1/messages"
                                  {:type :x-api-key :env "ANTHROPIC_API_KEY"}
                                  (model/direct-model :anthropic)
                                  messages-with-system nil)
            body (json/parse-string (:body req) true)]
        (is (= "You are a helpful assistant." (:system body)))
        (is (= [{:role "user" :content "Hello!"}] (:messages body)))))))

;;------------------------------------------------------------------------------
;; Google Strategy Tests
;;------------------------------------------------------------------------------

(deftest test-google-strategy
  (testing "Simple message"
    (with-redefs [svc/get-env (constantly "goog-key")]
      (let [req (svc/make-request "google"
                                  "https://generativelanguage.googleapis.com/v1beta/models"
                                  {:type :x-goog-api-key :env "GOOGLE_API_KEY"}
                                  (model/direct-model :google)
                                  simple-messages nil)
            body (json/parse-string (:body req) true)]
        (is (= (str "https://generativelanguage.googleapis.com/v1beta/models/"
                    (model/direct-model :google) ":generateContent")
               (:url req)))
        (is (= "goog-key" (get-in req [:headers "x-goog-api-key"])))
        (is (= [{:role "user" :parts [{:text "Hello, how are you?"}]}]
               (:contents body)))
        (is (nil? (:systemInstruction body))))))

  (testing "Message with system instruction"
    (with-redefs [svc/get-env (constantly "goog-key")]
      (let [req (svc/make-request "google"
                                  "https://generativelanguage.googleapis.com/v1beta/models"
                                  {:type :x-goog-api-key :env "GOOGLE_API_KEY"}
                                  (model/direct-model :google)
                                  messages-with-system nil)
            body (json/parse-string (:body req) true)]
        (is (= {:parts [{:text "You are a helpful assistant."}]}
               (:systemInstruction body)))
        (is (= [{:role "user" :parts [{:text "Hello!"}]}]
               (:contents body))))))

  (testing "Multi-turn with role translation"
    (with-redefs [svc/get-env (constantly "goog-key")]
      (let [req (svc/make-request "google"
                                  "https://generativelanguage.googleapis.com/v1beta/models"
                                  {:type :x-goog-api-key :env "GOOGLE_API_KEY"}
                                  (model/direct-model :google)
                                  multi-turn-messages nil)
            body (json/parse-string (:body req) true)]
        (is (= [{:role "user" :parts [{:text "What is a lazy sequence?"}]}
                {:role "model" :parts [{:text "A lazy sequence is..."}]}
                {:role "user" :parts [{:text "Can you show an example?"}]}]
               (:contents body)))))))

;;------------------------------------------------------------------------------
;; Response Parsing Tests
;;------------------------------------------------------------------------------

(deftest test-parse-response
  (testing "OpenAI-compat response - content only"
    (let [response {:choices [{:message {:content "Hello there!"}}]}
          result (svc/parse-response "openai-compat" response)]
      (is (= "Hello there!" (:content result)))
      (is (nil? (:tool_calls result)))))

  (testing "OpenAI-compat response - with tool_calls"
    (let [response {:choices [{:message {:content ""
                                         :tool_calls [{:id "call_123"
                                                       :function {:name "get_weather"
                                                                  :arguments "{\"city\":\"London\"}"}}]}}]}
          result (svc/parse-response "openai-compat" response)]
      (is (= "" (:content result)))
      (is (= [{:id "call_123" :name "get_weather" :arguments {:city "London"}}]
             (:tool_calls result)))))

  (testing "Anthropic response - content only"
    (let [response {:content [{:type "text" :text "Greetings!"}]}
          result (svc/parse-response "anthropic" response)]
      (is (= "Greetings!" (:content result)))
      (is (nil? (:tool_calls result)))))

  (testing "Anthropic response - with tool_use"
    (let [response {:content [{:type "tool_use"
                               :id "toolu_123"
                               :name "get_weather"
                               :input {:city "Paris"}}]}
          result (svc/parse-response "anthropic" response)]
      (is (nil? (:content result)))
      (is (= [{:id "toolu_123" :name "get_weather" :arguments {:city "Paris"}}]
             (:tool_calls result)))))

  (testing "Google response - content only"
    (let [response {:candidates [{:content {:parts [{:text "Hi!"}]}}]}
          result (svc/parse-response "google" response)]
      (is (= "Hi!" (:content result)))
      (is (nil? (:tool_calls result)))))

  (testing "Google response - with functionCall"
    (let [response {:candidates [{:content {:parts [{:functionCall {:name "get_weather"
                                                                    :args {:city "Berlin"}}}]}}]}
          result (svc/parse-response "google" response)]
      (is (nil? (:content result)))
      (is (= [{:id "get_weather" :name "get_weather" :arguments {:city "Berlin"}}]
             (:tool_calls result))))))

;;------------------------------------------------------------------------------
;; Registry Tests
;;------------------------------------------------------------------------------

(deftest test-lookup-service
  (testing "Known service returns config"
    (is (= {:strategy "openai-compat"
            :url "http://prognathodon:11434/v1/chat/completions"
            :auth nil}
           (svc/lookup-service test-registry "ollama:local"))))

  (testing "Unknown service throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown service"
                          (svc/lookup-service test-registry "nonexistent")))))

(deftest test-build-request-from-registry
  (testing "Builds correct request"
    (let [req (svc/build-request-from-registry test-registry
                                               "ollama:local"
                                               "mistral:7b"
                                               simple-messages)
          body (json/parse-string (:body req) true)]
      (is (= "http://prognathodon:11434/v1/chat/completions" (:url req)))
      (is (= "mistral:7b" (:model body))))))

(deftest test-all-registry-services
  (testing "All services produce valid requests"
    (doseq [[service-name _config] test-registry]
      (with-redefs [svc/get-env (constantly "test-key")]
        (let [req (svc/build-request-from-registry test-registry
                                                   service-name
                                                   "test-model"
                                                   simple-messages)]
          (is (string? (:url req)) (str service-name " should have URL"))
          (is (map? (:headers req)) (str service-name " should have headers"))
          (is (string? (:body req)) (str service-name " should have JSON body")))))))

;;------------------------------------------------------------------------------
;; Integration Test (requires Ollama running)
;;------------------------------------------------------------------------------

(deftest test-call-llm-sync
  (testing "Synchronous call with mocked HTTP"
    (with-redefs [svc/get-env (constantly "test-key")
                  http/post (fn [url opts]
                              (is (= "http://prognathodon:11434/v1/chat/completions" url))
                              {:body {:choices [{:message {:content "Mocked response"}}]}})]
      (let [response (svc/call-llm-sync test-registry
                                        "ollama:local"
                                        "mistral:7b"
                                        simple-messages)]
        (is (= "Mocked response" (:content response)))
        (is (nil? (:tool_calls response))))))

  (testing "Anthropic strategy parsing"
    (with-redefs [svc/get-env (constantly "test-key")
                  http/post (fn [_url _opts]
                              {:body {:content [{:type "text" :text "Anthropic response"}]}})]
      (let [response (svc/call-llm-sync test-registry
                                        "anthropic"
                                        (model/direct-model :anthropic)
                                        simple-messages)]
        (is (= "Anthropic response" (:content response))))))

  (testing "Google strategy parsing"
    (with-redefs [svc/get-env (constantly "test-key")
                  http/post (fn [_url _opts]
                              {:body {:candidates [{:content {:parts [{:text "Google response"}]}}]}})]
      (let [response (svc/call-llm-sync test-registry
                                        "google"
                                        (model/direct-model :google)
                                        simple-messages)]
        (is (= "Google response" (:content response))))))

  (testing "With tools option"
    (with-redefs [svc/get-env (constantly "test-key")
                  http/post (fn [_url opts]
                              (let [body (json/parse-string (:body opts) true)]
                                ;; Verify tools were included in request
                                (is (= [{:type "function"
                                         :function {:name "test_tool"
                                                    :description "A test tool"
                                                    :parameters {:type "object" :properties {}}}}]
                                       (:tools body))))
                              {:body {:choices [{:message {:content ""
                                                           :tool_calls [{:id "call_1"
                                                                         :function {:name "test_tool"
                                                                                    :arguments "{}"}}]}}]}})]
      (let [response (svc/call-llm-sync test-registry
                                        "ollama:local"
                                        "mistral:7b"
                                        simple-messages
                                        {:tools [{:type "function"
                                                  :function {:name "test_tool"
                                                             :description "A test tool"
                                                             :parameters {:type "object" :properties {}}}}]})]
        (is (= [{:id "call_1" :name "test_tool" :arguments {}}]
               (:tool_calls response)))))))

(deftest test-call-llm-async
  (testing "Async call with mocked HTTP - success"
    (let [result (promise)]
      (with-redefs [svc/get-env (constantly "test-key")
                    http/post (fn [_url opts success-fn _error-fn]
                                (success-fn {:body "{\"choices\":[{\"message\":{\"content\":\"Async response\"}}]}"}))]
        (svc/call-llm-async test-registry
                            "ollama:local"
                            "mistral:7b"
                            simple-messages
                            (fn [response] (deliver result response))))
      (is (= "Async response" (:content (deref result 1000 :timeout))))))

  (testing "Async call with mocked HTTP - error callback"
    (let [result (promise)
          test-exception (Exception. "Connection refused")]
      (with-redefs [svc/get-env (constantly "test-key")
                    http/post (fn [_url _opts _success-fn error-fn]
                                (error-fn test-exception))]
        (svc/call-llm-async test-registry
                            "ollama:local"
                            "mistral:7b"
                            simple-messages
                            (fn [_] (deliver result :should-not-happen))
                            {:error (fn [err] (deliver result err))}))
      (let [err (deref result 1000 :timeout)]
        (is (= "request-failed" (:error err)))
        (is (= test-exception (:exception err)))))))

(deftest ^:integration test-ollama-live
  (testing "Live call to Ollama"
    (let [response (svc/call-llm-sync
                    test-registry
                    "ollama:local"
                    "mistral:7b"
                    [{"role" "user"
                      "content" "Reply with exactly one word: hello"}])
          content (:content response)]
      (is (string? content))
      (is (pos? (count content)))
      (println "Ollama response:" content))))
