(ns claij.poc.xor-tool-calling-poc-test
  "PoC test: Validate LLMs respect XOR constraint (tool_calls OR content, never both).
   
   Before integrating native tool calling into production (#126), we need to verify
   that LLMs can follow our constraint. They're trained on chat UIs where returning
   both is normal behavior.
   
   Story #128: Validate LLM XOR constraint"
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.tools.logging :as log]
   [clojure.string :as str]
   [cheshire.core :as json]
   [clj-http.client :as http]
   [claij.model :as model]))

;;==============================================================================
;; Environment Loading (copied from native_tool_calling_poc_test)
;;==============================================================================

(defn load-env-file [path]
  (try
    (let [content (slurp path)
          lines (str/split-lines content)]
      (into {}
            (for [line lines
                  :when (and (not (str/blank? line))
                             (str/starts-with? line "export ")
                             (str/includes? line "="))]
              (let [line (subs line 7)
                    [k v] (str/split line #"=" 2)
                    v (-> v str/trim (str/replace #"^['\"]|['\"]$" ""))]
                [(str/trim k) v]))))
    (catch Exception _ {})))

(def ^:dynamic *env-vars* (delay (load-env-file ".env")))

(defn get-env [key]
  (or (System/getenv key) (get @*env-vars* key)))

;;==============================================================================
;; XOR Prompt
;;==============================================================================

(def xor-system-prompt
  "You are a helpful assistant with access to tools.

CRITICAL RULE: Return EITHER a tool call OR text content, NEVER both.

- If you need to use a tool: Return ONLY the tool call. Do NOT include any text message.
- If you have a final answer: Return ONLY text content. Do NOT include any tool calls.

Returning both tool_calls and content together is a protocol error and will be rejected.")

(def xor-error-message
  "ERROR: You returned both tool_calls and content. This violates the protocol.
Return ONLY one or the other:
- Tool call with NO text content, OR  
- Text content with NO tool calls")

;;==============================================================================
;; Tool Definition
;;==============================================================================

(def answer-tool
  {:name "get_answer"
   :description "Returns the secret answer. You must call this tool to get it."
   :parameters {:type "object" :properties {} :required []}})

(def answer-value "42")

;;==============================================================================
;; Response Analysis
;;==============================================================================

(defn analyze-response
  "Analyze response for XOR compliance.
   Returns {:xor-ok? bool :has-tool-calls? bool :has-content? bool :details ...}"
  [provider response]
  (case provider
    :openrouter
    (let [message (get-in response [:body :choices 0 :message])
          tool-calls (:tool_calls message)
          content (:content message)
          has-tools? (boolean (seq tool-calls))
          has-content? (boolean (and content (not (str/blank? content))))]
      {:xor-ok? (not (and has-tools? has-content?))
       :has-tool-calls? has-tools?
       :has-content? has-content?
       :tool-calls tool-calls
       :content content})

    :anthropic
    (let [content-blocks (get-in response [:body :content])
          tool-uses (filter #(= "tool_use" (:type %)) content-blocks)
          text-blocks (filter #(= "text" (:type %)) content-blocks)
          text-content (str/join " " (map :text text-blocks))
          has-tools? (boolean (seq tool-uses))
          has-content? (boolean (and (seq text-blocks)
                                     (not (str/blank? text-content))))]
      {:xor-ok? (not (and has-tools? has-content?))
       :has-tool-calls? has-tools?
       :has-content? has-content?
       :tool-calls tool-uses
       :content text-content})

    :ollama
    (let [message (get-in response [:body :message])
          tool-calls (:tool_calls message)
          content (:content message)
          has-tools? (boolean (seq tool-calls))
          has-content? (boolean (and content (not (str/blank? content))))]
      {:xor-ok? (not (and has-tools? has-content?))
       :has-tool-calls? has-tools?
       :has-content? has-content?
       :tool-calls tool-calls
       :content content})))

;;==============================================================================
;; API Callers
;;==============================================================================

(defn call-openrouter [model messages tools]
  (let [api-key (get-env "OPENROUTER_API_KEY")
        _ (assert api-key "OPENROUTER_API_KEY not set")
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

(defn call-anthropic [model messages tools]
  (let [api-key (get-env "ANTHROPIC_API_KEY")
        _ (assert api-key "ANTHROPIC_API_KEY not set")
        ;; Anthropic requires system prompt separate from messages
        system-msg (first (filter #(= "system" (:role %)) messages))
        user-msgs (remove #(= "system" (:role %)) messages)
        anthropic-tools (when (seq tools)
                          (mapv (fn [t]
                                  {:name (:name t)
                                   :description (:description t)
                                   :input_schema (or (:parameters t)
                                                     {:type "object" :properties {}})})
                                tools))
        body (cond-> {:model model
                      :max_tokens 1024
                      :messages (vec user-msgs)}
               system-msg (assoc :system (:content system-msg))
               anthropic-tools (assoc :tools anthropic-tools))
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

(defn call-ollama
  ([model messages tools] (call-ollama model messages tools "prognathodon"))
  ([model messages tools host]
   (let [openai-tools (when (seq tools)
                        (mapv (fn [t] {:type "function" :function t}) tools))
         body (cond-> {:model model
                       :messages messages
                       :stream false}
                openai-tools (assoc :tools openai-tools))
         response (http/post (str "http://" host ":11434/api/chat")
                             {:headers {"Content-Type" "application/json"}
                              :body (json/generate-string body)
                              :as :json
                              :throw-exceptions false
                              :socket-timeout 120000
                              :connection-timeout 10000})]
     (if (= 200 (:status response))
       {:ok true :body (:body response)}
       {:ok false :status (:status response) :error (:body response)}))))

;;==============================================================================
;; XOR Test Runner
;;==============================================================================

(defn run-xor-test
  "Run XOR compliance test for a model.
   
   Returns:
   {:model string
    :provider keyword
    :first-call {:xor-ok? bool :has-tool-calls? bool :has-content? bool}
    :retry-needed? bool
    :retry-result (if retry needed)
    :final-success? bool}"
  [{:keys [call-fn provider model]}]
  (log/info "XOR test:" provider model)

  (let [messages [{:role "system" :content xor-system-prompt}
                  {:role "user" :content "What is the answer? Use the get_answer tool."}]

        ;; First call
        response1 (call-fn model messages [answer-tool])]

    (if-not (:ok response1)
      {:model model
       :provider provider
       :error (:error response1)
       :final-success? false}

      (let [analysis1 (analyze-response provider response1)]
        (log/info "  First call:" (select-keys analysis1 [:xor-ok? :has-tool-calls? :has-content?]))
        (when-not (:xor-ok? analysis1)
          (log/warn "  XOR VIOLATED - content:" (pr-str (:content analysis1))))

        (if (:xor-ok? analysis1)
          ;; XOR respected on first try
          {:model model
           :provider provider
           :first-call analysis1
           :retry-needed? false
           :final-success? true}

          ;; XOR violated - try retry with error message
          (let [_ (log/info "  Retrying with error message...")
                ;; Build retry messages with the assistant's bad response and our error
                retry-messages (conj (vec messages)
                                     {:role "assistant"
                                      :content (or (:content analysis1) "")
                                      :tool_calls (:tool-calls analysis1)}
                                     {:role "user"
                                      :content xor-error-message})
                response2 (call-fn model retry-messages [answer-tool])]

            (if-not (:ok response2)
              {:model model
               :provider provider
               :first-call analysis1
               :retry-needed? true
               :retry-error (:error response2)
               :final-success? false}

              (let [analysis2 (analyze-response provider response2)]
                (log/info "  Retry result:" (select-keys analysis2 [:xor-ok? :has-tool-calls? :has-content?]))
                {:model model
                 :provider provider
                 :first-call analysis1
                 :retry-needed? true
                 :retry-result analysis2
                 :final-success? (:xor-ok? analysis2)}))))))))

;;==============================================================================
;; Batch Test Runner
;;==============================================================================

(defn run-batch-test
  "Run XOR test N times and aggregate results."
  [test-config n]
  (let [results (for [i (range n)]
                  (do
                    (log/info (str "Run " (inc i) "/" n))
                    (run-xor-test test-config)))
        successes (filter :final-success? results)
        xor-first-try (filter #(and (:final-success? %) (not (:retry-needed? %))) results)
        xor-after-retry (filter #(and (:final-success? %) (:retry-needed? %)) results)]
    {:model (:model test-config)
     :provider (:provider test-config)
     :total n
     :successes (count successes)
     :xor-first-try (count xor-first-try)
     :xor-after-retry (count xor-after-retry)
     :failures (- n (count successes))
     :success-rate (double (/ (count successes) n))
     :details results}))

;;==============================================================================
;; Tests
;;==============================================================================

(deftest ^:long-running test-xor-openrouter-claude
  (testing "OpenRouter Claude - XOR compliance"
    (let [result (run-batch-test
                  {:call-fn call-openrouter
                   :provider :openrouter
                   :model (model/openrouter-model :anthropic)}
                  5)]
      (log/info "Claude results:" (dissoc result :details))
      (is (>= (:success-rate result) 0.8)
          (str "Claude should respect XOR at least 80% of the time: " result)))))

(deftest ^:long-running test-xor-openrouter-openai
  (testing "OpenRouter OpenAI - XOR compliance"
    (let [result (run-batch-test
                  {:call-fn call-openrouter
                   :provider :openrouter
                   :model (model/openrouter-model :openai)}
                  5)]
      (log/info "OpenAI results:" (dissoc result :details))
      (is (>= (:success-rate result) 0.8)
          (str "OpenAI should respect XOR at least 80% of the time: " result)))))

(deftest ^:long-running test-xor-openrouter-xai
  (testing "OpenRouter xAI - XOR compliance"
    (let [result (run-batch-test
                  {:call-fn call-openrouter
                   :provider :openrouter
                   :model (model/openrouter-model :xai)}
                  5)]
      (log/info "xAI results:" (dissoc result :details))
      (is (>= (:success-rate result) 0.8)
          (str "xAI should respect XOR at least 80% of the time: " result)))))

(deftest ^:long-running test-xor-openrouter-google
  (testing "OpenRouter Google - XOR compliance"
    (let [result (run-batch-test
                  {:call-fn call-openrouter
                   :provider :openrouter
                   :model (model/openrouter-model :google)}
                  5)]
      (log/info "Google results:" (dissoc result :details))
      (is (>= (:success-rate result) 0.8)
          (str "Google should respect XOR at least 80% of the time: " result)))))

(deftest ^:long-running test-xor-anthropic-direct
  (testing "Anthropic Direct - XOR compliance"
    (let [result (run-batch-test
                  {:call-fn call-anthropic
                   :provider :anthropic
                   :model (model/direct-model :anthropic)}
                  5)]
      (log/info "Anthropic direct results:" (dissoc result :details))
      (is (>= (:success-rate result) 0.8)
          (str "Anthropic should respect XOR at least 80% of the time: " result)))))

(deftest ^:long-running test-xor-ollama-qwen3
  (testing "Ollama qwen3:8b - XOR compliance"
    (let [result (run-batch-test
                  {:call-fn call-ollama
                   :provider :ollama
                   :model "qwen3:8b"}
                  5)]
      (log/info "qwen3:8b results:" (dissoc result :details))
      (is (>= (:success-rate result) 0.6)
          (str "qwen3:8b XOR compliance: " result)))))

(deftest ^:long-running test-xor-ollama-mistral
  (testing "Ollama mistral:7b - XOR compliance"
    (let [result (run-batch-test
                  {:call-fn call-ollama
                   :provider :ollama
                   :model "mistral:7b"}
                  5)]
      (log/info "mistral:7b results:" (dissoc result :details))
      (is (>= (:success-rate result) 0.6)
          (str "mistral:7b XOR compliance: " result)))))

(deftest ^:long-running test-xor-ollama-granite
  (testing "Ollama granite4:3b - XOR compliance"
    (let [result (run-batch-test
                  {:call-fn call-ollama
                   :provider :ollama
                   :model "granite4:3b"}
                  5)]
      (log/info "granite4:3b results:" (dissoc result :details))
      (is (>= (:success-rate result) 0.6)
          (str "granite4:3b XOR compliance: " result)))))
