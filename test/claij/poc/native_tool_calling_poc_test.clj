(ns claij.poc.native-tool-calling-poc-test
  "PoC test: Native tool calling across LLM providers.
   
   Tests whether LLMs can use native tool calling with a stubbed tool.
   No real MCP - just proves the API mechanics work.
   
   Story #124: Native tool calling support for MCP hat"
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.tools.logging :as log]
   [clojure.string :as str]
   [cheshire.core :as json]
   [clj-http.client :as http]))

;;==============================================================================
;; Environment Loading
;;==============================================================================

(defn load-env-file
  "Load environment variables from .env file. Returns map of key->value."
  [path]
  (try
    (let [content (slurp path)
          lines (str/split-lines content)]
      (into {}
            (for [line lines
                  :when (and (not (str/blank? line))
                             (str/starts-with? line "export ")
                             (str/includes? line "="))]
              (let [line (subs line 7) ;; remove "export "
                    [k v] (str/split line #"=" 2)
                    v (-> v str/trim (str/replace #"^['\"]|['\"]$" ""))]
                [(str/trim k) v]))))
    (catch Exception _ {})))

(def ^:dynamic *env-vars*
  "Fallback env vars loaded from .env file"
  (delay (load-env-file ".env")))

(defn get-env
  "Get environment variable, falling back to .env file"
  [key]
  (or (System/getenv key)
      (get @*env-vars* key)))

;;==============================================================================
;; Stubbed Tool Definition
;;==============================================================================

(def secret-tool
  "A simple tool that 'returns' a secret. The LLM must call this tool
   to get the secret value. We simulate the response."
  {:name "get_secret"
   :description "Returns the secret passphrase. Call this to retrieve the secret."
   :input_schema {:type "object"
                  :properties {}
                  :required []}})

(def secret-value "the-eagle-has-landed-42")

;;==============================================================================
;; Anthropic API (Direct)
;;==============================================================================

(defn call-anthropic
  "Call Anthropic API with native tools parameter."
  [model messages tools]
  (let [api-key (get-env "ANTHROPIC_API_KEY")
        _ (assert api-key "ANTHROPIC_API_KEY not set")
        body (cond-> {:model model
                      :max_tokens 1024
                      :messages messages}
               (seq tools) (assoc :tools tools))
        response (http/post "https://api.anthropic.com/v1/messages"
                            {:headers {"x-api-key" api-key
                                       "anthropic-version" "2023-06-01"
                                       "content-type" "application/json"}
                             :body (json/generate-string body)
                             :as :json
                             :throw-exceptions false})]
    (if (= 200 (:status response))
      {:ok true :body (:body response)}
      {:ok false :status (:status response) :error (:body response)})))

(defn extract-tool-use-anthropic
  "Extract tool_use blocks from Anthropic response."
  [response]
  (->> (get-in response [:body :content])
       (filter #(= "tool_use" (:type %)))
       (mapv (fn [{:keys [id name input]}]
               {:id id :name name :arguments input}))))

(defn build-tool-result-anthropic
  "Build messages with tool result for Anthropic."
  [original-messages assistant-content tool-use-id result]
  (conj (vec original-messages)
        {:role "assistant" :content assistant-content}
        {:role "user"
         :content [{:type "tool_result"
                    :tool_use_id tool-use-id
                    :content result}]}))

;;==============================================================================
;; OpenRouter API (OpenAI-compatible)
;;==============================================================================

(defn call-openrouter
  "Call OpenRouter API with native tools parameter (OpenAI format)."
  [model messages tools]
  (let [api-key (get-env "OPENROUTER_API_KEY")
        _ (assert api-key "OPENROUTER_API_KEY not set")
        ;; OpenRouter uses OpenAI format - wrap tools in function type
        openai-tools (when (seq tools)
                       (mapv (fn [t] {:type "function" :function t}) tools))
        body (cond-> {:model model
                      :max_tokens 1024
                      :messages messages}
               openai-tools (assoc :tools openai-tools))
        response (http/post "https://openrouter.ai/api/v1/chat/completions"
                            {:headers {"Authorization" (str "Bearer " api-key)
                                       "Content-Type" "application/json"}
                             :body (json/generate-string body)
                             :as :json
                             :throw-exceptions false})]
    (if (= 200 (:status response))
      {:ok true :body (:body response)}
      {:ok false :status (:status response) :error (:body response)})))

(defn extract-tool-use-openrouter
  "Extract tool_calls from OpenRouter/OpenAI response."
  [response]
  (let [message (get-in response [:body :choices 0 :message])
        tool-calls (:tool_calls message)]
    (mapv (fn [{:keys [id function]}]
            {:id id
             :name (:name function)
             :arguments (if (string? (:arguments function))
                          (json/parse-string (:arguments function) true)
                          (:arguments function))})
          tool-calls)))

(defn build-tool-result-openrouter
  "Build messages with tool result for OpenRouter/OpenAI format.
   NOTE: arguments must be a JSON string, not an object.
   See: https://openrouter.ai/docs/guides/guides/mcp-servers"
  [original-messages assistant-message tool-call-id result]
  ;; Fix assistant message: ensure arguments exists and is JSON string
  (let [fixed-assistant
        (update-in assistant-message [:tool_calls]
                   (fn [calls]
                     (mapv (fn [call]
                             (update call :function
                                     (fn [f]
                                       (let [args (or (:arguments f) {})]
                                         (assoc f :arguments
                                                (if (string? args)
                                                  args
                                                  (json/generate-string args)))))))
                           calls)))
        ;; Get tool name for the result message
        tool-name (get-in assistant-message [:tool_calls 0 :function :name])]
    (conj (vec original-messages)
          fixed-assistant
          {:role "tool"
           :tool_call_id tool-call-id
           :name tool-name ;; OpenRouter docs include this
           :content result})))

;;==============================================================================
;; Ollama API (OpenAI-compatible)
;;==============================================================================

(defn call-ollama
  "Call Ollama API with native tools parameter.
   Defaults to prognathodon host (GPU server)."
  ([model messages tools]
   (call-ollama model messages tools "prognathodon"))
  ([model messages tools host]
   (let [openai-tools (when (seq tools)
                        (mapv (fn [t] {:type "function" :function t}) tools))
         body (cond-> {:model model
                       :messages messages
                       :stream false}
                openai-tools (assoc :tools openai-tools))
         url (str "http://" host ":11434/api/chat")
         response (http/post url
                             {:headers {"Content-Type" "application/json"}
                              :body (json/generate-string body)
                              :as :json
                              :throw-exceptions false
                              :socket-timeout 120000
                              :connection-timeout 10000})]
     (if (= 200 (:status response))
       {:ok true :body (:body response)}
       {:ok false :status (:status response) :error (:body response)}))))

(defn extract-tool-use-ollama
  "Extract tool_calls from Ollama response."
  [response]
  (let [message (get-in response [:body :message])
        tool-calls (:tool_calls message)]
    (mapv (fn [{:keys [function]}]
            {:id (str (random-uuid)) ;; Ollama doesn't provide IDs
             :name (:name function)
             :arguments (:arguments function)})
          tool-calls)))

;;==============================================================================
;; Generic Test Runner
;;==============================================================================

(defn run-tool-calling-test
  "Run a tool calling test with the given provider functions.
   Returns {:success bool :tool-called bool :final-response string :details map}"
  [{:keys [call-fn extract-fn build-result-fn provider model]}]
  (log/info "Testing" provider "with model" model)

  (let [messages [{:role "user"
                   :content "What is the secret? Use the get_secret tool to find out, then tell me."}]

        ;; First call - LLM should decide to use the tool
        response1 (call-fn model messages [secret-tool])]

    (if-not (:ok response1)
      {:success false
       :tool-called false
       :error (:error response1)
       :details {:provider provider :model model :stage "first-call"}}

      (let [tool-uses (extract-fn response1)]
        (log/info provider "tool uses:" (pr-str tool-uses))

        (if (empty? tool-uses)
          {:success false
           :tool-called false
           :details {:provider provider
                     :model model
                     :stage "no-tool-call"
                     :response (:body response1)}}

          (let [tool-use (first tool-uses)
                _ (is (= "get_secret" (:name tool-use))
                      (str provider " should call get_secret"))

                ;; Build messages with tool result
                messages2 (build-result-fn
                           messages
                           (or (get-in response1 [:body :content])
                               (get-in response1 [:body :choices 0 :message]))
                           (:id tool-use)
                           secret-value)

                ;; Second call - LLM processes result
                response2 (call-fn model messages2 [secret-tool])]

            (if-not (:ok response2)
              {:success false
               :tool-called true
               :error (:error response2)
               :details {:provider provider :model model :stage "second-call"}}

              (let [final-text (or
                                ;; Anthropic format
                                (->> (get-in response2 [:body :content])
                                     (filter #(= "text" (:type %)))
                                     first
                                     :text)
                                ;; OpenAI format
                                (get-in response2 [:body :choices 0 :message :content])
                                ;; Ollama format
                                (get-in response2 [:body :message :content]))]

                (log/info provider "final response:" final-text)

                {:success (boolean (and final-text
                                        (re-find (re-pattern secret-value) final-text)))
                 :tool-called true
                 :final-response final-text
                 :details {:provider provider :model model}}))))))))

;;==============================================================================
;; Tests
;;==============================================================================

(deftest ^:integration test-1-anthropic-direct
  (testing "Anthropic API direct → Claude Sonnet 4"
    (let [result (run-tool-calling-test
                  {:call-fn call-anthropic
                   :extract-fn extract-tool-use-anthropic
                   :build-result-fn build-tool-result-anthropic
                   :provider "anthropic-direct"
                   :model "claude-sonnet-4-20250514"})]
      (is (:tool-called result) "Claude should call the tool")
      (is (:success result) "Claude should return the secret in final response")
      (when-not (:success result)
        (log/error "Test 1 failed:" (pr-str result))))))

(deftest ^:integration test-2-openrouter-claude
  (testing "OpenRouter → Claude Sonnet 4"
    (let [result (run-tool-calling-test
                  {:call-fn call-openrouter
                   :extract-fn extract-tool-use-openrouter
                   :build-result-fn build-tool-result-openrouter
                   :provider "openrouter-claude"
                   :model "anthropic/claude-sonnet-4"})]
      (is (:tool-called result) "Claude via OpenRouter should call the tool")
      (is (:success result) "Claude via OpenRouter should return the secret")
      (when-not (:success result)
        (log/error "Test 2 failed:" (pr-str result))))))

(deftest ^:integration test-3-openrouter-free-model
  (testing "OpenRouter → free model with tool support"
    ;; Try several free models known to support function calling
    (let [models ["mistralai/mistral-7b-instruct:free"
                  "meta-llama/llama-3.1-8b-instruct:free"
                  "google/gemma-2-9b-it:free"]
          results (for [model models]
                    (let [result (run-tool-calling-test
                                  {:call-fn call-openrouter
                                   :extract-fn extract-tool-use-openrouter
                                   :build-result-fn build-tool-result-openrouter
                                   :provider "openrouter-free"
                                   :model model})]
                      (assoc result :model model)))
          successful (filter :success results)]
      (log/info "Free model results:" (pr-str (mapv #(select-keys % [:model :tool-called :success]) results)))
      (is (seq successful) "At least one free model should support tool calling"))))

(deftest ^:integration test-4-ollama-local
  (testing "Ollama → local model with native tool calling"
    ;; Test with models known to support tool calling
    (let [models ["qwen3:8b" "mistral:7b" "granite4:3b"]
          results (for [model models]
                    (try
                      (let [result (run-tool-calling-test
                                    {:call-fn call-ollama
                                     :extract-fn extract-tool-use-ollama
                                     :build-result-fn build-tool-result-openrouter ;; Same format
                                     :provider "ollama-local"
                                     :model model})]
                        (assoc result :model model))
                      (catch Exception e
                        {:success false
                         :tool-called false
                         :model model
                         :error (.getMessage e)})))
          successful (filter :tool-called results)]
      (log/info "Ollama results:" (pr-str (mapv #(select-keys % [:model :tool-called :success :error]) results)))
      (is (seq successful) "At least one Ollama model should support tool calling")
      ;; qwen3:8b should work perfectly
      (is (some #(and (= "qwen3:8b" (:model %)) (:success %)) results)
          "qwen3:8b should succeed with native tool calling"))))

;;==============================================================================
;; Manual Testing
;;==============================================================================

(comment
  ;; Test 1: Anthropic direct
  (run-tool-calling-test
   {:call-fn call-anthropic
    :extract-fn extract-tool-use-anthropic
    :build-result-fn build-tool-result-anthropic
    :provider "anthropic-direct"
    :model "claude-sonnet-4-20250514"})

  ;; Test 2: OpenRouter Claude
  (run-tool-calling-test
   {:call-fn call-openrouter
    :extract-fn extract-tool-use-openrouter
    :build-result-fn build-tool-result-openrouter
    :provider "openrouter-claude"
    :model "anthropic/claude-sonnet-4"})

  ;; Test 3: OpenRouter free models
  (run-tool-calling-test
   {:call-fn call-openrouter
    :extract-fn extract-tool-use-openrouter
    :build-result-fn build-tool-result-openrouter
    :provider "openrouter-free"
    :model "mistralai/mistral-7b-instruct:free"})

  ;; Test 4: Ollama local
  (run-tool-calling-test
   {:call-fn call-ollama
    :extract-fn extract-tool-use-ollama
    :build-result-fn build-tool-result-openrouter
    :provider "ollama-local"
    :model "qwen2.5:7b"})

  ;; Quick test - just the first call
  (call-anthropic "claude-sonnet-4-20250514"
                  [{:role "user" :content "What is the secret? Use get_secret."}]
                  [secret-tool]))
