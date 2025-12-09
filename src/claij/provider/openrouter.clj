(ns claij.provider.openrouter
  "OpenRouter provider transforms.
   
   This is the 'identity' case - OpenRouter format is our canonical format.
   These transforms exist for API consistency with other providers.")

;;------------------------------------------------------------------------------
;; Model translation (OpenRouter model names -> OpenRouter model names)
;;------------------------------------------------------------------------------

(def model-translations
  "OpenRouter model names are already in the correct format.
   This map exists for consistency with other providers."
  {})

(defn translate-model
  "Translate model name for OpenRouter. Identity function."
  [model]
  (get model-translations model model))

;;------------------------------------------------------------------------------
;; Request transform
;;------------------------------------------------------------------------------

(defn openrouter->openrouter
  "Transform OpenRouter-format request to OpenRouter API request.
   
   This is essentially identity - we're already in the right format.
   
   Args:
     provider - provider string (e.g., \"anthropic\")
     model    - model string (e.g., \"claude-sonnet-4\")
     messages - [{\"role\" \"user\" \"content\" \"...\"}]
     api-key  - OpenRouter API key
   
   Returns:
     {:url     string
      :headers map
      :body    map (to be JSON encoded)}"
  [provider model messages api-key]
  {:url "https://openrouter.ai/api/v1/chat/completions"
   :headers {"Authorization" (str "Bearer " api-key)
             "content-type" "application/json"}
   :body {:model (str provider "/" (translate-model model))
          :messages messages}})

;;------------------------------------------------------------------------------
;; Response transform
;;------------------------------------------------------------------------------

(defn openrouter->openrouter-response
  "Transform OpenRouter API response to canonical format.
   
   Identity function - response is already in canonical format.
   
   Args:
     response - parsed JSON response from OpenRouter
   
   Returns:
     Content string from the response"
  [response]
  (get-in response [:choices 0 :message :content]))
