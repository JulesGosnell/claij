(ns claij.new.backend.openrouter
  "OpenRouter backend for calling various LLMs.
  
  Supports: GPT-4, Claude, Grok, and other OpenRouter models.
  Requires OPENROUTER_API_KEY environment variable."
  (:require [clj-http.client :refer [post]]
            [clojure.data.json :as json]))

(def openrouter-api-url "https://openrouter.ai/api/v1/chat/completions")

(defn get-api-key
  "Get OpenRouter API key from environment or system properties."
  []
  (or (System/getProperty "OPENROUTER_API_KEY")
      (System/getenv "OPENROUTER_API_KEY")
      (throw (ex-info "OPENROUTER_API_KEY not found in environment or system properties. Did you load .env?" {}))))

(defn make-llm-fn
  "Create an LLM function for a specific model.
  
  Parameters:
  - model: Model identifier (e.g., 'openai/gpt-4', 'anthropic/claude-3.5-sonnet')
  - opts: Optional map with:
    - :temperature (default 0.7)
    - :max-tokens (default 1000)
  
  Returns a function that takes prompts map and returns JSON string."
  ([model] (make-llm-fn model {}))
  ([model opts]
   (let [api-key (get-api-key)
         temperature (:temperature opts 0.7)
         max-tokens (:max-tokens opts 1000)]

     (fn [prompts]
       (let [messages [{:role "system" :content (:system prompts)}
                       {:role "user" :content (:user prompts)}]

             response (post openrouter-api-url
                            {:headers {"Authorization" (str "Bearer " api-key)
                                       "Content-Type" "application/json"}
                             :body (json/write-str
                                    {:model model
                                     :messages messages
                                     :temperature temperature
                                     :max_tokens max-tokens})
                             :as :json})

             content (-> response :body :choices first :message :content)]

         content)))))

;; Convenience functions for common models

(defn gpt-4
  "Create LLM function for GPT-4."
  ([] (gpt-4 {}))
  ([opts] (make-llm-fn "openai/gpt-4" opts)))

(defn gpt-4-turbo
  "Create LLM function for GPT-4 Turbo."
  ([] (gpt-4-turbo {}))
  ([opts] (make-llm-fn "openai/gpt-4-turbo" opts)))

(defn claude-35-sonnet
  "Create LLM function for Claude 3.5 Sonnet."
  ([] (claude-35-sonnet {}))
  ([opts] (make-llm-fn "anthropic/claude-3.5-sonnet" opts)))

(defn grok-beta
  "Create LLM function for Grok Beta."
  ([] (grok-beta {}))
  ([opts] (make-llm-fn "x-ai/grok-beta" opts)))

;; Model Registry
;; Definitive mapping of short names to full model identifiers and friendly names

(def model-registry
  "Registry of supported LLM models with their OpenRouter identifiers.
  
  Each entry maps a short name to a map containing:
  - :model - The full OpenRouter model identifier
  - :display-name - Human-friendly name for output
  - :constructor - Function to create LLM instance
  
  Models are sourced from claij.agent.open-router definitions.
  Using currently available models as of 2025-10."
  {:grok {:model "x-ai/grok-code-fast-1"
          :display-name "Grok Code Fast 1"
          :constructor (fn [opts] (make-llm-fn "x-ai/grok-code-fast-1" opts))}

   :gpt {:model "openai/gpt-5-codex"
         :display-name "GPT-5 Codex"
         :constructor (fn [opts] (make-llm-fn "openai/gpt-5-codex" opts))}

   :claude {:model "anthropic/claude-sonnet-4.5"
            :display-name "Claude Sonnet 4.5"
            :constructor (fn [opts] (make-llm-fn "anthropic/claude-sonnet-4.5" opts))}

   :gemini {:model "google/gemini-2.5-flash"
            :display-name "Gemini 2.5 Flash"
            :constructor (fn [opts] (make-llm-fn "google/gemini-2.5-flash" opts))}})

(comment
  ;; Example usage
  (def gpt4-fn (gpt-4 {:temperature 0.5}))

  (gpt4-fn {:system "You are helpful."
            :user "Say hello"})
  ;=> "{\"answer\": \"Hello!\", \"state\": \"ready\"}"
  )
