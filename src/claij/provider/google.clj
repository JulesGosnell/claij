(ns claij.provider.google
  "Google (Gemini) provider transforms.
   
   Key differences from OpenRouter format:
   - Model name is in the URL path, not the body
   - Message structure: {:contents [{:role :parts [{:text}]}]} vs {:messages [{:role :content}]}
   - Role names: 'model' instead of 'assistant'
   - Response format: {:candidates [{:content {:parts [{:text}]}}]}")

;;------------------------------------------------------------------------------
;; Model translation
;;------------------------------------------------------------------------------

(def model-translations
  "Map from OpenRouter model names to Google API model names."
  {;; Add translations here as needed
   })

(defn translate-model
  "Translate OpenRouter model name to Google API model name."
  [model]
  (get model-translations model model))

;;------------------------------------------------------------------------------
;; Request transform
;;------------------------------------------------------------------------------

(defn- translate-role
  "Translate OpenRouter role to Google role.
   OpenRouter/OpenAI uses 'assistant', Google uses 'model'."
  [role]
  (case role
    "assistant" "model"
    role))

(defn- message->content
  "Convert OpenRouter message to Google content format."
  [{:strs [role content]}]
  {:role (translate-role role)
   :parts [{:text content}]})

(defn openrouter->google
  "Transform OpenRouter-format request to Google AI API request.
   
   Google differences:
   - Model in URL path: /v1beta/models/{model}:generateContent
   - Different message structure with :contents and :parts
   - System messages become systemInstruction
   
   Args:
     model    - model string (e.g., \"gemini-2.0-flash\")
     messages - [{\"role\" \"user\" \"content\" \"...\"}]
     api-key  - Google AI API key
   
   Returns:
     {:url     string
      :headers map
      :body    map}"
  [model messages api-key]
  (let [translated-model (translate-model model)
        ;; Extract system messages
        system-msgs (filter #(= "system" (get % "role")) messages)
        system-text (when (seq system-msgs)
                      (clojure.string/join "\n\n" (map #(get % "content") system-msgs)))
        ;; Non-system messages for contents array
        non-system (remove #(= "system" (get % "role")) messages)
        contents (mapv message->content non-system)]
    {:url (str "https://generativelanguage.googleapis.com/v1beta/models/"
               translated-model
               ":generateContent")
     :headers {"x-goog-api-key" api-key
               "content-type" "application/json"}
     :body (cond-> {:contents contents}
             system-text (assoc :systemInstruction {:parts [{:text system-text}]}))}))

;;------------------------------------------------------------------------------
;; Response transform
;;------------------------------------------------------------------------------

(defn google->openrouter
  "Transform Google AI API response to content string.
   
   Google response: {:candidates [{:content {:parts [{:text \"...\"}]}}]}
   
   Args:
     response - parsed JSON response from Google
   
   Returns:
     Content string from the response"
  [response]
  (get-in response [:candidates 0 :content :parts 0 :text]))
