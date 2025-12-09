(ns claij.llm
  "Unified LLM client with multimethod dispatch.
   
   Uses direct provider APIs when available (cheaper - no OpenRouter commission),
   falls back to OpenRouter for unknown providers or when direct API key not set.
   
   Request transforms:  openrouter->provider - convert canonical format to provider-native
   Response transforms: provider->openrouter - convert provider response to canonical format"
  (:require
   [claij.provider.openrouter :as openrouter]
   [claij.provider.anthropic :as anthropic]))

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
   
   Dispatches on provider string. Falls back to OpenRouter for unknown providers."
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
;; Anthropic (Claude)
;;------------------------------------------------------------------------------

(defn anthropic-api-key
  "Get Anthropic API key from environment. Public for test mocking."
  []
  (or (System/getenv "ANTHROPIC_API_KEY")
      (throw (ex-info "ANTHROPIC_API_KEY not set" {}))))

(defmethod openrouter->provider "anthropic"
  [_provider model messages]
  (anthropic/openrouter->anthropic model messages (anthropic-api-key)))

(defmethod provider->openrouter "anthropic"
  [_provider response]
  (anthropic/anthropic->openrouter response))
