(ns claij.llm
  "Unified LLM client with multimethod dispatch.
   
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
