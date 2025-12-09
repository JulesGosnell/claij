(ns claij.llm
  "Unified LLM client with multimethod dispatch.
   
   Uses direct provider APIs when available (cheaper - no OpenRouter commission),
   falls back to OpenRouter for unknown providers.
   
   Interface matches OpenAI/OpenRouter message format:
   [{\"role\" \"system\" \"content\" \"...\"}
    {\"role\" \"user\" \"content\" \"...\"}
    {\"role\" \"assistant\" \"content\" \"...\"}]
   
   Dynamic binding *http-post* allows stubbing for tests."
  (:require
   [clj-http.client :as http]
   [clojure.tools.logging :as log]
   [claij.util :refer [assert-env-var clj->json json->clj]]))

;;------------------------------------------------------------------------------
;; Dynamic HTTP binding for testing
;;------------------------------------------------------------------------------

(def ^:dynamic *http-post*
  "HTTP POST function. Rebind in tests to stub API calls.
   
   Signature: (fn [url opts] response)
   where opts is {:headers {...} :body \"json-string\" :async? bool ...}
   and response is {:status int :body \"json-string\"}"
  http/post)

;;------------------------------------------------------------------------------
;; API Key helpers
;;------------------------------------------------------------------------------

(defn- env-key
  "Get API key from environment, returns nil if not set (doesn't throw)."
  [var-name]
  (System/getenv var-name))

(defn- require-key
  "Get API key from environment, throws if not set."
  [var-name]
  (or (env-key var-name)
      (throw (ex-info (str "Missing required environment variable: " var-name)
                      {:var var-name}))))

;;------------------------------------------------------------------------------
;; Response extraction helpers
;;------------------------------------------------------------------------------

(defn- extract-openai-content
  "Extract content from OpenAI/OpenRouter style response."
  [response-body]
  (let [parsed (json->clj response-body)]
    (get-in parsed [:choices 0 :message :content])))

(defn- extract-anthropic-content
  "Extract content from Anthropic style response."
  [response-body]
  (let [parsed (json->clj response-body)]
    (get-in parsed [:content 0 :text])))

(defn- extract-google-content
  "Extract content from Google Gemini style response."
  [response-body]
  (let [parsed (json->clj response-body)]
    (get-in parsed [:candidates 0 :content :parts 0 :text])))

;;------------------------------------------------------------------------------
;; Provider dispatch
;;------------------------------------------------------------------------------

(defmulti call-llm
  "Call an LLM provider. Dispatches on provider string.
   
   Args:
     provider - Provider name: \"anthropic\", \"openai\", \"google\", \"x-ai\"
     model    - Model identifier (provider-specific)
     messages - Vector of message maps with \"role\" and \"content\" keys
     opts     - Optional map with :async? :on-success :on-error
   
   Returns:
     Sync mode (default): Response content string
     Async mode: nil (calls :on-success or :on-error callbacks)
   
   Falls back to OpenRouter for unknown providers."
  (fn [provider _model _messages _opts] provider))

;;------------------------------------------------------------------------------
;; Anthropic (Claude)
;;------------------------------------------------------------------------------

(defmethod call-llm "anthropic"
  [_provider model messages {:keys [async? on-success on-error max-tokens]
                             :or {max-tokens 4096}}]
  (let [api-key (require-key "ANTHROPIC_API_KEY")
        ;; Anthropic uses separate system parameter
        system-msg (first (filter #(= "system" (get % "role")) messages))
        non-system (remove #(= "system" (get % "role")) messages)

        url "https://api.anthropic.com/v1/messages"
        headers {"x-api-key" api-key
                 "anthropic-version" "2023-06-01"
                 "content-type" "application/json"}
        body (cond-> {:model model
                      :max_tokens max-tokens
                      :messages (vec non-system)}
               system-msg (assoc :system (get system-msg "content")))]

    (log/info (str "  [Anthropic] " model))

    (if async?
      (*http-post* url
                   {:headers headers
                    :body (clj->json body)
                    :async? true}
                   (fn [response]
                     (try
                       (on-success (extract-anthropic-content (:body response)))
                       (catch Exception e
                         (if on-error
                           (on-error {:error (.getMessage e) :response response})
                           (throw e)))))
                   (fn [exception]
                     (if on-error
                       (on-error {:error (.getMessage exception) :exception exception})
                       (throw exception))))
      ;; Sync
      (let [response (*http-post* url {:headers headers
                                       :body (clj->json body)
                                       :throw-exceptions false})]
        (extract-anthropic-content (:body response))))))

;;------------------------------------------------------------------------------
;; OpenAI (GPT)
;;------------------------------------------------------------------------------

(defmethod call-llm "openai"
  [_provider model messages {:keys [async? on-success on-error]}]
  (let [api-key (require-key "OPENAI_API_KEY")
        url "https://api.openai.com/v1/chat/completions"
        headers {"Authorization" (str "Bearer " api-key)
                 "content-type" "application/json"}
        body {:model model
              :messages messages}]

    (log/info (str "  [OpenAI] " model))

    (if async?
      (*http-post* url
                   {:headers headers
                    :body (clj->json body)
                    :async? true}
                   (fn [response]
                     (try
                       (on-success (extract-openai-content (:body response)))
                       (catch Exception e
                         (if on-error
                           (on-error {:error (.getMessage e) :response response})
                           (throw e)))))
                   (fn [exception]
                     (if on-error
                       (on-error {:error (.getMessage exception) :exception exception})
                       (throw exception))))
      ;; Sync
      (let [response (*http-post* url {:headers headers
                                       :body (clj->json body)
                                       :throw-exceptions false})]
        (extract-openai-content (:body response))))))

;;------------------------------------------------------------------------------
;; Google (Gemini)
;;------------------------------------------------------------------------------

(defmethod call-llm "google"
  [_provider model messages {:keys [async? on-success on-error]}]
  (let [api-key (require-key "GEMINI_API_KEY")
        url (str "https://generativelanguage.googleapis.com/v1beta/models/"
                 model ":generateContent")
        headers {"x-goog-api-key" api-key
                 "content-type" "application/json"}
        ;; Convert OpenAI format to Gemini format
        ;; Gemini uses contents: [{role: "user", parts: [{text: "..."}]}]
        contents (mapv (fn [{role "role" content "content"}]
                         {:role (if (= role "assistant") "model" role)
                          :parts [{:text content}]})
                       ;; Gemini doesn't have system role, prepend to first user msg
                       (let [system-msg (first (filter #(= "system" (get % "role")) messages))
                             non-system (remove #(= "system" (get % "role")) messages)]
                         (if system-msg
                           (update-in (vec non-system) [0 "content"]
                                      #(str (get system-msg "content") "\n\n" %))
                           non-system)))
        body {:contents contents}]

    (log/info (str "  [Google] " model))

    (if async?
      (*http-post* url
                   {:headers headers
                    :body (clj->json body)
                    :async? true}
                   (fn [response]
                     (try
                       (on-success (extract-google-content (:body response)))
                       (catch Exception e
                         (if on-error
                           (on-error {:error (.getMessage e) :response response})
                           (throw e)))))
                   (fn [exception]
                     (if on-error
                       (on-error {:error (.getMessage exception) :exception exception})
                       (throw exception))))
      ;; Sync
      (let [response (*http-post* url {:headers headers
                                       :body (clj->json body)
                                       :throw-exceptions false})]
        (extract-google-content (:body response))))))

;;------------------------------------------------------------------------------
;; xAI (Grok)
;;------------------------------------------------------------------------------

(defmethod call-llm "x-ai"
  [_provider model messages {:keys [async? on-success on-error]}]
  (let [api-key (require-key "XAI_API_KEY")
        url "https://api.x.ai/v1/chat/completions"
        headers {"Authorization" (str "Bearer " api-key)
                 "content-type" "application/json"}
        ;; xAI uses OpenAI-compatible format
        body {:model model
              :messages messages}]

    (log/info (str "  [xAI] " model))

    (if async?
      (*http-post* url
                   {:headers headers
                    :body (clj->json body)
                    :async? true}
                   (fn [response]
                     (try
                       (on-success (extract-openai-content (:body response)))
                       (catch Exception e
                         (if on-error
                           (on-error {:error (.getMessage e) :response response})
                           (throw e)))))
                   (fn [exception]
                     (if on-error
                       (on-error {:error (.getMessage exception) :exception exception})
                       (throw exception))))
      ;; Sync
      (let [response (*http-post* url {:headers headers
                                       :body (clj->json body)
                                       :throw-exceptions false})]
        (extract-openai-content (:body response))))))

;;------------------------------------------------------------------------------
;; OpenRouter (fallback for all providers)
;;------------------------------------------------------------------------------

(defmethod call-llm :default
  [provider model messages {:keys [async? on-success on-error]}]
  (let [api-key (require-key "OPENROUTER_API_KEY")
        url "https://openrouter.ai/api/v1/chat/completions"
        headers {"Authorization" (str "Bearer " api-key)
                 "content-type" "application/json"}
        ;; OpenRouter uses provider/model format
        body {:model (str provider "/" model)
              :messages messages}]

    (log/info (str "  [OpenRouter] " provider "/" model))

    (if async?
      (*http-post* url
                   {:headers headers
                    :body (clj->json body)
                    :async? true}
                   (fn [response]
                     (try
                       (on-success (extract-openai-content (:body response)))
                       (catch Exception e
                         (if on-error
                           (on-error {:error (.getMessage e) :response response})
                           (throw e)))))
                   (fn [exception]
                     (if on-error
                       (on-error {:error (.getMessage exception) :exception exception})
                       (throw exception))))
      ;; Sync
      (let [response (*http-post* url {:headers headers
                                       :body (clj->json body)
                                       :throw-exceptions false})]
        (extract-openai-content (:body response))))))

;;------------------------------------------------------------------------------
;; Provider availability
;;------------------------------------------------------------------------------

(def provider-env-vars
  "Map of provider to required environment variable."
  {"anthropic" "ANTHROPIC_API_KEY"
   "openai" "OPENAI_API_KEY"
   "google" "GEMINI_API_KEY"
   "x-ai" "XAI_API_KEY"})

(defn available-providers
  "Returns set of providers that have API keys configured."
  []
  (into #{}
        (comp (filter (fn [[_ env-var]] (env-key env-var)))
              (map first))
        provider-env-vars))

(defn direct-provider?
  "Returns true if provider has direct API support (not just OpenRouter)."
  [provider]
  (contains? provider-env-vars provider))

(defn provider-available?
  "Returns true if provider's API key is configured."
  [provider]
  (if-let [env-var (get provider-env-vars provider)]
    (some? (env-key env-var))
    ;; Unknown provider - check if OpenRouter is available
    (some? (env-key "OPENROUTER_API_KEY"))))
