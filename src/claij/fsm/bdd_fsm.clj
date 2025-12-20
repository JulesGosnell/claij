(ns claij.fsm.bdd-fsm
  "Bath Driven Development FSM - voice-controlled development workflow.
   
   Pipeline: STT → LLM (with MCP hat) → TTS
   
   Input: audio bytes (WAV)
   Output: audio bytes (WAV)
   
   The LLM state has MCP hat enabled, giving it access to:
   - GitHub tools (issues, PRs, code)
   - Clojure tools (REPL, file operations)"
  (:require
   [malli.registry :as mr]
   [claij.malli :refer [def-fsm base-registry]]
   [claij.fsm :as fsm]
   [claij.actions :as actions]
   [claij.action.openapi-call :refer [openapi-call-actions]]
   [claij.mcp.bridge :as bridge]
   [claij.hat :as hat]
   [claij.hat.mcp :as mcp-hat]))

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
   "entry" [:map {:closed true
                  :description "Voice input to transcribe"}
            ["id" [:= ["start" "stt"]]]
            ["audio" {:description "WAV audio bytes"} :any]]

   ;; STT → MC: openapi-call response with transcription in body
   "stt-to-mc" [:map {:closed true
                      :description "STT service response"}
                ["id" [:= ["stt" "mc"]]]
                ["status" :int]
                ["body" [:map
                         ["text" {:description "Transcribed text"} :string]
                         ["language" {:optional true} :string]]]
                ["content-type" {:optional true} :string]]

   ;; MC → TTS: LLM produces text for synthesis
   "mc-to-tts" [:map {:closed true
                      :description "LLM response for TTS synthesis"}
                ["id" [:= ["mc" "tts"]]]
                ["text" {:description "Response text to synthesize"} :string]]

   ;; TTS → end: openapi-call response with audio bytes in body
   "exit" [:map {:closed true
                 :description "TTS service response - synthesized audio"}
           ["id" [:= ["tts" "end"]]]
           ["status" :int]
           ["body" {:description "WAV audio bytes"} :any]
           ["content-type" {:optional true} :string]]})

(def bdd-registry
  "Malli registry for BDD FSM validation."
  (mr/composite-registry
   base-registry
   bdd-schemas))

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

    ;; MC: LLM with MCP hat for GitHub and Clojure tools
    {"id" "mc"
     "description" "Master of Ceremonies"
     "action" "llm"
     "hats" [{"mcp" {:servers {"github" {:config github-mcp-config}
                               "clojure" {:config clojure-tools-config}}}}]
     "prompts"
     ["You are a voice assistant for software development."
      "The user is speaking to you - their speech has been transcribed."
      "Look for the user's message in the 'body.text' field of your input."
      ""
      "RESPONSE FORMAT:"
      "You MUST respond with a JSON object containing 'id' and 'text' fields:"
      "- 'id': [\"mc\", \"tts\"] (the transition to TTS)"
      "- 'text': Your spoken response (will be synthesized to audio)"
      ""
      "MCP TOOLS AVAILABLE:"
      "You have access to multiple MCP servers with tools:"
      ""
      "From 'github' server:"
      "- list_issues, create_issue, get_issue - manage GitHub issues"
      "- create_pull_request, get_pull_request - manage PRs"
      "- search_code, get_file_contents - read repository code"
      ""
      "From 'clojure' server:"
      "- clojure_eval - evaluate Clojure code in the REPL"
      "- read_file, glob_files, grep - file operations"
      "- bash - run shell commands"
      ""
      "When calling tools, specify the server in your tool call."
      ""
      "RESPONSE STYLE:"
      "- Speak naturally, as in a conversation"
      "- Avoid code blocks, bullet points, markdown formatting"
      "- Be concise but informative (under 3 sentences when possible)"
      "- When you perform actions, summarize what you did"
      ""
      "CONTEXT:"
      "The user is working on CLAIJ, a Clojure AI orchestration system."
      "They may ask you to create issues, review code, run tests, or explain concepts."]}

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
   [;; Entry: audio bytes in
    {"id" ["start" "stt"]
     "label" "audio in"
     "schema" [:ref "entry"]}

    ;; STT → MC: openapi-call response with text in body
    {"id" ["stt" "mc"]
     "label" "transcribed"
     "schema" [:ref "stt-to-mc"]}

    ;; MC → TTS: LLM response text for synthesis
    {"id" ["mc" "tts"]
     "label" "response"
     "schema" [:ref "mc-to-tts"]}

    ;; TTS → end: audio bytes out
    {"id" ["tts" "end"]
     "label" "audio out"
     "schema" [:ref "exit"]}]})

;;; ============================================================
;;; Context Setup
;;; ============================================================

(def bdd-actions
  "Actions required for BDD FSM."
  (merge actions/default-actions
         openapi-call-actions))

(defn make-bdd-context
  "Create context for running BDD FSM.
   
   Options:
   - :service - LLM service (default: ollama:local)
   - :model - LLM model (default: deepseek-coder-v2:16b)
   - :stt-url - STT service URL (default: prognathodon:8000)
   - :tts-url - TTS service URL (default: prognathodon:8001)"
  [{:keys [service model stt-url tts-url]
    :or {service "ollama:local"
         model "deepseek-coder-v2:16b"
         stt-url default-stt-url
         tts-url default-tts-url}}]
  {:id->action bdd-actions
   :llm/service service
   :llm/model model
   ;; Register MCP hat maker
   :hats {:registry (-> (hat/make-hat-registry)
                        (hat/register-hat "mcp" mcp-hat/mcp-hat-maker))}
   ;; These could be used to override FSM config at runtime
   :stt-url stt-url
   :tts-url tts-url})

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
