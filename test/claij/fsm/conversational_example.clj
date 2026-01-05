(ns claij.fsm.conversational-example
  "Example: Conversational FSM with human-in-the-loop.
   
   Demonstrates LLM → user → LLM loop where:
   - LLM analyzes a story and determines if it needs clarification
   - User provides input when asked
   - LLM implements with access to MCP tools
   - Multiple exit paths (success, timeout, error)
   
   This example shows how to use make-user-action with adapter functions
   to decouple the generic user-action from FSM-specific types."
  (:require
   [claij.schema :refer [def-fsm]]
   [claij.actions :as actions]))

;;------------------------------------------------------------------------------
;; FSM Type Definitions
;;------------------------------------------------------------------------------
;;
;; This FSM defines three types for the ask-user state:
;;
;; I (Input) - What the LLM sends to user-action:
;;   {"id" ["analyze" "ask-user"]
;;    "story-id" 123
;;    "question" "Do you want JWT or OAuth?"}
;;
;; S (Success) - What user-action returns on successful input:
;;   {"id" ["ask-user" "implement"]
;;    "story-id" 123
;;    "question" "Do you want JWT or OAuth?"
;;    "user-answer" "JWT"}
;;
;; F (Failure) - What user-action returns on timeout:
;;   {"id" ["ask-user" "timeout"]
;;    "story-id" 123
;;    "question" "Do you want JWT or OAuth?"
;;    "timeout" true}
;;------------------------------------------------------------------------------

(def-fsm conversational-fsm
  "Conversational FSM with LLM-user-LLM loop.
   
   Flow:
   1. User submits story ID
   2. LLM analyzes story, decides if clarification needed
   3. If needed: ask user, wait for response, implement
   4. If confident: implement directly
   5. During implementation: LLM can loop back to ask more questions"

  ;; States
  {:states
   [{:id "start"}

    {:id "analyze"
     :action "llm"
     :description "LLM analyzes story and decides next step"}

    {:id "ask-user"
     :action "story-user-action" ; Custom action created by make-user-action
     :description "Wait for user input with timeout"}

    {:id "implement"
     :action "llm"
     :description "LLM implements story with MCP tools"}

    {:id "timeout"
     :description "User didn't respond in time"}

    {:id "end"}]

   ;; Transitions
   :xitions
   [;; Entry: user submits story ID
    {:id ["start" "analyze"]
     :schema {:type "object"
              :required ["id" "story-id"]
              :properties {:id {:const ["start" "analyze"]}
                           :story-id {:type "integer"}}}}

    ;; LLM analysis results
    {:id ["analyze" "ask-user"]
     :label "needs clarification"
     :schema {:type "object"
              :required ["id" "story-id" "question"]
              :properties {:id {:const ["analyze" "ask-user"]}
                           :story-id {:type "integer"}
                           :question {:type "string"}}}}

    {:id ["analyze" "implement"]
     :label "confident, proceed"
     :schema {:type "object"
              :required ["id" "story-id"]
              :properties {:id {:const ["analyze" "implement"]}
                           :story-id {:type "integer"}}}}

    {:id ["analyze" "end"]
     :label "cannot proceed"
     :schema {:type "object"
              :required ["id" "story-id" "reason"]
              :properties {:id {:const ["analyze" "end"]}
                           :story-id {:type "integer"}
                           :reason {:type "string"}}}}

    ;; User input results (SUCCESS)
    {:id ["ask-user" "implement"]
     :label "user responded"
     :schema {:type "object"
              :required ["id" "story-id" "question" "user-answer"]
              :properties {:id {:const ["ask-user" "implement"]}
                           :story-id {:type "integer"}
                           :question {:type "string"}
                           :user-answer {:type "string"}}}}

    ;; User input results (FAILURE - timeout)
    {:id ["ask-user" "timeout"]
     :label "user timeout"
     :schema {:type "object"
              :required ["id" "story-id" "question" "timeout"]
              :properties {:id {:const ["ask-user" "timeout"]}
                           :story-id {:type "integer"}
                           :question {:type "string"}
                           :timeout {:const true}}}}

    ;; User input results (FAILURE - no socket)
    {:id ["ask-user" "end"]
     :label "no socket error"
     :schema {:type "object"
              :required ["id" "error"]
              :properties {:id {:const ["ask-user" "end"]}
                           :error {:const "no-socket"}}}}

    ;; Implementation results
    {:id ["implement" "ask-user"]
     :label "needs more input"
     :schema {:type "object"
              :required ["id" "story-id" "question"]
              :properties {:id {:const ["implement" "ask-user"]}
                           :story-id {:type "integer"}
                           :question {:type "string"}}}}

    {:id ["implement" "end"]
     :label "implementation complete"
     :schema {:type "object"
              :required ["id" "story-id" "status"]
              :properties {:id {:const ["implement" "end"]}
                           :story-id {:type "integer"}
                           :status {:enum ["success" "error"]}
                           :message {:type "string"}}}}

    ;; Timeout exit
    {:id ["timeout" "end"]
     :schema {:type "object"
              :required ["id" "story-id"]
              :properties {:id {:const ["timeout" "end"]}
                           :story-id {:type "integer"}
                           :timeout {:const true}}}}]

   ;; Prompts
   :prompts
   ["You are helping implement user stories."
    "When you need clarification, transition to 'ask-user' with a clear question."
    "When you have enough information, transition to 'implement' and use MCP tools."
    "If you cannot proceed, transition to 'end' with a reason."]})

;;------------------------------------------------------------------------------
;; Adapter Functions
;;------------------------------------------------------------------------------

(defn story-I->s
  "Extract prompt string from FSM input (type I).
   
   Input: {\"story-id\" 123 \"question\" \"Do you want JWT or OAuth?\"}
   Output: \"Story #123: Do you want JWT or OAuth?\""
  [input]
  (str "Story #" (get input "story-id") ": " (get input "question")))

(defn story-s->S
  "Convert user's string response to FSM success type (type S).
   
   Input context: {\"story-id\" 123 \"question\" \"...\"}
   User string: \"JWT\"
   Output: {\"id\" [\"ask-user\" \"implement\"]
            \"story-id\" 123
            \"question\" \"...\"
            \"user-answer\" \"JWT\"}"
  [input user-string]
  {"id" ["ask-user" "implement"]
   "story-id" (get input "story-id")
   "question" (get input "question")
   "user-answer" user-string})

(defn story->F
  "Generate FSM failure type on timeout (type F).
   
   Input context: {\"story-id\" 123 \"question\" \"...\"}
   Output: {\"id\" [\"ask-user\" \"timeout\"]
            \"story-id\" 123
            \"question\" \"...\"
            \"timeout\" true}"
  [input]
  {"id" ["ask-user" "timeout"]
   "story-id" (get input "story-id")
   "question" (get input "question")
   "timeout" true})

(defn story-no-socket->F
  "Generate FSM failure when socket is missing.
   
   Output: {\"id\" [\"ask-user\" \"end\"]
            \"error\" \"no-socket\"}"
  []
  {"id" ["ask-user" "end"]
   "error" "no-socket"})

;;------------------------------------------------------------------------------
;; Context Setup
;;------------------------------------------------------------------------------

(defn make-conversational-context
  "Create context for the conversational FSM.
   
   This is where the coupling happens - we wire up the generic user-action
   with FSM-specific adapter functions.
   
   Options:
   - :store - Database connection (required)
   - :llm/service - LLM service name (default: \"anthropic\")
   - :llm/model - LLM model (default: Claude Sonnet)
   - :timeout-ms - User input timeout (default: 300000 = 5 minutes)"
  [{:keys [store timeout-ms]
    :or {timeout-ms 300000}
    :as opts}]
  (let [;; Create the custom user-action for this FSM using make-user-action
        story-user-action
        (actions/make-user-action
          ;; I->s: Extract prompt from FSM input
         story-I->s

          ;; s->S: Convert user string to FSM success type
         story-s->S

          ;; ->F: Generate FSM failure on timeout
         story->F

          ;; no-socket->F: Generate failure when socket missing
         story-no-socket->F)]

    ;; Base context with custom action registered
    (-> (actions/make-context opts)
        (assoc-in [:id->action "story-user-action"] story-user-action))))

;;------------------------------------------------------------------------------
;; Usage Example
;;------------------------------------------------------------------------------

(comment
  ;; Start conversational FSM
  (require '[claij.fsm :as fsm]
           '[claij.store :as store])

  (def store (store/make-store "jdbc:h2:mem:test"))

  (def context (make-conversational-context {:store store}))

  (def {:keys [submit await stop]} (fsm/start-fsm context conversational-fsm))

  ;; Submit story ID
  (submit {"id" ["start" "analyze"]
           "story-id" 123})

  ;; FSM runs:
  ;; 1. analyze state (LLM) → decides to ask user
  ;; 2. ask-user state (user-action) → waits for input
  ;; 3. User responds via SSE
  ;; 4. implement state (LLM with MCP) → implements story
  ;; 5. May loop back to ask-user if needs more info

  ;; Wait for completion
  (let [[final-context trail] (await 300000)]
    (fsm/last-event trail)))
