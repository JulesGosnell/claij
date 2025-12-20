(ns claij.poc.llm-service-poc-test
  "POC for LLM service abstraction.
   
   Design:
   - Service registry maps service names to {:strategy, :url, :auth}
   - make-request dispatches on strategy, returns clj-http request map
   - parse-response dispatches on strategy, extracts content string
   
   Strategies:
   - openai-compat: Ollama, OpenRouter, xAI, OpenAI
   - anthropic: Anthropic direct API
   - google: Google/Gemini direct API"
  (:require
   [clojure.test :refer [deftest is testing]]
   [cheshire.core :as json]
   [clj-http.client]))

;;------------------------------------------------------------------------------
;; Auth Resolution
;;------------------------------------------------------------------------------

(defn get-env
  "Get environment variable. Wrapper for testability."
  [var-name]
  (System/getenv var-name))

(defn resolve-auth
  "Resolve auth configuration to actual credentials.
   
   Auth types:
   - nil                          -> no auth
   - {:type :bearer :env \"VAR\"} -> Bearer token from env var
   - {:type :x-api-key :env \"VAR\"} -> x-api-key header from env var
   - {:type :x-goog-api-key :env \"VAR\"} -> x-goog-api-key header from env var
   
   Returns map of headers to add, or empty map."
  [auth]
  (when auth
    (let [token (get-env (:env auth))]
      (case (:type auth)
        :bearer {"Authorization" (str "Bearer " token)}
        :x-api-key {"x-api-key" token}
        :x-goog-api-key {"x-goog-api-key" token}
        {}))))

;;------------------------------------------------------------------------------
;; Request Building - Dispatch on Strategy
;;------------------------------------------------------------------------------

(defmulti make-request
  "Build a clj-http request map for the given strategy.
   
   Args:
     strategy - \"openai-compat\", \"anthropic\", or \"google\"
     url      - API endpoint URL
     auth     - Auth config (or nil)
     model    - Model name (native to service)
     messages - Vector of message maps [{\"role\" \"user\" \"content\" \"...\"}]
   
   Returns:
     {:url     string
      :headers map
      :body    string (JSON encoded)}"
  (fn [strategy _url _auth _model _messages] strategy))

(defmethod make-request "openai-compat"
  [_strategy url auth model messages]
  {:url url
   :headers (merge {"Content-Type" "application/json"}
                   (resolve-auth auth))
   :body (json/generate-string {:model model
                                :messages messages})})

(defmethod make-request "anthropic"
  [_strategy url auth model messages]
  (let [;; Anthropic requires system message as separate field
        system-msgs (filter #(= "system" (get % "role")) messages)
        system-text (when (seq system-msgs)
                      (clojure.string/join "\n\n" (map #(get % "content") system-msgs)))
        non-system (vec (remove #(= "system" (get % "role")) messages))]
    {:url url
     :headers (merge {"Content-Type" "application/json"
                      "anthropic-version" "2023-06-01"}
                     (resolve-auth auth))
     :body (json/generate-string
            (cond-> {:model model
                     :max_tokens 4096
                     :messages non-system}
              system-text (assoc :system system-text)))}))

(defmethod make-request "google"
  [_strategy url auth model messages]
  (let [;; Google puts model in URL path
        full-url (str url "/" model ":generateContent")
        ;; Extract system messages
        system-msgs (filter #(= "system" (get % "role")) messages)
        system-text (when (seq system-msgs)
                      (clojure.string/join "\n\n" (map #(get % "content") system-msgs)))
        ;; Convert messages to Google format
        non-system (remove #(= "system" (get % "role")) messages)
        contents (mapv (fn [{:strs [role content]}]
                         {:role (if (= role "assistant") "model" role)
                          :parts [{:text content}]})
                       non-system)]
    {:url full-url
     :headers (merge {"Content-Type" "application/json"}
                     (resolve-auth auth))
     :body (json/generate-string
            (cond-> {:contents contents}
              system-text (assoc :systemInstruction {:parts [{:text system-text}]})))}))

;;------------------------------------------------------------------------------
;; Response Parsing - Dispatch on Strategy
;;------------------------------------------------------------------------------

(defmulti parse-response
  "Extract content string from provider response.
   
   Args:
     strategy - \"openai-compat\", \"anthropic\", or \"google\"
     response - Parsed JSON response body
   
   Returns:
     Content string"
  (fn [strategy _response] strategy))

(defmethod parse-response "openai-compat"
  [_strategy response]
  (get-in response [:choices 0 :message :content]))

(defmethod parse-response "anthropic"
  [_strategy response]
  (get-in response [:content 0 :text]))

(defmethod parse-response "google"
  [_strategy response]
  (get-in response [:candidates 0 :content :parts 0 :text]))

;;------------------------------------------------------------------------------
;; Service Registry Helpers
;;------------------------------------------------------------------------------

(defn lookup-service
  "Look up service configuration from registry.
   
   Args:
     registry - Map of service-name -> {:strategy :url :auth}
     service  - Service name string
   
   Returns:
     Service config map or throws if not found."
  [registry service]
  (or (get registry service)
      (throw (ex-info (str "Unknown service: " service)
                      {:service service
                       :available (keys registry)}))))

(defn build-request
  "Build request from service name and model.
   
   This is the main entry point for the abstraction.
   
   Args:
     registry - Service registry
     service  - Service name (e.g., \"ollama:local\", \"anthropic\")
     model    - Model name (native to service)
     messages - Vector of message maps
   
   Returns:
     clj-http request map"
  [registry service model messages]
  (let [{:keys [strategy url auth]} (lookup-service registry service)]
    (make-request strategy url auth model messages)))

;;------------------------------------------------------------------------------
;; Example Service Registry
;;------------------------------------------------------------------------------

(def example-registry
  "Example service registry for testing."
  {"ollama:local" {:strategy "openai-compat"
                   :url "http://prognathodon:11434/v1/chat/completions"
                   :auth nil}

   "ollama:gpu" {:strategy "openai-compat"
                 :url "http://bigbox:11434/v1/chat/completions"
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
;; Tests
;;------------------------------------------------------------------------------

(def simple-messages
  [{"role" "user" "content" "Hello, how are you?"}])

(def simple-messages-kw
  "Keywordized version for comparing after JSON roundtrip."
  [{:role "user" :content "Hello, how are you?"}])

(def messages-with-system
  [{"role" "system" "content" "You are a helpful assistant."}
   {"role" "user" "content" "Hello!"}])

(def messages-with-system-kw
  [{:role "system" :content "You are a helpful assistant."}
   {:role "user" :content "Hello!"}])

(def multi-turn-messages
  [{"role" "system" "content" "You are a Clojure expert."}
   {"role" "user" "content" "What is a lazy sequence?"}
   {"role" "assistant" "content" "A lazy sequence is..."}
   {"role" "user" "content" "Can you show an example?"}])

(deftest test-resolve-auth
  (testing "nil auth returns nil"
    (is (nil? (resolve-auth nil))))

  (testing "bearer auth with mocked env"
    (with-redefs [get-env (constantly "test-token")]
      (is (= {"Authorization" "Bearer test-token"}
             (resolve-auth {:type :bearer :env "TEST_KEY"})))))

  (testing "x-api-key auth with mocked env"
    (with-redefs [get-env (constantly "anthropic-key")]
      (is (= {"x-api-key" "anthropic-key"}
             (resolve-auth {:type :x-api-key :env "ANTHROPIC_API_KEY"})))))

  (testing "x-goog-api-key auth with mocked env"
    (with-redefs [get-env (constantly "google-key")]
      (is (= {"x-goog-api-key" "google-key"}
             (resolve-auth {:type :x-goog-api-key :env "GOOGLE_API_KEY"}))))))

(deftest test-openai-compat-strategy
  (testing "Ollama request (no auth)"
    (let [req (make-request "openai-compat"
                            "http://prognathodon:11434/v1/chat/completions"
                            nil
                            "mistral:7b"
                            simple-messages)
          body (json/parse-string (:body req) true)]
      (is (= "http://prognathodon:11434/v1/chat/completions" (:url req)))
      (is (= "application/json" (get-in req [:headers "Content-Type"])))
      (is (nil? (get-in req [:headers "Authorization"])))
      (is (= "mistral:7b" (:model body)))
      (is (= simple-messages-kw (:messages body)))))

  (testing "OpenRouter request (with bearer auth)"
    (with-redefs [get-env (constantly "or-key-123")]
      (let [req (make-request "openai-compat"
                              "https://openrouter.ai/api/v1/chat/completions"
                              {:type :bearer :env "OPENROUTER_API_KEY"}
                              "anthropic/claude-sonnet-4"
                              simple-messages)
            body (json/parse-string (:body req) true)]
        (is (= "https://openrouter.ai/api/v1/chat/completions" (:url req)))
        (is (= "Bearer or-key-123" (get-in req [:headers "Authorization"])))
        (is (= "anthropic/claude-sonnet-4" (:model body))))))

  (testing "xAI request"
    (with-redefs [get-env (constantly "xai-key")]
      (let [req (make-request "openai-compat"
                              "https://api.x.ai/v1/chat/completions"
                              {:type :bearer :env "XAI_API_KEY"}
                              "grok-3-beta"
                              simple-messages)
            body (json/parse-string (:body req) true)]
        (is (= "https://api.x.ai/v1/chat/completions" (:url req)))
        (is (= "Bearer xai-key" (get-in req [:headers "Authorization"])))
        (is (= "grok-3-beta" (:model body)))))))

(deftest test-anthropic-strategy
  (testing "Simple message"
    (with-redefs [get-env (constantly "anthro-key")]
      (let [req (make-request "anthropic"
                              "https://api.anthropic.com/v1/messages"
                              {:type :x-api-key :env "ANTHROPIC_API_KEY"}
                              "claude-sonnet-4-20250514"
                              simple-messages)
            body (json/parse-string (:body req) true)]
        (is (= "https://api.anthropic.com/v1/messages" (:url req)))
        (is (= "anthro-key" (get-in req [:headers "x-api-key"])))
        (is (= "2023-06-01" (get-in req [:headers "anthropic-version"])))
        (is (= "claude-sonnet-4-20250514" (:model body)))
        (is (= 4096 (:max_tokens body)))
        (is (nil? (:system body)))
        (is (= simple-messages-kw (:messages body))))))

  (testing "Message with system prompt extracted"
    (with-redefs [get-env (constantly "anthro-key")]
      (let [req (make-request "anthropic"
                              "https://api.anthropic.com/v1/messages"
                              {:type :x-api-key :env "ANTHROPIC_API_KEY"}
                              "claude-sonnet-4-20250514"
                              messages-with-system)
            body (json/parse-string (:body req) true)]
        (is (= "You are a helpful assistant." (:system body)))
        (is (= [{:role "user" :content "Hello!"}] (:messages body)))))))

(deftest test-google-strategy
  (testing "Simple message"
    (with-redefs [get-env (constantly "goog-key")]
      (let [req (make-request "google"
                              "https://generativelanguage.googleapis.com/v1beta/models"
                              {:type :x-goog-api-key :env "GOOGLE_API_KEY"}
                              "gemini-2.0-flash"
                              simple-messages)
            body (json/parse-string (:body req) true)]
        ;; Model in URL path
        (is (= "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent"
               (:url req)))
        (is (= "goog-key" (get-in req [:headers "x-goog-api-key"])))
        ;; Google format: contents with parts
        (is (= [{:role "user" :parts [{:text "Hello, how are you?"}]}]
               (:contents body)))
        (is (nil? (:systemInstruction body))))))

  (testing "Message with system instruction"
    (with-redefs [get-env (constantly "goog-key")]
      (let [req (make-request "google"
                              "https://generativelanguage.googleapis.com/v1beta/models"
                              {:type :x-goog-api-key :env "GOOGLE_API_KEY"}
                              "gemini-2.0-flash"
                              messages-with-system)
            body (json/parse-string (:body req) true)]
        (is (= {:parts [{:text "You are a helpful assistant."}]}
               (:systemInstruction body)))
        (is (= [{:role "user" :parts [{:text "Hello!"}]}]
               (:contents body))))))

  (testing "Multi-turn with role translation"
    (with-redefs [get-env (constantly "goog-key")]
      (let [req (make-request "google"
                              "https://generativelanguage.googleapis.com/v1beta/models"
                              {:type :x-goog-api-key :env "GOOGLE_API_KEY"}
                              "gemini-2.0-flash"
                              multi-turn-messages)
            body (json/parse-string (:body req) true)]
        ;; assistant -> model
        (is (= [{:role "user" :parts [{:text "What is a lazy sequence?"}]}
                {:role "model" :parts [{:text "A lazy sequence is..."}]}
                {:role "user" :parts [{:text "Can you show an example?"}]}]
               (:contents body)))))))

(deftest test-parse-response
  (testing "OpenAI-compat response"
    (let [response {:choices [{:message {:content "Hello there!"}}]}]
      (is (= "Hello there!" (parse-response "openai-compat" response)))))

  (testing "Anthropic response"
    (let [response {:content [{:type "text" :text "Greetings!"}]}]
      (is (= "Greetings!" (parse-response "anthropic" response)))))

  (testing "Google response"
    (let [response {:candidates [{:content {:parts [{:text "Hi!"}]}}]}]
      (is (= "Hi!" (parse-response "google" response))))))

(deftest test-build-request
  (testing "Full flow with registry lookup"
    (let [req (build-request example-registry
                             "ollama:local"
                             "mistral:7b"
                             simple-messages)
          body (json/parse-string (:body req) true)]
      (is (= "http://prognathodon:11434/v1/chat/completions" (:url req)))
      (is (= "mistral:7b" (:model body)))))

  (testing "Unknown service throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown service"
                          (build-request example-registry "nonexistent" "model" simple-messages)))))

(deftest test-registry-services
  (testing "All example registry services produce valid requests"
    (doseq [[service-name config] example-registry]
      (with-redefs [get-env (constantly "test-key")]
        (let [req (build-request example-registry
                                 service-name
                                 "test-model"
                                 simple-messages)]
          (is (string? (:url req)) (str service-name " should have URL"))
          (is (map? (:headers req)) (str service-name " should have headers"))
          (is (string? (:body req)) (str service-name " should have JSON body")))))))

;;------------------------------------------------------------------------------
;; Live Integration Test (requires Ollama running)
;;------------------------------------------------------------------------------

(defn call-llm
  "Make an actual HTTP call to an LLM service.
   
   This is what production code would use - takes the pure request
   from make-request and executes it with clj-http.
   
   Args:
     registry - Service registry
     service  - Service name
     model    - Model name (native to service)
     messages - Vector of message maps
   
   Returns:
     Content string from response"
  [registry service model messages]
  (let [{:keys [strategy]} (lookup-service registry service)
        req (build-request registry service model messages)
        response (clj-http.client/post (:url req)
                                       {:headers (:headers req)
                                        :body (:body req)
                                        :as :json})]
    (parse-response strategy (:body response))))

(deftest ^:integration test-ollama-live
  (testing "Live call to Ollama on prognathodon"
    (let [response (call-llm example-registry
                             "ollama:local"
                             "mistral:7b"
                             [{"role" "user"
                               "content" "Reply with exactly one word: hello"}])]
      (is (string? response))
      (is (pos? (count response)))
      (println "Ollama response:" response))))
