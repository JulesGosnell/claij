(ns claij.provider.anthropic
  "Anthropic (Claude) provider transforms.
   
   Key differences from OpenRouter format:
   - System message is a separate top-level field, not in messages array
   - Response format: {:content [{:text \"...\"}]} vs {:choices [{:message {:content \"...\"}}]}
   - Requires max_tokens in request
   - Uses x-api-key header instead of Authorization Bearer")

;;------------------------------------------------------------------------------
;; Model translation (OpenRouter names -> Anthropic API names)
;;------------------------------------------------------------------------------

(def model-translations
  "Map from OpenRouter model names to Anthropic API model names.
   
   OpenRouter uses short names, Anthropic API may need versioned names.
   If a model isn't in this map, it's passed through unchanged."
  {;; Add translations here as needed, e.g.:
   ;; "claude-sonnet-4" "claude-sonnet-4-20250514"
   })

(defn translate-model
  "Translate OpenRouter model name to Anthropic API model name."
  [model]
  (get model-translations model model))

;;------------------------------------------------------------------------------
;; Request transform
;;------------------------------------------------------------------------------

(defn openrouter->anthropic
  "Transform OpenRouter-format request to Anthropic API request.
   
   Anthropic differences:
   - System message extracted to top-level :system field
   - Requires :max_tokens
   - Different auth header
   
   Args:
     model    - model string (e.g., \"claude-sonnet-4\")
     messages - [{\"role\" \"user\" \"content\" \"...\"}]
     api-key  - Anthropic API key
   
   Returns:
     {:url     string
      :headers map
      :body    map}"
  [model messages api-key]
  (let [;; Extract system messages and combine them
        system-msgs (filter #(= "system" (get % "role")) messages)
        system-text (when (seq system-msgs)
                      (clojure.string/join "\n\n" (map #(get % "content") system-msgs)))
        ;; Non-system messages for the messages array
        non-system (vec (remove #(= "system" (get % "role")) messages))]
    {:url "https://api.anthropic.com/v1/messages"
     :headers {"x-api-key" api-key
               "anthropic-version" "2023-06-01"
               "content-type" "application/json"}
     :body (cond-> {:model (translate-model model)
                    :max_tokens 4096
                    :messages non-system}
             system-text (assoc :system system-text))}))

;;------------------------------------------------------------------------------
;; Response transform
;;------------------------------------------------------------------------------

(defn anthropic->openrouter
  "Transform Anthropic API response to content string.
   
   Anthropic response: {:content [{:type \"text\" :text \"...\"}]}
   
   Args:
     response - parsed JSON response from Anthropic
   
   Returns:
     Content string from the response"
  [response]
  (get-in response [:content 0 :text]))
