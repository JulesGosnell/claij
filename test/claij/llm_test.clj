(ns claij.llm-test
  "Tests for unified LLM client.
   
   Uses dynamic binding *http-post* to stub HTTP calls and verify
   that each provider formats requests correctly."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [claij.llm :as llm :refer [call-llm *http-post*]]
   [claij.util :refer [json->clj clj->json]]))

;;------------------------------------------------------------------------------
;; Test fixtures and helpers
;;------------------------------------------------------------------------------

(def captured-requests
  "Atom to capture HTTP requests during tests."
  (atom []))

(defn reset-captures! []
  (reset! captured-requests []))

(defn make-mock-response
  "Create a mock HTTP response body for the given provider type."
  [provider-type content]
  (let [json-body (case provider-type
                    :openai (clj->json {:choices [{:message {:content content}}]})
                    :anthropic (clj->json {:content [{:text content}]})
                    :google (clj->json {:candidates [{:content {:parts [{:text content}]}}]}))]
    {:body json-body}))

(defn make-mock-http
  "Create a mock HTTP post function that captures requests and returns mock responses.
   
   response-type: :openai, :anthropic, or :google (determines response format)
   response-content: The content string to return"
  [response-type response-content]
  (fn mock-post
    ([url opts]
     ;; Sync mode
     (swap! captured-requests conj {:url url :opts opts :mode :sync})
     (make-mock-response response-type response-content))
    ([url opts on-success on-error]
     ;; Async mode
     (swap! captured-requests conj {:url url :opts opts :mode :async})
     (try
       (on-success (make-mock-response response-type response-content))
       (catch Exception e
         (on-error e)))
     nil)))

(defn last-request []
  (last @captured-requests))

(defn request-body []
  (-> (last-request) :opts :body json->clj))

(defn request-headers []
  (-> (last-request) :opts :headers))

(defn request-url []
  (-> (last-request) :url))

;;------------------------------------------------------------------------------
;; Anthropic tests
;;------------------------------------------------------------------------------

(deftest anthropic-request-format-test
  (testing "Anthropic provider formats requests correctly"
    (reset-captures!)

    (binding [*http-post* (make-mock-http :anthropic "Hello from Claude")]
      ;; Need to set env var for test
      (with-redefs [llm/require-key (constantly "test-api-key")]
        (let [messages [{"role" "system" "content" "You are helpful"}
                        {"role" "user" "content" "Hello"}]
              result (call-llm "anthropic" "claude-sonnet-4" messages {})]

          (testing "calls correct URL"
            (is (= "https://api.anthropic.com/v1/messages" (request-url))))

          (testing "includes required headers"
            (is (= "test-api-key" (get (request-headers) "x-api-key")))
            (is (= "2023-06-01" (get (request-headers) "anthropic-version")))
            (is (= "application/json" (get (request-headers) "content-type"))))

          (testing "extracts system message to separate field"
            (is (= "You are helpful" (:system (request-body)))))

          (testing "includes model"
            (is (= "claude-sonnet-4" (:model (request-body)))))

          (testing "messages don't include system"
            (let [msgs (:messages (request-body))]
              (is (= 1 (count msgs)))
              ;; After JSON roundtrip, keys are keywords
              (is (= "user" (:role (first msgs))))))

          (testing "returns extracted content"
            (is (= "Hello from Claude" result))))))))

;;------------------------------------------------------------------------------
;; OpenAI tests
;;------------------------------------------------------------------------------

(deftest openai-request-format-test
  (testing "OpenAI provider formats requests correctly"
    (reset-captures!)

    (binding [*http-post* (make-mock-http :openai "Hello from GPT")]
      (with-redefs [llm/require-key (constantly "test-api-key")]
        (let [messages [{"role" "system" "content" "You are helpful"}
                        {"role" "user" "content" "Hello"}]
              result (call-llm "openai" "gpt-4o" messages {})]

          (testing "calls correct URL"
            (is (= "https://api.openai.com/v1/chat/completions" (request-url))))

          (testing "includes auth header"
            (is (= "Bearer test-api-key" (get (request-headers) "Authorization"))))

          (testing "includes all messages (including system)"
            (let [msgs (:messages (request-body))]
              (is (= 2 (count msgs)))
              ;; After JSON roundtrip, keys are keywords
              (is (= "system" (:role (first msgs))))))

          (testing "includes model"
            (is (= "gpt-4o" (:model (request-body)))))

          (testing "returns extracted content"
            (is (= "Hello from GPT" result))))))))

;;------------------------------------------------------------------------------
;; Google tests
;;------------------------------------------------------------------------------

(deftest google-request-format-test
  (testing "Google provider formats requests correctly"
    (reset-captures!)

    (binding [*http-post* (make-mock-http :google "Hello from Gemini")]
      (with-redefs [llm/require-key (constantly "test-api-key")]
        (let [messages [{"role" "system" "content" "You are helpful"}
                        {"role" "user" "content" "Hello"}]
              result (call-llm "google" "gemini-2.5-flash" messages {})]

          (testing "calls correct URL with model"
            (is (= "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent"
                   (request-url))))

          (testing "includes API key header"
            (is (= "test-api-key" (get (request-headers) "x-goog-api-key"))))

          (testing "converts to Gemini content format"
            (let [contents (:contents (request-body))]
              (is (vector? contents))
              ;; System message should be merged into first user message
              (is (= 1 (count contents)))
              (let [first-content (first contents)]
                (is (= "user" (:role first-content)))
                (is (vector? (:parts first-content)))
                ;; Content should include system prompt
                (is (re-find #"You are helpful"
                             (get-in first-content [:parts 0 :text]))))))

          (testing "returns extracted content"
            (is (= "Hello from Gemini" result))))))))

;;------------------------------------------------------------------------------
;; xAI tests
;;------------------------------------------------------------------------------

(deftest xai-request-format-test
  (testing "xAI provider formats requests correctly"
    (reset-captures!)

    (binding [*http-post* (make-mock-http :openai "Hello from Grok")]
      (with-redefs [llm/require-key (constantly "test-api-key")]
        (let [messages [{"role" "user" "content" "Hello"}]
              result (call-llm "x-ai" "grok-4" messages {})]

          (testing "calls correct URL"
            (is (= "https://api.x.ai/v1/chat/completions" (request-url))))

          (testing "includes auth header"
            (is (= "Bearer test-api-key" (get (request-headers) "Authorization"))))

          (testing "uses OpenAI-compatible format"
            (is (= "grok-4" (:model (request-body))))
            ;; After JSON roundtrip, keys are keywords
            (let [msgs (:messages (request-body))]
              (is (= 1 (count msgs)))
              (is (= "user" (:role (first msgs))))
              (is (= "Hello" (:content (first msgs))))))

          (testing "returns extracted content"
            (is (= "Hello from Grok" result))))))))

;;------------------------------------------------------------------------------
;; OpenRouter fallback tests
;;------------------------------------------------------------------------------

(deftest openrouter-fallback-test
  (testing "Unknown provider falls back to OpenRouter"
    (reset-captures!)

    (binding [*http-post* (make-mock-http :openai "Hello from mystery model")]
      (with-redefs [llm/require-key (constantly "test-api-key")]
        (let [messages [{"role" "user" "content" "Hello"}]
              result (call-llm "mystery-provider" "mystery-model" messages {})]

          (testing "calls OpenRouter URL"
            (is (= "https://openrouter.ai/api/v1/chat/completions" (request-url))))

          (testing "formats model as provider/model"
            (is (= "mystery-provider/mystery-model" (:model (request-body)))))

          (testing "returns extracted content"
            (is (= "Hello from mystery model" result))))))))

;;------------------------------------------------------------------------------
;; Async mode tests
;;------------------------------------------------------------------------------

(deftest async-mode-test
  (testing "Async mode calls callbacks"
    (reset-captures!)
    (let [result-atom (atom nil)]

      (binding [*http-post* (make-mock-http :openai "Async response")]
        (with-redefs [llm/require-key (constantly "test-api-key")]
          (call-llm "openai" "gpt-4o"
                    [{"role" "user" "content" "Hello"}]
                    {:async? true
                     :on-success (fn [content] (reset! result-atom content))
                     :on-error (fn [err] (reset! result-atom {:error err}))})

          (testing "request marked as async"
            (is (= :async (:mode (last-request)))))

          (testing "on-success callback receives content"
            (is (= "Async response" @result-atom))))))))

;;------------------------------------------------------------------------------
;; Provider availability tests
;;------------------------------------------------------------------------------

(deftest provider-availability-test
  (testing "direct-provider? identifies known providers"
    (is (llm/direct-provider? "anthropic"))
    (is (llm/direct-provider? "openai"))
    (is (llm/direct-provider? "google"))
    (is (llm/direct-provider? "x-ai"))
    (is (not (llm/direct-provider? "unknown"))))

  (testing "available-providers returns providers with keys"
    ;; This test depends on actual env vars, so just check it returns a set
    (is (set? (llm/available-providers)))))

;;------------------------------------------------------------------------------
;; Edge cases
;;------------------------------------------------------------------------------

(deftest empty-messages-test
  (testing "Handles empty messages gracefully"
    (reset-captures!)

    (binding [*http-post* (make-mock-http :openai "Response")]
      (with-redefs [llm/require-key (constantly "test-api-key")]
        (call-llm "openai" "gpt-4o" [] {})
        (is (= [] (:messages (request-body))))))))

(deftest system-only-messages-test
  (testing "Anthropic handles system-only messages"
    (reset-captures!)

    (binding [*http-post* (make-mock-http :anthropic "Response")]
      (with-redefs [llm/require-key (constantly "test-api-key")]
        (call-llm "anthropic" "claude-sonnet-4"
                  [{"role" "system" "content" "Be helpful"}]
                  {})
        (let [body (request-body)]
          (is (= "Be helpful" (:system body)))
          (is (empty? (:messages body))))))))

(deftest multi-turn-conversation-test
  (testing "Multi-turn conversation preserved"
    (reset-captures!)

    (binding [*http-post* (make-mock-http :openai "Response")]
      (with-redefs [llm/require-key (constantly "test-api-key")]
        (let [messages [{"role" "system" "content" "Be helpful"}
                        {"role" "user" "content" "Hello"}
                        {"role" "assistant" "content" "Hi there!"}
                        {"role" "user" "content" "How are you?"}]]
          (call-llm "openai" "gpt-4o" messages {})
          (is (= 4 (count (:messages (request-body))))))))))
