(ns claij.integration.llm-test
  "Feature-based LLM integration testing.
   
   Architecture:
   - Direct HTTP calls to native APIs (clj-http, not claij.llm)
   - Production prompts required from src/ (fsm.clj, mcp/schema.clj)
   - 3 API schema pairs (Anthropic, OpenAI-compat, Google) for validation
   - Tests each LLM to verify behavior with production prompts
   
   The request/response schemas here establish ground truth for:
   1. Integration tests - validate real API responses
   2. Unit tests - validate recorded fixtures match real format
   
   To add a new LLM:
   1. Add to llm-capabilities with api-type and features
   2. Run: clojure -M:test --focus claij.integration.llm-test"
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.string :as str]
   [cheshire.core :as json]
   [clj-http.client :as http]
   ;; Production prompts - tests must use these
   [claij.mcp.schema :as mcp-schema]
   ;; Schema validation
   [claij.schema :as schema]
   ;; Official API schemas from OpenAPI specs
   [claij.llm.openai-schema :as openai-schema]
   [claij.llm.anthropic-schema :as anthropic-schema]
   [claij.llm.google-schema :as google-schema]))

;;------------------------------------------------------------------------------
;; Environment

(defn env-key-set?
  "Check if environment variable is set and non-empty"
  [key]
  (let [v (System/getenv key)]
    (and v (not (str/blank? v)))))

(defn get-env [key]
  (System/getenv key))

;;------------------------------------------------------------------------------
;; API Request/Response Schemas
;;
;; These define the ground truth for each provider's native API format.
;; Used to validate both integration test results and unit test fixtures.

(def anthropic-request-schema
  "Anthropic Messages API request format.
   Source: Official Anthropic OpenAPI spec via claij.llm.anthropic-schema"
  anthropic-schema/request-schema)

(def anthropic-response-schema
  "Anthropic Messages API response format.
   Source: Official Anthropic OpenAPI spec via claij.llm.anthropic-schema"
  anthropic-schema/response-schema)

(def openai-request-schema
  "OpenAI-compatible API request format (OpenRouter, xAI, Ollama).
   Source: Official OpenAI OpenAPI spec via claij.llm.openai-schema"
  openai-schema/request-schema)

(def openai-response-schema
  "OpenAI-compatible API response format.
   Source: Official OpenAI OpenAPI spec via claij.llm.openai-schema"
  openai-schema/response-schema)

(def google-request-schema
  "Google Gemini API request format.
   Source: Official Google OpenAPI spec via claij.llm.google-schema"
  google-schema/request-schema)

(def google-response-schema
  "Google Gemini API response format.
   Source: Official Google OpenAPI spec via claij.llm.google-schema"
  google-schema/response-schema)

;;------------------------------------------------------------------------------
;; Schema Validation Helper

(defn response-schema-for-api-type
  "Get the response schema for an API type"
  [api-type]
  (case api-type
    :anthropic anthropic-response-schema
    :openai openai-response-schema
    :google google-response-schema))

(defn request-schema-for-api-type
  "Get the request schema for an API type"
  [api-type]
  (case api-type
    :anthropic anthropic-request-schema
    :openai openai-request-schema
    :google google-request-schema))

(defn validate-response
  "Validate an HTTP response body against the API schema.
   Returns {:valid? true} or {:valid? false :errors [...]}."
  [api-type response-body]
  (let [response-schema (response-schema-for-api-type api-type)]
    (schema/validate response-schema response-body)))

(defn validate-request
  "Validate an HTTP request body against the API schema.
   Returns {:valid? true} or {:valid? false :errors [...]}."
  [api-type request-body]
  (let [request-schema (request-schema-for-api-type api-type)]
    (schema/validate request-schema request-body)))

;;------------------------------------------------------------------------------
;; Native Tool Call Schemas (for specific tool_call validation)

(def anthropic-tool-use-schema
  "Schema for Anthropic tool_use content block.
   Source: Official Anthropic OpenAPI spec via claij.llm.anthropic-schema"
  anthropic-schema/tool-use-block-schema)

(def openai-tool-call-schema
  "Schema for OpenAI tool_call object.
   Source: Official OpenAI OpenAPI spec via claij.llm.openai-schema"
  openai-schema/tool-call-item-schema)

(def google-function-call-schema
  "Schema for Google functionCall part.
   Source: Official Google OpenAPI spec via claij.llm.google-schema"
  google-schema/function-call-schema)

(defn tool-call-schema-for-api-type
  "Get the tool_call schema for an API type"
  [api-type]
  (case api-type
    :anthropic anthropic-tool-use-schema
    :openai openai-tool-call-schema
    :google google-function-call-schema))

;;------------------------------------------------------------------------------
;; LLM Capability Matrix

(def llm-capabilities
  "LLMs that CLAIJ supports with their API type and features.
   
   API types: :anthropic, :openai, :google
   Features: #{:base-tool-call :tuple-3 :content-xor-tools :native-tools}"
  {["anthropic" "claude-sonnet-4-5-20250514" "ANTHROPIC_API_KEY"]
   {:api-type :anthropic
    :url "https://api.anthropic.com/v1/messages"
    :features #{:base-tool-call :tuple-3 :content-xor-tools :native-tools}}

   ["google" "gemini-2.0-flash" "GOOGLE_API_KEY"]
   {:api-type :google
    :url-fn #(str "https://generativelanguage.googleapis.com/v1beta/models/"
                  % ":generateContent")
    :features #{:base-tool-call :tuple-3 :native-tools}}

   ["openrouter" "openai/gpt-4o" "OPENROUTER_API_KEY"]
   {:api-type :openai
    :url "https://openrouter.ai/api/v1/chat/completions"
    :features #{:base-tool-call :tuple-3 :content-xor-tools :native-tools}}

   ["xai" "grok-3-fast" "XAI_API_KEY"]
   {:api-type :openai
    :url "https://api.x.ai/v1/chat/completions"
    :features #{:base-tool-call :tuple-3 :content-xor-tools}}

   ["ollama:local" "qwen3:8b" nil]
   {:api-type :openai
    :url "http://prognathodon:11434/v1/chat/completions"
    :features #{:base-tool-call :tuple-3}}})

(defn llm-available?
  "Check if an LLM is available (API key set or local service)"
  [[_service _model env-key]]
  (or (nil? env-key)
      (env-key-set? env-key)))

;;------------------------------------------------------------------------------
;; Production Prompts (from src/)
;;
;; The tuple-3 system prompt is defined inline in fsm.clj make-prompts.
;; We extract the essential parts here for testing.

(def tuple-3-system-prompt
  "Production system prompt for tuple-3 format (extracted from fsm/make-prompts)"
  "JSON FORMAT

All communications use JSON:
- Objects: {\"key\": \"value\", \"key2\": 123}
- Arrays: [\"item1\", \"item2\", 42]
- Strings use double quotes: \"hello\"
- Numbers: 42, 3.14
- Booleans: true, false
- Null: null

YOUR REQUEST:
- Contains [INPUT-SCHEMA, INPUT-DOCUMENT, OUTPUT-SCHEMA] triples
- INPUT-SCHEMA: JSON Schema describing the INPUT-DOCUMENT structure
- INPUT-DOCUMENT: The actual data you receive
- OUTPUT-SCHEMA: JSON Schema your response MUST conform to

YOUR RESPONSE:
- Must be ONLY a valid JSON object - no prose, no markdown, no explanation
- Must include the \"id\" field with a valid transition array
- Must include all required fields with valid values

CRITICAL FORMAT RULES:
- Start with { and end with } - nothing else!
- Do NOT output arrays as your response, explanations, or the word 'I'
- Do NOT wrap in markdown code blocks
- WRONG: I will analyze... or [schema, doc, schema]
- RIGHT: {\"id\": [\"from\", \"to\"], \"field\": \"value\"}")

;; XOR tool prompt from production code
(def xor-tool-prompt mcp-schema/xor-tool-prompt)

;;------------------------------------------------------------------------------
;; Calculator Tool (test fixture)

(def calculator-tool-anthropic
  "Calculator tool in Anthropic format"
  {"name" "calculator"
   "description" "Performs arithmetic operations"
   "input_schema" {"type" "object"
                   "properties" {"op" {"type" "string" "enum" ["add" "multiply"]}
                                 "a" {"type" "number"}
                                 "b" {"type" "number"}}
                   "required" ["op" "a" "b"]}})

(def calculator-tool-openai
  "Calculator tool in OpenAI format"
  {"type" "function"
   "function" {"name" "calculator"
               "description" "Performs arithmetic operations"
               "parameters" {"type" "object"
                             "properties" {"op" {"type" "string" "enum" ["add" "multiply"]}
                                           "a" {"type" "number"}
                                           "b" {"type" "number"}}
                             "required" ["op" "a" "b"]}}})

(def calculator-tool-google
  "Calculator tool in Google format"
  {"function_declarations"
   [{"name" "calculator"
     "description" "Performs arithmetic operations"
     "parameters" {"type" "object"
                   "properties" {"op" {"type" "string" "enum" ["add" "multiply"]}
                                 "a" {"type" "number"}
                                 "b" {"type" "number"}}
                   "required" ["op" "a" "b"]}}]})

;;------------------------------------------------------------------------------
;; Direct API Callers

(defn call-anthropic!
  "Direct call to Anthropic Messages API"
  [model messages & [{:keys [tools system]}]]
  (let [api-key (get-env "ANTHROPIC_API_KEY")
        body (cond-> {"model" model
                      "max_tokens" 1024
                      "messages" messages}
               system (assoc "system" system)
               tools (assoc "tools" tools))]
    (http/post "https://api.anthropic.com/v1/messages"
               {:headers {"x-api-key" api-key
                          "anthropic-version" "2023-06-01"
                          "content-type" "application/json"}
                :body (json/generate-string body)
                :as :json-strict-string-keys
                :throw-exceptions false
                :socket-timeout 60000
                :connection-timeout 10000})))

(defn call-openai!
  "Direct call to OpenAI-compatible API (OpenRouter, xAI, Ollama).
   Validates request against schema before sending."
  [url model messages & [{:keys [tools api-key]}]]
  (let [body (cond-> {"model" model
                      "messages" messages
                      "stream" false
                      "max_tokens" 1024}
               tools (assoc "tools" tools))
        ;; Validate request before sending
        validation (validate-request :openai body)]
    (when-not (:valid? validation)
      (throw (ex-info "Request validation failed"
                      {:errors (:errors validation)
                       :body body})))
    (let [headers (cond-> {"Content-Type" "application/json"}
                    api-key (assoc "Authorization" (str "Bearer " api-key)))]
      (http/post url
                 {:headers headers
                  :body (json/generate-string body)
                  :as :json-strict-string-keys
                  :throw-exceptions false
                  :socket-timeout 60000
                  :connection-timeout 10000}))))

(defn call-google!
  "Direct call to Google Gemini API"
  [model messages & [{:keys [tools]}]]
  (let [api-key (get-env "GOOGLE_API_KEY")
        url (str "https://generativelanguage.googleapis.com/v1beta/models/"
                 model ":generateContent")
        contents (mapv (fn [{:strs [role content]}]
                         {"role" (if (= role "assistant") "model" role)
                          "parts" [{"text" content}]})
                       messages)
        body (cond-> {"contents" contents}
               tools (assoc "tools" tools))]
    (http/post url
               {:headers {"x-goog-api-key" api-key
                          "Content-Type" "application/json"}
                :body (json/generate-string body)
                :as :json-strict-string-keys
                :throw-exceptions false
                :socket-timeout 60000
                :connection-timeout 10000})))

;;------------------------------------------------------------------------------
;; Response Extractors

(defn extract-anthropic-content
  "Extract content from Anthropic response"
  [response]
  (let [content (get-in response [:body "content"])]
    {:text (->> content
                (filter #(= "text" (get % "type")))
                first
                (get "text"))
     :tool-calls (->> content
                      (filter #(= "tool_use" (get % "type")))
                      (mapv (fn [tc]
                              {"id" (get tc "id")
                               "name" (get tc "name")
                               "arguments" (get tc "input")})))}))

(defn extract-openai-content
  "Extract content from OpenAI-compatible response"
  [response]
  (let [message (get-in response [:body "choices" 0 "message"])]
    {:text (get message "content")
     :tool-calls (when-let [tcs (get message "tool_calls")]
                   (mapv (fn [tc]
                           {"id" (get tc "id")
                            "name" (get-in tc ["function" "name"])
                            "arguments" (let [args (get-in tc ["function" "arguments"])]
                                          (if (string? args)
                                            (json/parse-string args)
                                            args))})
                         tcs))}))

(defn extract-google-content
  "Extract content from Google response"
  [response]
  (let [parts (get-in response [:body "candidates" 0 "content" "parts"])]
    {:text (->> parts
                (filter #(contains? % "text"))
                first
                (get "text"))
     :tool-calls (->> parts
                      (filter #(contains? % "functionCall"))
                      (mapv (fn [p]
                              (let [fc (get p "functionCall")]
                                {"id" (get fc "name")
                                 "name" (get fc "name")
                                 "arguments" (get fc "args")}))))}))

;;------------------------------------------------------------------------------
;; Feature Tests
;;
;; Each feature returns {:pass? bool :error str} or {:pass? bool}

(defn test-base-tool-call
  "Test basic tool calling: user asks math question, LLM calls calculator"
  [api-type call-fn extract-fn tool-def]
  (let [messages [{"role" "user"
                   "content" "What is 2 + 2? Use the calculator tool."}]
        response (call-fn messages {:tools [tool-def]})
        status (:status response)]
    (if (= 200 status)
      (let [body (:body response)
            ;; Validate response against API schema
            validation (validate-response api-type body)]
        (if-not (:valid? validation)
          {:pass? false :error (str "Schema validation failed: " (pr-str (:errors validation)))}
          ;; Schema valid, now check content
          (let [{:keys [tool-calls]} (extract-fn response)]
            (if (seq tool-calls)
              (let [tc (first tool-calls)
                    name (get tc "name")
                    args (get tc "arguments")]
                (if (and (= "calculator" name)
                         (= "add" (get args "op")))
                  {:pass? true :tool-call tc}
                  {:pass? false :error (str "Wrong tool call: " (pr-str tc))}))
              {:pass? false :error "No tool calls in response"}))))
      {:pass? false :error (str "HTTP " status ": " (pr-str (:body response)))})))

(defn test-tuple-3-format
  "Test tuple-3 schema-in-prompt format"
  [api-type call-fn extract-fn]
  (let [input-schema {"type" "object"
                      "properties" {"numbers" {"type" "array"
                                               "items" {"type" "integer"}}}}
        input-doc {"numbers" [1 2 3]}
        output-schema {"type" "object"
                       "required" ["id" "sum"]
                       "properties" {"id" {"type" "array"}
                                     "sum" {"type" "integer"}}}
        tuple-3 [input-schema input-doc output-schema]
        messages [{"role" "system" "content" tuple-3-system-prompt}
                  {"role" "user" "content" (json/generate-string tuple-3)}]
        response (call-fn messages {})]
    (if (= 200 (:status response))
      (let [body (:body response)
            ;; Validate response against API schema
            validation (validate-response api-type body)]
        (if-not (:valid? validation)
          {:pass? false :error (str "Schema validation failed: " (pr-str (:errors validation)))}
          ;; Schema valid, now check content
          (let [{:keys [text]} (extract-fn response)]
            (if text
              (try
                (let [parsed (json/parse-string text)]
                  (if (and (get parsed "id")
                           (get parsed "sum"))
                    {:pass? true :response parsed}
                    {:pass? false :error (str "Missing required fields: " (pr-str parsed))}))
                (catch Exception e
                  {:pass? false :error (str "JSON parse error: " (.getMessage e) " - " text)}))
              {:pass? false :error "No text content in response"}))))
      {:pass? false :error (str "HTTP " (:status response) ": " (pr-str (:body response)))})))

(defn test-content-xor-tools
  "Test that response has content XOR tool_calls, never both"
  [api-type call-fn extract-fn tool-def]
  (let [messages [{"role" "system" "content" xor-tool-prompt}
                  {"role" "user"
                   "content" "What is 2 + 2? Use the calculator tool."}]
        response (call-fn messages {:tools [tool-def]})]
    (if (= 200 (:status response))
      (let [body (:body response)
            ;; Validate response against API schema
            validation (validate-response api-type body)]
        (if-not (:valid? validation)
          {:pass? false :error (str "Schema validation failed: " (pr-str (:errors validation)))}
          ;; Schema valid, now check XOR constraint
          (let [{:keys [text tool-calls]} (extract-fn response)
                has-text? (and text (not (str/blank? text)))
                has-tools? (seq tool-calls)]
            (cond
              (and has-text? has-tools?)
              {:pass? false :error (str "XOR violation: both content and tool_calls present"
                                        "\ntext: " (pr-str text)
                                        "\ntools: " (pr-str tool-calls))}
              (or has-text? has-tools?)
              {:pass? true :has-text? has-text? :has-tools? has-tools?}
              :else
              {:pass? false :error "Neither content nor tool_calls present"}))))
      {:pass? false :error (str "HTTP " (:status response) ": " (pr-str (:body response)))})))

(defn extract-raw-tool-calls
  "Extract raw tool_calls from response body (not normalized)"
  [api-type response-body]
  (case api-type
    :anthropic
    (->> (get response-body "content")
         (filter #(= "tool_use" (get % "type"))))

    :openai
    (get-in response-body ["choices" 0 "message" "tool_calls"])

    :google
    (->> (get-in response-body ["candidates" 0 "content" "parts"])
         (filter #(contains? % "functionCall")))))

(defn test-native-tools
  "Test that native tool call responses match API-specific schema"
  [api-type call-fn tool-def]
  (let [messages [{"role" "user"
                   "content" "What is 2 + 2? Use the calculator tool."}]
        response (call-fn messages {:tools [tool-def]})]
    (if (= 200 (:status response))
      (let [body (:body response)
            raw-tool-calls (extract-raw-tool-calls api-type body)]
        (if (seq raw-tool-calls)
          (let [tool-call-schema (tool-call-schema-for-api-type api-type)
                first-tc (first raw-tool-calls)
                validation (schema/validate tool-call-schema first-tc)]
            (if (:valid? validation)
              {:pass? true :tool-call first-tc}
              {:pass? false :error (str "Tool call schema validation failed: "
                                        (pr-str (:errors validation))
                                        "\nTool call: " (pr-str first-tc))}))
          {:pass? false :error "No tool calls in response"}))
      {:pass? false :error (str "HTTP " (:status response) ": " (pr-str (:body response)))})))

;;------------------------------------------------------------------------------
;; Test Runner

(defn run-llm-tests
  "Run all applicable feature tests for an LLM"
  [[service model env-key :as llm-key] {:keys [api-type url url-fn features]}]
  (testing (str "LLM: " service "/" model)
    (let [;; Build call function based on API type
          call-fn (case api-type
                    :anthropic
                    (fn [msgs opts]
                      (call-anthropic! model msgs (assoc opts :system tuple-3-system-prompt)))

                    :openai
                    (let [actual-url (or url (url-fn model))
                          api-key (when env-key (get-env env-key))]
                      (fn [msgs opts]
                        (call-openai! actual-url model msgs (assoc opts :api-key api-key))))

                    :google
                    (fn [msgs opts]
                      (call-google! model msgs opts)))

          ;; Extract function
          extract-fn (case api-type
                       :anthropic extract-anthropic-content
                       :openai extract-openai-content
                       :google extract-google-content)

          ;; Tool definition
          tool-def (case api-type
                     :anthropic calculator-tool-anthropic
                     :openai calculator-tool-openai
                     :google calculator-tool-google)]

      ;; Run applicable feature tests
      (when (features :base-tool-call)
        (testing "base-tool-call"
          (let [result (test-base-tool-call api-type call-fn extract-fn tool-def)]
            (is (:pass? result) (or (:error result) "")))))

      (when (features :tuple-3)
        (testing "tuple-3-format"
          (let [result (test-tuple-3-format api-type call-fn extract-fn)]
            (is (:pass? result) (or (:error result) "")))))

      (when (features :content-xor-tools)
        (testing "content-xor-tools"
          (let [result (test-content-xor-tools api-type call-fn extract-fn tool-def)]
            (is (:pass? result) (or (:error result) "")))))

      (when (features :native-tools)
        (testing "native-tools"
          (let [result (test-native-tools api-type call-fn tool-def)]
            (is (:pass? result) (or (:error result) ""))))))))

;;------------------------------------------------------------------------------
;; Main Test

(deftest ^:integration llm-capability-test
  (doseq [[llm-key config] llm-capabilities]
    (if (llm-available? llm-key)
      (run-llm-tests llm-key config)
      (let [[service model env-key] llm-key]
        (testing (str "LLM: " service "/" model)
          (is false (str service " unavailable - " env-key " not set")))))))
