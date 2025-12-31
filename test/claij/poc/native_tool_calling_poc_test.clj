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
   [clj-http.client :as http]
   [claij.model :as model]))

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

(def answer-tool
  "A simple tool that 'returns' a value. The LLM must call this tool
   to get the value. We simulate the response."
  {:name "get_answer"
   :description "Returns the answer. Call this to retrieve it."
   :input_schema {:type "object"
                  :properties {}
                  :required []}})

(def answer-value "42")

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
        ;; Also transform :input_schema to :parameters (OpenAI format)
        openai-tools (when (seq tools)
                       (mapv (fn [t]
                               (let [params (or (:parameters t) (:input_schema t))]
                                 {:type "function"
                                  :function (-> t
                                                (dissoc :input_schema)
                                                (assoc :parameters params))}))
                             tools))
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

(defn call-openai-direct
  "Call OpenAI API directly with native tools."
  [model messages tools]
  (let [api-key (get-env "OPENAI_API_KEY")
        _ (assert api-key "OPENAI_API_KEY not set")
        openai-tools (when (seq tools)
                       (mapv (fn [t]
                               (let [params (or (:parameters t) (:input_schema t))]
                                 {:type "function"
                                  :function (-> t
                                                (dissoc :input_schema)
                                                (assoc :parameters params))}))
                             tools))
        body (cond-> {:model model
                      :max_completion_tokens 1024
                      :messages messages}
               openai-tools (assoc :tools openai-tools))
        response (http/post "https://api.openai.com/v1/chat/completions"
                            {:headers {"Authorization" (str "Bearer " api-key)
                                       "Content-Type" "application/json"}
                             :body (json/generate-string body)
                             :as :json
                             :throw-exceptions false})]
    (if (= 200 (:status response))
      {:ok true :body (:body response)}
      {:ok false :status (:status response) :error (:body response)})))

(defn call-xai-direct
  "Call xAI API directly with native tools."
  [model messages tools]
  (let [api-key (get-env "XAI_API_KEY")
        _ (assert api-key "XAI_API_KEY not set")
        openai-tools (when (seq tools)
                       (mapv (fn [t]
                               (let [params (or (:parameters t) (:input_schema t))]
                                 {:type "function"
                                  :function (-> t
                                                (dissoc :input_schema)
                                                (assoc :parameters params))}))
                             tools))
        body (cond-> {:model model
                      :max_tokens 1024
                      :messages messages}
               openai-tools (assoc :tools openai-tools))
        response (http/post "https://api.x.ai/v1/chat/completions"
                            {:headers {"Authorization" (str "Bearer " api-key)
                                       "Content-Type" "application/json"}
                             :body (json/generate-string body)
                             :as :json
                             :throw-exceptions false})]
    (if (= 200 (:status response))
      {:ok true :body (:body response)}
      {:ok false :status (:status response) :error (:body response)})))

(defn call-google-direct
  "Call Google Gemini API directly with native tools."
  [model messages tools]
  (let [api-key (get-env "GOOGLE_API_KEY")
        _ (assert api-key "GOOGLE_API_KEY not set")
        url (str "https://generativelanguage.googleapis.com/v1beta/models/" model ":generateContent")
        ;; Google uses function_declarations format
        google-tools (when (seq tools)
                       [{:function_declarations
                         (mapv (fn [t]
                                 {:name (:name t)
                                  :description (:description t)
                                  :parameters (or (:parameters t) (:input_schema t))})
                               tools)}])
        ;; Convert messages to Google format
        ;; Handle both initial messages and messages with pre-formatted parts
        contents (mapv (fn [{:keys [role content parts] :as msg}]
                         (cond
                           ;; Already has :parts with :functionResponse or :functionCall - pass through
                           (and parts (some #(or (:functionResponse %) (:functionCall %)) parts))
                           {:role (if (= role "assistant") "model" role)
                            :parts parts}
                           ;; Already has :parts with :text - pass through
                           (and parts (some :text parts))
                           {:role (if (= role "assistant") "model" role)
                            :parts parts}
                           ;; Regular message with content
                           content
                           {:role (if (= role "assistant") "model" role)
                            :parts [{:text content}]}
                           ;; Fallback - already formatted message
                           :else
                           {:role (if (= role "assistant") "model" role)
                            :parts (or parts [{:text ""}])}))
                       messages)
        body (cond-> {:contents contents}
               google-tools (assoc :tools google-tools))
        response (http/post url
                            {:headers {"x-goog-api-key" api-key
                                       "Content-Type" "application/json"}
                             :body (json/generate-string body)
                             :as :json
                             :throw-exceptions false})]
    (if (= 200 (:status response))
      {:ok true :body (:body response)}
      {:ok false :status (:status response) :error (:body response)})))

(defn extract-tool-use-google
  "Extract function calls from Google Gemini response."
  [response]
  (let [parts (get-in response [:body :candidates 0 :content :parts])]
    (->> parts
         (filter :functionCall)
         (mapv (fn [part]
                 (let [fc (:functionCall part)]
                   {:id (:name fc) ;; Google doesn't use separate IDs
                    :name (:name fc)
                    :arguments (:args fc)}))))))

(defn build-tool-result-google
  "Build Google-format messages with tool result."
  [original-messages assistant-parts tool-name result]
  (let [;; Add the assistant's response with function call
        with-assistant (conj (vec original-messages)
                             {:role "assistant"
                              :parts assistant-parts})
        ;; Add the function response
        with-result (conj with-assistant
                          {:role "user"
                           :parts [{:functionResponse
                                    {:name tool-name
                                     :response {:result result}}}]})]
    with-result))

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
                   :content "What is the answer? Use the get_answer tool to retrieve it, then tell me."}]

        ;; First call - LLM should decide to use the tool
        response1 (call-fn model messages [answer-tool])]

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
                _ (is (= "get_answer" (:name tool-use))
                      (str provider " should call get_answer"))

                ;; Build messages with tool result
                ;; Different providers return assistant content at different paths
                assistant-content (or (get-in response1 [:body :content]) ;; Anthropic
                                      (get-in response1 [:body :choices 0 :message]) ;; OpenAI/OpenRouter
                                      (get-in response1 [:body :candidates 0 :content :parts])) ;; Google
                messages2 (build-result-fn
                           messages
                           assistant-content
                           (:id tool-use)
                           answer-value)

                ;; Second call - LLM processes result
                response2 (call-fn model messages2 [answer-tool])]

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
                                (get-in response2 [:body :message :content])
                                ;; Google format
                                (->> (get-in response2 [:body :candidates 0 :content :parts])
                                     (filter :text)
                                     first
                                     :text))]

                (log/info provider "final response:" final-text)

                {:success (boolean (and final-text
                                        (re-find (re-pattern answer-value) final-text)))
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
                   :model (model/direct-model :anthropic)})]
      (is (:tool-called result) "Claude should call the tool")
      (is (:success result) "Claude should return the answer in final response")
      (when-not (:success result)
        (log/error "Test 1 failed:" (pr-str result))))))

(deftest ^:integration test-2-openrouter-claude
  (testing "OpenRouter → Claude Sonnet 4"
    (let [result (run-tool-calling-test
                  {:call-fn call-openrouter
                   :extract-fn extract-tool-use-openrouter
                   :build-result-fn build-tool-result-openrouter
                   :provider "openrouter-claude"
                   :model (model/openrouter-model :anthropic)})]
      (is (:tool-called result) "Claude via OpenRouter should call the tool")
      (is (:success result) "Claude via OpenRouter should return the answer")
      (when-not (:success result)
        (log/error "Test 2 failed:" (pr-str result))))))

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
    :model (model/direct-model :anthropic)})

  ;; Test 2: OpenRouter Claude
  (run-tool-calling-test
   {:call-fn call-openrouter
    :extract-fn extract-tool-use-openrouter
    :build-result-fn build-tool-result-openrouter
    :provider "openrouter-claude"
    :model (model/openrouter-model :anthropic)})

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
  (call-anthropic (model/direct-model :anthropic)
                  [{:role "user" :content "What is the answer? Use get_answer."}]
                  [answer-tool]))
