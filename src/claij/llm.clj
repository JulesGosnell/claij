(ns claij.llm
  "Unified LLM client with multimethod dispatch and async HTTP.
   
   Uses direct provider APIs when available (cheaper - no OpenRouter commission),
   falls back to OpenRouter for unknown providers or when direct API key not set.
   
   Request transforms:  openrouter->provider - convert canonical format to provider-native
   Response transforms: provider->openrouter - convert provider response to canonical format
   
   Direct provider routing is enabled by setting the corresponding env var:
   - ANTHROPIC_API_KEY -> anthropic calls go direct
   - GOOGLE_API_KEY    -> google calls go direct  
   - OPENAI_API_KEY    -> openai calls go direct
   - XAI_API_KEY       -> x-ai calls go direct
   
   If a provider's API key is not set, calls route through OpenRouter."
  (:require
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [clojure.edn :as edn]
   [clj-http.client :refer [post]]
   [cheshire.core :as json]
   [claij.util :refer [clj->json json->clj make-retrier]]
   [claij.provider.openrouter :as openrouter]
   [claij.provider.anthropic :as anthropic]
   [claij.provider.google :as google]
   [claij.provider.openai :as openai]
   [claij.provider.xai :as xai]))

;;------------------------------------------------------------------------------
;; Multimethod dispatch on provider
;;------------------------------------------------------------------------------

(defmulti openrouter->provider
  "Transform OpenRouter-format request to provider-native request.
   
   Args:
     provider - provider string
     model    - model string  
     messages - [{\"role\" \"user\" \"content\" \"...\"}]
   
   Returns:
     {:url     string
      :headers map  
      :body    map (to be JSON encoded)}
   
   Dispatches on provider string. Falls back to OpenRouter for unknown providers
   or when the provider's direct API key is not set."
  (fn [provider _model _messages] provider))

(defmulti provider->openrouter
  "Transform provider-native response to content string.
   
   Args:
     provider - provider string
     response - parsed JSON response from provider
   
   Returns:
     Content string extracted from response
   
   Dispatches on provider string."
  (fn [provider _response] provider))

;;------------------------------------------------------------------------------
;; OpenRouter (default fallback)
;;------------------------------------------------------------------------------

(defn openrouter-api-key
  "Get OpenRouter API key from environment. Public for test mocking."
  []
  (or (System/getenv "OPENROUTER_API_KEY")
      (throw (ex-info "OPENROUTER_API_KEY not set" {}))))

(defmethod openrouter->provider :default
  [provider model messages]
  (openrouter/openrouter->openrouter provider model messages (openrouter-api-key)))

(defmethod provider->openrouter :default
  [_provider response]
  (openrouter/openrouter->openrouter-response response))

;;------------------------------------------------------------------------------
;; Anthropic (Claude) - only if ANTHROPIC_API_KEY is set
;;------------------------------------------------------------------------------

(when-let [api-key (System/getenv "ANTHROPIC_API_KEY")]
  (defmethod openrouter->provider "anthropic"
    [_provider model messages]
    (anthropic/openrouter->anthropic model messages api-key))

  (defmethod provider->openrouter "anthropic"
    [_provider response]
    (anthropic/anthropic->openrouter response)))

;;------------------------------------------------------------------------------
;; Google (Gemini) - only if GOOGLE_API_KEY is set
;;------------------------------------------------------------------------------

(when-let [api-key (System/getenv "GOOGLE_API_KEY")]
  (defmethod openrouter->provider "google"
    [_provider model messages]
    (google/openrouter->google model messages api-key))

  (defmethod provider->openrouter "google"
    [_provider response]
    (google/google->openrouter response)))

;;------------------------------------------------------------------------------
;; OpenAI - only if OPENAI_API_KEY is set
;;------------------------------------------------------------------------------

(when-let [api-key (System/getenv "OPENAI_API_KEY")]
  (defmethod openrouter->provider "openai"
    [_provider model messages]
    (openai/openrouter->openai model messages api-key))

  (defmethod provider->openrouter "openai"
    [_provider response]
    (openai/openai->openrouter response)))

;;------------------------------------------------------------------------------
;; xAI (Grok) - only if XAI_API_KEY is set
;;------------------------------------------------------------------------------

(when-let [api-key (System/getenv "XAI_API_KEY")]
  (defmethod openrouter->provider "x-ai"
    [_provider model messages]
    (xai/openrouter->xai model messages api-key))

  (defmethod provider->openrouter "x-ai"
    [_provider response]
    (xai/xai->openrouter response)))

;;------------------------------------------------------------------------------
;; Async HTTP Client
;;------------------------------------------------------------------------------

(defn strip-md-json
  "Strip markdown code fences from LLM response.
   LLMs often wrap JSON/EDN in ```json ... ``` blocks."
  [s]
  (-> s
      (str/replace #"^```(?:json|edn|clojure)?\s*" "")
      (str/replace #"\s*```$" "")))

(defn call
  "Call LLM API asynchronously via provider-specific transforms.
   
   Uses multimethod dispatch to select the appropriate provider.
   If a direct API key is set, uses the provider's native API.
   Otherwise falls back to OpenRouter.
   
   Automatically retries on EDN parse errors with feedback to the LLM.
  
   Args:
     provider - Provider name (e.g., 'openai', 'anthropic')
     model - Model name (e.g., 'gpt-4o', 'claude-sonnet-4')
     prompts - Vector of message maps with :role and :content
     handler - Function to call with successful parsed EDN response
     schema - (Optional) Malli schema for response validation
     error - (Optional) Function to call on error
     retry-count - (Internal) Current retry attempt number
     max-retries - Maximum number of retries for malformed EDN (default: 3)"
  [provider model prompts handler & [{:keys [schema error retry-count max-retries]
                                      :or {retry-count 0 max-retries 3}}]]
  (log/info (str "      LLM Call: " provider "/" model
                 (when (> retry-count 0) (str " (retry " retry-count "/" max-retries ")"))))

  ;; Use transforms to build provider-specific request
  (let [{:keys [url headers body]} (openrouter->provider provider model prompts)]
    (post
     url
     {:async? true
      :headers headers
      :body (clj->json body)}
     (fn [r]
       (try
         ;; Use transform to extract content from provider response
         (let [parsed-response (json->clj (:body r))
               raw-content (provider->openrouter provider parsed-response)
               d (strip-md-json raw-content)]
           (try
             (let [j (edn/read-string (str/trim d))]
               (log/info "      [OK] LLM Response: Valid EDN received")
               (handler j))
             (catch Exception e
               (let [retrier (make-retrier max-retries)]
                 (retrier
                  retry-count
                  ;; Retry operation: send error feedback and try again
                  (fn []
                    (let [error-msg (str "We could not unmarshal your EDN - it must be badly formed.\n\n"
                                         "Here is the exception:\n"
                                         (.getMessage e) "\n\n"
                                         "Here is your malformed response:\n" d "\n\n"
                                         "Please try again. Your response should only contain the relevant EDN document.")
                          retry-prompts (conj (vec prompts) {"role" "user" "content" error-msg})]
                      (log/warn (str "      [X] EDN Parse Error: " (.getMessage e)))
                      (log/info (str "      [>>] Sending error feedback to LLM"))
                      (call provider model retry-prompts handler
                            {:error error
                             :retry-count (inc retry-count)
                             :max-retries max-retries})))
                  ;; Max retries handler
                  (fn []
                    (log/debug (str "Final malformed response: " d))
                    (when error (error {:error "max-retries-exceeded"
                                        :raw-response d
                                        :exception (.getMessage e)}))))))))
         (catch Throwable t
           (log/error t "Error processing LLM response"))))
     (fn [exception]
       (try
         (let [m (json/parse-string (:body (.getData exception)) true)]
           (log/error (str "      [X] LLM Request Failed: " (get m "error")))
           (when error (error m)))
         (catch Throwable t
           (log/error t "Error handling LLM failure")))))))
