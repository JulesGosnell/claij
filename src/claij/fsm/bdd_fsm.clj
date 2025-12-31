(ns claij.fsm.bdd-fsm
  "Bath Driven Development FSM - voice-controlled development workflow.
   
   Pipeline: STT → LLM (with MCP hat) → TTS
   
   Input: audio bytes (WAV)
   Output: audio bytes (WAV)
   
   The LLM state has MCP hat enabled, giving it access to:
   - GitHub tools (issues, PRs, code)
   - Clojure tools (REPL, file operations)"
  (:require
   [clojure.tools.logging :as log]
   [claij.schema :refer [def-fsm]]
   [claij.fsm :as fsm]
   [claij.actions :as actions]
   [claij.action.openapi-call :refer [openapi-call-actions]]
   [claij.mcp.bridge :as bridge]
   [claij.hat :as hat]
   [claij.hat.mcp :as mcp-hat]
   [claij.model :as model]))

;;; ============================================================
;;; Configuration
;;; ============================================================

(def default-stt-url "http://prognathodon:8000")
(def default-tts-url "http://prognathodon:8001")

;;; ============================================================
;;; MCP Server Configurations
;;; ============================================================

(def github-mcp-config
  "MCP server config for GitHub API access."
  {"command" "npx"
   "args" ["-y" "@modelcontextprotocol/server-github"]
   "transport" "stdio"
   "env" {"GITHUB_PERSONAL_ACCESS_TOKEN" (System/getenv "GITHUB_PERSONAL_ACCESS_TOKEN")}})

(def clojure-tools-config
  "MCP server config for Clojure tools (REPL, file operations)."
  bridge/default-mcp-config)

;;; ============================================================
;;; Schemas
;;; ============================================================

(def bdd-schemas
  "Schema definitions for BDD voice pipeline events.
   
   Note: openapi-call returns {\"status\" N, \"body\" {...}, \"content-type\" \"...\"}
   The schemas here reflect that structure."
  {;; Entry event: start → stt (audio in)
   "entry" {"type" "object"
            "description" "Voice input to transcribe"
            "additionalProperties" false
            "required" ["id" "audio"]
            "properties"
            {"id" {"const" ["start" "stt"]}
             "audio" {"description" "WAV audio bytes"}}}

   ;; STT → LLM: openapi-call response with transcription in body
   "stt-to-llm" {"type" "object"
                 "description" "STT service response"
                 "additionalProperties" false
                 "required" ["id" "status" "body"]
                 "properties"
                 {"id" {"const" ["stt" "llm"]}
                  "status" {"type" "integer"}
                  "body" {"type" "object"
                          "required" ["text"]
                          "properties"
                          {"text" {"type" "string"
                                   "description" "Transcribed text"}
                           "language" {"type" "string"}}}
                  "content-type" {"type" "string"}}}

   ;; LLM → TTS: LLM produces text for synthesis
   "llm-to-tts" {"type" "object"
                 "description" "LLM response for TTS synthesis"
                 "additionalProperties" false
                 "required" ["id" "text"]
                 "properties"
                 {"id" {"const" ["llm" "tts"]}
                  "text" {"type" "string"
                          "description" "Response text to synthesize"}}}

   ;; TTS → end: openapi-call response with audio bytes in body
   "exit" {"type" "object"
           "description" "TTS service response - synthesized audio"
           "additionalProperties" false
           "required" ["id" "status" "body"]
           "properties"
           {"id" {"const" ["tts" "end"]}
            "status" {"type" "integer"}
            "body" {"description" "WAV audio bytes"}
            "content-type" {"type" "string"}}}})

;;; ============================================================
;;; FSM Definition
;;; ============================================================

(def-fsm
  bdd-fsm
  {"id" "bdd"
   "schemas" bdd-schemas
   "prompts" ["You are a voice assistant for Bath Driven Development"]

   "states"
   [;; STT: audio → text
    {"id" "stt"
     "description" "Speech to Text"
     "action" "openapi-call"
     "config" {:spec-url (str default-stt-url "/openapi.json")
               :base-url default-stt-url
               :operation "transcribe"}}

    ;; LLM: with MCP hat for GitHub and Clojure tools
    {"id" "llm"
     "description" "Large Language Model"
     "action" "llm"
     "hats" [{"mcp" {:servers {"github" {:config github-mcp-config}
                               "clojure" {:config clojure-tools-config}}}}]
     "prompts"
     ["You are a voice assistant for software development."
      "The user is speaking to you - their speech has been transcribed."
      "Look for the user's message in the 'body.text' field of your input."
      ""
      "RESPONSE STYLE:"
      "- Speak naturally, as in a conversation"
      "- Avoid code blocks, bullet points, markdown formatting"
      "- Be concise but informative (under 3 sentences when possible)"
      "- When you perform actions, summarize what you did"
      ""
      "CONTEXT:"
      "The user is working on CLAIJ, a Clojure AI orchestration system."]}

    ;; TTS: text → audio
    {"id" "tts"
     "description" "Text to Speech"
     "action" "openapi-call"
     "config" {:spec-url (str default-tts-url "/openapi.json")
               :base-url default-tts-url
               :operation "synthesize"}}

    {"id" "end"
     "action" "end"}]

   "xitions"
   [;; Entry: audio bytes in (omit from trail - binary data is huge)
    {"id" ["start" "stt"]
     "label" "audio in"
     "omit" true
     "schema" {"$ref" "#/$defs/entry"}}

    ;; STT → LLM: openapi-call response with text in body
    {"id" ["stt" "llm"]
     "label" "transcribed"
     "schema" {"$ref" "#/$defs/stt-to-llm"}}

    ;; LLM → TTS: LLM response text for synthesis
    {"id" ["llm" "tts"]
     "label" "response"
     "schema" {"$ref" "#/$defs/llm-to-tts"}}

    ;; LLM → end: bail-out for errors (allows graceful failure)
    {"id" ["llm" "end"]
     "label" "error"
     "schema" true}

    ;; TTS → end: audio bytes out
    {"id" ["tts" "end"]
     "label" "audio out"
     "schema" {"$ref" "#/$defs/exit"}}]})

;;; ============================================================
;;; Context Setup
;;; ============================================================

(def bdd-actions
  "Actions required for BDD FSM."
  (merge actions/default-actions
         openapi-call-actions))

(defn make-bdd-context
  "Create context for running BDD FSM.
   
   Options (can also be set via environment variables):
   - :service - LLM service (env: BDD_LLM_SERVICE, default: openrouter)
   - :model - LLM model (env: BDD_LLM_MODEL, default: xai via openrouter)
   - :stt-url - STT service URL (default: prognathodon:8000)
   - :tts-url - TTS service URL (default: prognathodon:8001)
   
   Example configurations:
   - Anthropic native: BDD_LLM_SERVICE=anthropic BDD_LLM_MODEL=claude-sonnet-4-5
   - OpenRouter Claude: BDD_LLM_SERVICE=openrouter BDD_LLM_MODEL=anthropic/claude-sonnet-4.5
   - Google native: BDD_LLM_SERVICE=google BDD_LLM_MODEL=gemini-3-flash-preview
   - OpenRouter GPT: BDD_LLM_SERVICE=openrouter BDD_LLM_MODEL=openai/gpt-5.2
   - xAI native: BDD_LLM_SERVICE=xai BDD_LLM_MODEL=grok-code-fast-1
   - OpenRouter Grok: BDD_LLM_SERVICE=openrouter BDD_LLM_MODEL=x-ai/grok-code-fast-1"
  [{:keys [service model stt-url tts-url]
    :or {stt-url default-stt-url
         tts-url default-tts-url}}]
  (let [;; Environment variables override options, options override defaults
        env-service (System/getenv "BDD_LLM_SERVICE")
        env-model (System/getenv "BDD_LLM_MODEL")
        final-service (or service env-service "openrouter")
        final-model (or model env-model (model/openrouter-model :xai))]
    (log/info "BDD context: LLM provider" (str final-service "/" final-model))
    {:id->action bdd-actions
     :llm/service final-service
     :llm/model final-model
     ;; Register MCP hat maker
     :hats {:registry (-> (hat/make-hat-registry)
                          (hat/register-hat "mcp" mcp-hat/mcp-hat-maker))}
     ;; These could be used to override FSM config at runtime
     :stt-url stt-url
     :tts-url tts-url}))

;;; ============================================================
;;; Convenience Functions
;;; ============================================================

(defn start-bdd
  "Start a BDD session.
   Returns {:submit fn, :await fn, :stop fn}."
  ([]
   (start-bdd {}))
  ([opts]
   (let [context (make-bdd-context opts)]
     (fsm/start-fsm context bdd-fsm))))

(defn run-bdd
  "Run a single BDD interaction synchronously.
   
   audio-bytes - WAV audio input
   opts - Same as make-bdd-context
   
   Returns [context trail] or :timeout"
  ([audio-bytes]
   (run-bdd audio-bytes {}))
  ([audio-bytes opts]
   (let [context (make-bdd-context opts)
         input {"id" ["start" "stt"]
                "audio" audio-bytes}]
     (fsm/run-sync bdd-fsm context input 120000)))) ;; 2 min timeout
