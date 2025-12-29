(ns claij.model
  "Centralized model definitions for all LLM providers.
   
   Use these definitions instead of hardcoding model strings throughout the codebase.
   This allows easy version updates without hunting through all files.")

;; Model registry - maps provider to model variants
;; :default is the standard model for general use
;; :pro/:heavy is for complex reasoning tasks (slower, more expensive)
;; :light/:fast is for simple tasks (faster, cheaper)

(def models
  {:openai {:openrouter "openai/gpt-5.2"
            :direct "gpt-5.2"
            ;; gpt-5.2-pro available for heavy reasoning tasks (slower)
            :pro "openai/gpt-5.2-pro"
            ;; gpt-5.2-chat for fast chat interactions
            :chat "gpt-5.2-chat"}

   ;; Anthropic - claude-sonnet-4.5 (latest)
   ;; Note: Both 4.5 and 4 return empty content [] on multi-turn FSM conversations
   ;; (code-review-fsm reviewer state). Needs investigation. Using 4.5 for now.
   :anthropic {:openrouter "anthropic/claude-sonnet-4.5"
               :direct "claude-sonnet-4-5"
               :light "anthropic/claude-haiku-4.5"}

   ;; Google - gemini-3-flash-preview has 1M token context window
   :google {:openrouter "google/gemini-3-flash-preview"
            :direct "gemini-3-flash-preview"
            :light "google/gemini-2.0-flash-001"}

   ;; xAI - grok-code-fast-1 has largest context (486B tokens)
   :xai {:openrouter "x-ai/grok-code-fast-1"
         :direct "grok-code-fast-1"
         :chat "grok-code-fast-1"
         ;; grok-4.1-fast available as alternative
         :fast "x-ai/grok-4.1-fast"}

   :meta {:openrouter "meta-llama/llama-3.3-70b-instruct"}

   ;; Ollama - tested with native tool calling (issue #124)
   ;; 10/10: qwen3-vl:latest, qwen3:8b, functiongemma:latest, granite4:3b
   ;; 7/10: mistral:7b
   ;; 0/10: qwen2.5-coder:14b, olmo-3:latest, deepseek-r1:8b, ministral-3:8b, gemma3:4b
   :ollama {:default "qwen3-vl:latest"
            :light "qwen3:8b"
            :functiongemma "functiongemma:latest"
            :granite "granite4:3b"}})

;; Convenience accessors

(defn openrouter-model
  "Get the OpenRouter model string for a provider."
  [provider]
  (get-in models [provider :openrouter]))

(defn direct-model
  "Get the direct API model string for a provider."
  [provider]
  (get-in models [provider :direct]))

(defn chat-model
  "Get the chat/fast model string for a provider."
  [provider]
  (get-in models [provider :chat]))

(defn ollama-model
  "Get the default Ollama model."
  ([] (get-in models [:ollama :default]))
  ([variant] (get-in models [:ollama variant])))

;; Default models for common use cases

(def default-openrouter-model (openrouter-model :openai))
(def default-ollama-model (ollama-model))
