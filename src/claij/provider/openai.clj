(ns claij.provider.openai
  "OpenAI provider transforms.
   
   OpenAI uses the same format that OpenRouter adopted, so transforms are straightforward.
   Response format: {:choices [{:message {:content \"...\"}}]}")

;;------------------------------------------------------------------------------
;; Model translation
;;------------------------------------------------------------------------------

(def model-translations
  "Map from OpenRouter model names to OpenAI API model names."
  {;; Add translations here as needed
   })

(defn translate-model
  "Translate OpenRouter model name to OpenAI API model name."
  [model]
  (get model-translations model model))

;;------------------------------------------------------------------------------
;; Request transform
;;------------------------------------------------------------------------------

(defn openrouter->openai
  "Transform OpenRouter-format request to OpenAI API request.
   
   OpenAI uses the same format OpenRouter adopted, so this is mostly pass-through.
   
   Args:
     model    - model string (e.g., \"gpt-4o\")
     messages - [{\"role\" \"user\" \"content\" \"...\"}]
     api-key  - OpenAI API key
   
   Returns:
     {:url     string
      :headers map
      :body    map}"
  [model messages api-key]
  {:url "https://api.openai.com/v1/chat/completions"
   :headers {"Authorization" (str "Bearer " api-key)
             "content-type" "application/json"}
   :body {:model (translate-model model)
          :messages messages}})

;;------------------------------------------------------------------------------
;; Response transform
;;------------------------------------------------------------------------------

(defn openai->openrouter
  "Transform OpenAI API response to content string.
   
   OpenAI response: {:choices [{:message {:content \"...\"}}]}
   Same as OpenRouter format.
   
   Args:
     response - parsed JSON response from OpenAI
   
   Returns:
     Content string from the response"
  [response]
  (get-in response [:choices 0 :message :content]))
