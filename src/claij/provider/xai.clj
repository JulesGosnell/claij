(ns claij.provider.xai
  "xAI (Grok) provider transforms.
   
   xAI uses the same format that OpenRouter adopted, so transforms are straightforward.
   Response format: {:choices [{:message {:content \"...\"}}]}")

;;------------------------------------------------------------------------------
;; Model translation
;;------------------------------------------------------------------------------

(def model-translations
  "Map from OpenRouter model names to xAI API model names."
  {;; Add translations here as needed
   })

(defn translate-model
  "Translate OpenRouter model name to xAI API model name."
  [model]
  (get model-translations model model))

;;------------------------------------------------------------------------------
;; Request transform
;;------------------------------------------------------------------------------

(defn openrouter->xai
  "Transform OpenRouter-format request to xAI API request.
   
   xAI uses the same format OpenRouter adopted, so this is mostly pass-through.
   
   Args:
     model    - model string (e.g., \"grok-3-beta\")
     messages - [{\"role\" \"user\" \"content\" \"...\"}]
     api-key  - xAI API key
   
   Returns:
     {:url     string
      :headers map
      :body    map}"
  [model messages api-key]
  {:url "https://api.x.ai/v1/chat/completions"
   :headers {"Authorization" (str "Bearer " api-key)
             "content-type" "application/json"}
   :body {:model (translate-model model)
          :messages messages}})

;;------------------------------------------------------------------------------
;; Response transform
;;------------------------------------------------------------------------------

(defn xai->openrouter
  "Transform xAI API response to content string.
   
   xAI response: {:choices [{:message {:content \"...\"}}]}
   Same as OpenRouter format.
   
   Args:
     response - parsed JSON response from xAI
   
   Returns:
     Content string from the response"
  [response]
  (get-in response [:choices 0 :message :content]))
