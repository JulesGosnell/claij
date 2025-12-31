(ns claij.integration.llm-test
  "Feature-based LLM capability testing.
   
   Design principles:
   1. Minimal API calls - 2 calls per LLM regardless of feature count
   2. Maximum verification - all applicable features validated from same calls
   3. Easy to extend - add new feature definition, update capability matrix
   4. Composable - request transformers chain, validators accumulate
   
   Each feature defines:
   - :doc - human readable description
   - :call-1 - {:request-fn (fn [req] req'), :validate-fn (fn [resp] bool)}
   - :call-2 - {:request-fn (fn [req tool-result] req'), :validate-fn (fn [resp] bool)}
   
   See #141 for design rationale."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.tools.logging :as log]
   [clojure.string :as str]
   [cheshire.core :as json]
   [clj-http.client :as http]))

;;==============================================================================
;; Environment
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
              (let [line (subs line 7)
                    [k v] (str/split line #"=" 2)
                    v (-> v str/trim (str/replace #"^['\"]|['\"]$" ""))]
                [(str/trim k) v]))))
    (catch Exception _ {})))

(def ^:private env-cache (delay (load-env-file ".env")))

(defn get-env [key]
  (or (System/getenv key) (get @env-cache key)))

;;==============================================================================
;; Supported LLMs Registry
;;==============================================================================

(def supported-llms
  "LLMs that CLAIJ officially supports.
   Each entry: [service model] -> {:env-key str-or-nil :api-type keyword}"
  {["anthropic" "claude-sonnet-4-5-20250514"]
   {:env-key "ANTHROPIC_API_KEY" :api-type :anthropic}

   ["openrouter" "anthropic/claude-sonnet-4"]
   {:env-key "OPENROUTER_API_KEY" :api-type :openai}

   ["google" "gemini-2.0-flash"]
   {:env-key "GOOGLE_API_KEY" :api-type :google}

   ["xai" "grok-3-fast"]
   {:env-key "XAI_API_KEY" :api-type :openai}

   ["ollama:local" "qwen3:8b"]
   {:env-key nil :api-type :ollama}})

(defn available? [[service _model :as llm-key]]
  (let [{:keys [env-key]} (get supported-llms llm-key)]
    (or (nil? env-key)
        (some? (get-env env-key)))))

;;==============================================================================
;; Tool Definition (used for testing)
;;==============================================================================

(def calculator-tool
  "Simple calculator tool for testing LLM tool calling."
  {:name "calculator"
   :description "A calculator that can add two numbers. Use this to compute sums."
   :input_schema {:type "object"
                  :properties {:a {:type "number" :description "First number"}
                               :b {:type "number" :description "Second number"}}
                  :required ["a" "b"]}})

;;==============================================================================
;; API Callers (per provider)
;;==============================================================================

(defmulti call-api
  "Call LLM API. Returns {:ok bool :body map} or {:ok false :error ...}"
  (fn [llm-key _messages _tools] (get-in supported-llms [llm-key :api-type])))

(defmethod call-api :anthropic
  [[_service model] messages tools]
  (let [api-key (get-env "ANTHROPIC_API_KEY")
        anthropic-tools (when (seq tools)
                          (mapv (fn [t]
                                  {:name (:name t)
                                   :description (:description t)
                                   :input_schema (:input_schema t)})
                                tools))
        body (cond-> {:model model
                      :max_tokens 1024
                      :messages messages}
               anthropic-tools (assoc :tools anthropic-tools))
        response (http/post "https://api.anthropic.com/v1/messages"
                            {:headers {"x-api-key" api-key
                                       "anthropic-version" "2023-06-01"
                                       "content-type" "application/json"}
                             :body (json/generate-string body)
                             :as :json
                             :throw-exceptions false})]
    (if (= 200 (:status response))
      {:ok true :body (:body response) :api-type :anthropic}
      {:ok false :status (:status response) :error (:body response)})))

(defmethod call-api :openai
  [[service model] messages tools]
  (let [;; Different endpoints for different services
        {:keys [url api-key-env auth-header]}
        (case service
          "openrouter" {:url "https://openrouter.ai/api/v1/chat/completions"
                        :api-key-env "OPENROUTER_API_KEY"
                        :auth-header "Authorization"}
          "xai" {:url "https://api.x.ai/v1/chat/completions"
                 :api-key-env "XAI_API_KEY"
                 :auth-header "Authorization"})
        api-key (when api-key-env (get-env api-key-env))
        openai-tools (when (seq tools)
                       (mapv (fn [t]
                               {:type "function"
                                :function {:name (:name t)
                                           :description (:description t)
                                           :parameters (:input_schema t)}})
                             tools))
        body (cond-> {:model model
                      :messages messages
                      :stream false
                      :max_tokens 1024}
               openai-tools (assoc :tools openai-tools))
        headers (cond-> {"Content-Type" "application/json"}
                  api-key (assoc auth-header (str "Bearer " api-key)))
        response (http/post url
                            {:headers headers
                             :body (json/generate-string body)
                             :as :json
                             :throw-exceptions false
                             :socket-timeout 120000
                             :connection-timeout 10000})]
    (if (= 200 (:status response))
      {:ok true :body (:body response) :api-type :openai}
      {:ok false :status (:status response) :error (:body response)})))

(defmethod call-api :ollama
  [[_service model] messages tools]
  (let [ollama-tools (when (seq tools)
                       (mapv (fn [t]
                               {:type "function"
                                :function {:name (:name t)
                                           :description (:description t)
                                           :parameters (:input_schema t)}})
                             tools))
        body (cond-> {:model model
                      :messages messages
                      :stream false}
               ollama-tools (assoc :tools ollama-tools))
        response (http/post "http://prognathodon:11434/api/chat"
                            {:headers {"Content-Type" "application/json"}
                             :body (json/generate-string body)
                             :as :json
                             :throw-exceptions false
                             :socket-timeout 120000
                             :connection-timeout 10000})]
    (if (= 200 (:status response))
      {:ok true :body (:body response) :api-type :ollama}
      {:ok false :status (:status response) :error (:body response)})))

(defmethod call-api :google
  [[_service model] messages tools]
  (let [api-key (get-env "GOOGLE_API_KEY")
        url (str "https://generativelanguage.googleapis.com/v1beta/models/" model ":generateContent")
        google-tools (when (seq tools)
                       [{:function_declarations
                         (mapv (fn [t]
                                 {:name (:name t)
                                  :description (:description t)
                                  :parameters (:input_schema t)})
                               tools)}])
        contents (mapv (fn [{:keys [role content parts]}]
                         (cond
                           parts {:role (if (= role "assistant") "model" role) :parts parts}
                           content {:role (if (= role "assistant") "model" role) :parts [{:text content}]}
                           :else {:role role :parts [{:text ""}]}))
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
      {:ok true :body (:body response) :api-type :google}
      {:ok false :status (:status response) :error (:body response)})))

;;==============================================================================
;; Response Extractors (normalize across providers)
;;==============================================================================

(defmulti extract-tool-calls
  "Extract tool calls from response. Returns [{:id :name :arguments}]"
  (fn [response] (:api-type response)))

(defmethod extract-tool-calls :anthropic [response]
  (->> (get-in response [:body :content])
       (filter #(= "tool_use" (:type %)))
       (mapv (fn [{:keys [id name input]}]
               {:id id :name name :arguments input}))))

(defmethod extract-tool-calls :openai [response]
  (let [message (or (get-in response [:body :choices 0 :message])
                    (get-in response [:body :message]))
        tool-calls (:tool_calls message)]
    (mapv (fn [{:keys [id function]}]
            {:id (or id (str (random-uuid)))
             :name (:name function)
             :arguments (if (string? (:arguments function))
                          (json/parse-string (:arguments function) true)
                          (:arguments function))})
          tool-calls)))

(defmethod extract-tool-calls :ollama [response]
  (let [message (get-in response [:body :message])
        tool-calls (:tool_calls message)]
    (mapv (fn [{:keys [function]}]
            {:id (str (random-uuid)) ;; Ollama doesn't provide IDs
             :name (:name function)
             :arguments (:arguments function)})
          tool-calls)))

(defmethod extract-tool-calls :google [response]
  (let [parts (get-in response [:body :candidates 0 :content :parts])]
    (->> parts
         (filter :functionCall)
         (mapv (fn [part]
                 (let [fc (:functionCall part)]
                   {:id (:name fc)
                    :name (:name fc)
                    :arguments (:args fc)}))))))

(defmulti extract-text
  "Extract text content from response."
  (fn [response] (:api-type response)))

(defmethod extract-text :anthropic [response]
  (->> (get-in response [:body :content])
       (filter #(= "text" (:type %)))
       first
       :text))

(defmethod extract-text :openai [response]
  (or (get-in response [:body :choices 0 :message :content])
      (get-in response [:body :message :content])))

(defmethod extract-text :ollama [response]
  (get-in response [:body :message :content]))

(defmethod extract-text :google [response]
  (->> (get-in response [:body :candidates 0 :content :parts])
       (filter :text)
       first
       :text))

;;==============================================================================
;; Message Builders (for call-2 with tool results)
;;==============================================================================

(defmulti build-tool-result-messages
  "Build messages including tool result for second call."
  (fn [api-type _orig-messages _response _tool-call _result] api-type))

(defmethod build-tool-result-messages :anthropic
  [_ orig-messages response tool-call result]
  (let [assistant-content (get-in response [:body :content])]
    (conj (vec orig-messages)
          {:role "assistant" :content assistant-content}
          {:role "user"
           :content [{:type "tool_result"
                      :tool_use_id (:id tool-call)
                      :content (str result)}]})))

(defmethod build-tool-result-messages :openai
  [_ orig-messages response tool-call result]
  (let [assistant-message (or (get-in response [:body :choices 0 :message])
                              (get-in response [:body :message]))
        ;; Ensure arguments is JSON string (OpenAI/OpenRouter format)
        fixed-assistant (update-in assistant-message [:tool_calls]
                                   (fn [calls]
                                     (mapv (fn [call]
                                             (update call :function
                                                     (fn [f]
                                                       (let [args (or (:arguments f) {})]
                                                         (assoc f :arguments
                                                                (if (string? args) args (json/generate-string args)))))))
                                           calls)))]
    (conj (vec orig-messages)
          fixed-assistant
          {:role "tool"
           :tool_call_id (:id tool-call)
           :name (:name tool-call)
           :content (str result)})))

(defmethod build-tool-result-messages :ollama
  [_ orig-messages response tool-call result]
  (let [assistant-message (get-in response [:body :message])
        ;; Ollama wants arguments as object, NOT JSON string
        fixed-assistant (update-in assistant-message [:tool_calls]
                                   (fn [calls]
                                     (mapv (fn [call]
                                             (update call :function
                                                     (fn [f]
                                                       (let [args (or (:arguments f) {})]
                                                         ;; Keep as object, parse if string
                                                         (assoc f :arguments
                                                                (if (string? args)
                                                                  (json/parse-string args true)
                                                                  args))))))
                                           calls)))]
    (conj (vec orig-messages)
          fixed-assistant
          {:role "tool"
           :tool_call_id (:id tool-call)
           :name (:name tool-call)
           :content (str result)})))

(defmethod build-tool-result-messages :google
  [_ orig-messages response tool-call result]
  (let [assistant-parts (get-in response [:body :candidates 0 :content :parts])]
    (conj (vec orig-messages)
          {:role "assistant" :parts assistant-parts}
          {:role "user"
           :parts [{:functionResponse
                    {:name (:name tool-call)
                     :response {:result result}}}]})))

;;==============================================================================
;; Stub Tool Execution
;;==============================================================================

(defn stub-tool-execution
  "Execute tool call with stub. Returns result value."
  [{:keys [name arguments]}]
  (case name
    "calculator" (let [{:keys [a b]} arguments]
                   (+ a b))
    (throw (ex-info "Unknown tool" {:name name}))))

;;==============================================================================
;; Feature Definitions
;;==============================================================================

(def features
  "Feature definitions for LLM capability testing.
   Each feature has :doc, and optionally :call-1/:call-2 with :request-fn/:validate-fn."

  {:base-tool-call
   {:doc "Basic text→tool→text roundtrip"
    :call-1 {:validate-fn (fn [response tool-calls]
                            (and (seq tool-calls)
                                 (= "calculator" (:name (first tool-calls)))))}
    :call-2 {:validate-fn (fn [response text]
                            (and (some? text)
                                 (re-find #"59" text)))}}

   :content-xor-tools
   {:doc "Response has content OR tools, never both"
    :call-1 {:validate-fn (fn [response tool-calls]
                            (let [text (extract-text response)]
                              ;; If we have tool calls, text should be nil or empty
                              (if (seq tool-calls)
                                (or (nil? text) (str/blank? text))
                                true)))}
    :call-2 {:validate-fn (fn [response text]
                            (let [tool-calls (extract-tool-calls response)]
                              ;; Final response should have text, not tools
                              (and (some? text)
                                   (empty? tool-calls))))}}})

;;==============================================================================
;; Capability Matrix
;;==============================================================================

(def llm-capabilities
  "Which features each LLM supports. Start minimal, add as verified."
  {["anthropic" "claude-sonnet-4-5-20250514"] #{:base-tool-call :content-xor-tools}
   ["openrouter" "anthropic/claude-sonnet-4"] #{:base-tool-call} ;; Claude may include text with tool calls
   ["google" "gemini-2.0-flash"] #{:base-tool-call}
   ["xai" "grok-3-fast"] #{:base-tool-call :content-xor-tools}
   ["ollama:local" "qwen3:8b"] #{:base-tool-call}})

;;==============================================================================
;; Test Runner
;;==============================================================================

(defn run-feature-test
  "Run capability test for a single LLM.
   Makes 2 API calls, validates all applicable features from each."
  [llm-key enabled-feature-keys]
  (let [feature-defs (keep #(get features %) enabled-feature-keys)
        messages-1 [{:role "user"
                     :content "What is 42 + 17? Use the calculator tool to compute this."}]
        tools [calculator-tool]]

    ;; Call 1: text → tool
    (testing "call-1: text→tool"
      (let [resp-1 (call-api llm-key messages-1 tools)]
        (is (:ok resp-1) (str "API call should succeed: " (pr-str (:error resp-1))))

        (when (:ok resp-1)
          (let [tool-calls (extract-tool-calls resp-1)]
            ;; Run all call-1 validators
            (doseq [f feature-defs
                    :let [validate (get-in f [:call-1 :validate-fn])]
                    :when validate]
              (testing (:doc f)
                (is (validate resp-1 tool-calls))))

            ;; Proceed to call 2 if we got tool calls
            (when (seq tool-calls)
              (let [tool-call (first tool-calls)
                    result (stub-tool-execution tool-call)
                    api-type (:api-type resp-1)
                    messages-2 (build-tool-result-messages api-type messages-1 resp-1 tool-call result)]

                ;; Call 2: tool-result → text
                (testing "call-2: tool-result→text"
                  (let [resp-2 (call-api llm-key messages-2 tools)]
                    (is (:ok resp-2) (str "API call should succeed: " (pr-str (:error resp-2))))

                    (when (:ok resp-2)
                      (let [text (extract-text resp-2)]
                        ;; Run all call-2 validators
                        (doseq [f feature-defs
                                :let [validate (get-in f [:call-2 :validate-fn])]
                                :when validate]
                          (testing (:doc f)
                            (is (validate resp-2 text))))))))))))))))

;;==============================================================================
;; Main Test
;;==============================================================================

(deftest ^:integration llm-capability-test
  (doseq [[llm-key feature-set] llm-capabilities]
    (testing (str "LLM: " (first llm-key) "/" (second llm-key))
      (if (available? llm-key)
        (run-feature-test llm-key feature-set)
        (is false (str (first llm-key) " unavailable - "
                       (get-in supported-llms [llm-key :env-key]) " not set"))))))

;;==============================================================================
;; REPL Testing
;;==============================================================================

(comment
  ;; Test single provider
  (run-feature-test ["anthropic" "claude-sonnet-4-5-20250514"] #{:base-tool-call})

  ;; Run all
  (clojure.test/run-tests 'claij.integration.llm-test))
