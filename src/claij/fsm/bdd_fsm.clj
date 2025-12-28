(ns claij.fsm.bdd-fsm
  "Bath Driven Development FSM - voice-controlled development workflow.
   
   Pipeline: STT → LLM (with MCP hat) → TTS
   
   Input: audio bytes (WAV)
   Output: audio bytes (WAV)
   
   The LLM state has MCP hat enabled, giving it access to:
   - GitHub tools (issues, PRs, code)
   - Clojure tools (REPL, file operations)"
  (:require
   [claij.schema :refer [def-fsm]]
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
      "RESPONSE FORMAT:"
      "You MUST respond with a JSON object containing 'id' and 'text' fields:"
      "- 'id': [\"llm\", \"tts\"] (the transition to TTS)"
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
     "schema" {"$ref" "#/$defs/entry"}}

    ;; STT → LLM: openapi-call response with text in body
    {"id" ["stt" "llm"]
     "label" "transcribed"
     "schema" {"$ref" "#/$defs/stt-to-llm"}}

    ;; LLM → TTS: LLM response text for synthesis
    {"id" ["llm" "tts"]
     "label" "response"
     "schema" {"$ref" "#/$defs/llm-to-tts"}}

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
   
   Options:
   - :service - LLM service (default: ollama:local)
   - :model - LLM model (default: granite4:3b)
   - :stt-url - STT service URL (default: prognathodon:8000)
   - :tts-url - TTS service URL (default: prognathodon:8001)"
  [{:keys [service model stt-url tts-url]
    :or {service "ollama:local"
         model "granite4:3b"
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
