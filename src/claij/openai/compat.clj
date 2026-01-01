(ns claij.openai.compat
  "OpenAI-compatible chat completions API for CLAIJ FSMs.
   
   Exposes FSMs via POST /v1/chat/completions endpoint, enabling any
   OpenAI-compatible chat UI (Open WebUI, LobeChat, etc.) to become
   a frontend for CLAIJ societies.
   
   Key transformations:
   1. OpenAI messages → synthetic trail entries (for context preservation)
   2. Model name → FSM ID lookup
   3. FSM output → OpenAI response format"
  (:require
   [clojure.tools.logging :as log]
   [clojure.string :as str]
   [claij.fsm :as fsm]
   [claij.fsm.registry :as registry]
   [claij.actions :as actions]
   [claij.model :as model]))

;;------------------------------------------------------------------------------
;; Message → Trail Conversion
;;------------------------------------------------------------------------------

(defn openai-message->trail-entry
  "Convert a single OpenAI message to a synthetic trail entry.
   
   OpenAI messages have {\"role\" \"system\"|\"user\"|\"assistant\", \"content\" \"...\"}
   
   Trail entries have {:from :to :event}
   
   We use synthetic state IDs \"chat\" to indicate these are external to the FSM.
   The event preserves the original role/content structure.
   
   Example:
     {\"role\" \"user\" \"content\" \"Hello\"}
     → {:from \"chat\" :to \"chat\" :event {\"role\" \"user\" \"content\" \"Hello\"}}"
  [message]
  {:from "chat"
   :to "chat"
   :event message})

(defn openai-messages->trail
  "Convert OpenAI message array to synthetic trail entries.
   
   IMPORTANT: Filters out system messages to prevent UI metadata/prompts
   from interfering with FSM execution. Only user and assistant messages
   are preserved.
   
   Takes: [{\"role\" \"system\" \"content\" \"...\"} ...]
   Returns: [{:from \"chat\" :to \"chat\" :event {...}} ...]"
  [messages]
  (let [;; Filter to only user and assistant messages
        ;; This prevents Open WebUI's system prompts (tagging, categorization, etc.)
        ;; from bleeding into the FSM's conversation context
        filtered-messages (filterv #(contains? #{"user" "assistant"} (get % "role")) messages)

        ;; Log filtering for debugging
        filtered-count (- (count messages) (count filtered-messages))]

    (when (pos? filtered-count)
      (log/info "OpenAI compat: Filtered out" filtered-count "system messages"))

    (mapv openai-message->trail-entry filtered-messages)))

;;------------------------------------------------------------------------------
;; Model Name Parsing
;;------------------------------------------------------------------------------

(defn parse-model-name
  "Parse OpenAI model name to extract FSM ID.
   
   Convention: \"claij/fsm-id\" or \"claij-fsm-id\" → \"fsm-id\"
   
   Examples:
     \"claij/code-review\" → \"code-review\"
     \"claij-code-review\" → \"code-review\"
     \"code-review\" → \"code-review\" (pass through)
   
   Returns nil if model name is empty or nil."
  [model-name]
  (when (and model-name (not (str/blank? model-name)))
    (let [;; Remove optional "claij/" or "claij-" prefix
          without-prefix (-> model-name
                             (str/replace #"^claij/" "")
                             (str/replace #"^claij-" ""))]
      (when (not (str/blank? without-prefix))
        without-prefix))))

;;------------------------------------------------------------------------------
;; FSM Output → OpenAI Response
;;------------------------------------------------------------------------------

(defn fsm-output->openai-response
  "Convert FSM output event to OpenAI chat completion response.
   
   For non-streaming mode, returns:
   {\"id\" \"chatcmpl-xxx\"
    \"object\" \"chat.completion\"
    \"created\" <unix-timestamp>
    \"model\" <original-model-name>
    \"choices\" [{\"index\" 0
                 \"message\" {\"role\" \"assistant\"
                             \"content\" <content-string>}
                 \"finish_reason\" \"stop\"}]}
   
   Extracts the appropriate content field based on FSM type."
  [model-name fsm-output]
  (let [;; Parse model name to get FSM ID
        fsm-id (parse-model-name model-name)

        ;; Extract content based on FSM type
        content (cond
                  ;; Society FSM: extract summary field
                  (= fsm-id "society")
                  (get fsm-output "summary")

                  ;; Code review FSM: extract notes field
                  (= fsm-id "code-review-fsm")
                  (get fsm-output "notes")

                  ;; BDD FSM: would extract voice/audio field
                  (= fsm-id "bdd")
                  (get fsm-output "tts") ; or whatever the audio field is

                  ;; Default: look for common field names, fallback to pr-str
                  :else
                  (or (get fsm-output "summary")
                      (get fsm-output "answer")
                      (get fsm-output "message")
                      (get fsm-output "content")
                      (get fsm-output "notes")
                      ;; If no known fields, stringify the whole thing
                      (pr-str fsm-output)))

        ;; Generate unique completion ID
        completion-id (str "chatcmpl-" (java.util.UUID/randomUUID))

        ;; Current Unix timestamp
        created (quot (System/currentTimeMillis) 1000)]

    {"id" completion-id
     "object" "chat.completion"
     "created" created
     "model" model-name
     "choices" [{"index" 0
                 "message" {"role" "assistant"
                            "content" content}
                 "finish_reason" "stop"}]}))

;;------------------------------------------------------------------------------
;; Entry Event Creation
;;------------------------------------------------------------------------------

(defn make-entry-event
  "Create entry event for the FSM from OpenAI messages.
   
   For FSMs like 'society' that expect plain text input, we extract
   the last user message and format it according to the FSM's entry schema.
   
   Args:
     fsm-id: The FSM identifier
     messages: Array of OpenAI message objects
   
   Returns:
     Event map formatted for the FSM's entry transition"
  [fsm-id messages]
  (let [;; Get the last user message
        last-user-msg (last (filter #(= (get % "role") "user") messages))
        content (get last-user-msg "content" "")

        ;; Look up FSM to get its entry transition
        fsm-entry (registry/get-fsm-entry fsm-id)
        fsm (get fsm-entry :definition)

        ;; Find the transition from "start"
        xitions (get fsm "xitions")
        start-xition (first (filter (fn [{[from _] "id"}] (= from "start")) xitions))

        ;; Extract the destination state
        [_ next-state] (get start-xition "id")]

    ;; Create event based on FSM type
    (cond
      ;; Society FSM expects {"message": "..."}
      (= fsm-id "society")
      {"id" ["start" next-state]
       "message" content}

      ;; Other FSMs might need different formats
      ;; For now, default to empty event (relies on synthetic trail)
      :else
      {"id" ["start" next-state]})))

;;------------------------------------------------------------------------------
;; Main Handler
;;------------------------------------------------------------------------------

(defn chat-completion-handler
  "Handle POST /v1/chat/completions requests.
   
   Request body (OpenAI format):
   {\"model\" \"claij/code-review\"
    \"messages\" [{\"role\" \"user\" \"content\" \"...\"}]
    \"stream\" false}
   
   Response (OpenAI format):
   {\"id\" \"chatcmpl-xxx\"
    \"object\" \"chat.completion\"
    \"created\" 1234567890
    \"model\" \"claij/code-review\"
    \"choices\" [{\"message\" {\"role\" \"assistant\" \"content\" \"...\"}
                 \"finish_reason\" \"stop\"}]}
   
   Error responses:
   - 400: Invalid model name
   - 404: FSM not found
   - 500: FSM execution error
   - 504: FSM timeout"
  [{{:strs [model messages stream]} :body-params :as request}]

  ;; DEBUG: Log the request details including message roles
  (let [message-roles (mapv #(get % "role") messages)]
    (log/info "OpenAI compat: POST /v1/chat/completions"
              {:model model
               :message-count (count messages)
               :stream stream
               :roles message-roles}))

  ;; CRITICAL: Streaming not implemented - reject streaming requests
  ;; This prevents Open WebUI from waiting for SSE chunks that never arrive
  (when stream
    (log/warn "OpenAI compat: Streaming requested but not supported - rejecting request")
    (throw (ex-info "Streaming not supported. Please disable streaming in your client settings."
                    {:status 400
                     :error {:message "Streaming is not currently supported by CLAIJ. Please set 'stream': false in your request."
                             :type "invalid_request_error"
                             :code "streaming_not_supported"}})))

  (try
    ;; Step 1: Parse model name to get FSM ID
    (let [fsm-id (parse-model-name model)]
      (if-not fsm-id
        {:status 400
         :body {"error" {"message" "Invalid model name - expected format: claij/fsm-id"
                         "type" "invalid_request_error"}}}

        ;; Step 2: Look up FSM in registry
        (if-let [fsm-entry (registry/get-fsm-entry fsm-id)]
          (let [fsm-def (:definition fsm-entry)

                ;; Step 3: Convert OpenAI messages to synthetic trail entries
                synthetic-trail (openai-messages->trail messages)

                ;; Step 4: Create entry event from messages
                entry-event (make-entry-event fsm-id messages)

                ;; Step 5: Create FSM context with initial trail
                ;; Use OpenRouter with Claude to avoid hitting Anthropic credit limits
                context (-> (actions/make-context
                             {:llm/service "openrouter"
                              :llm/model (model/openrouter-model :anthropic)})
                            ;; NEW: Add synthetic trail to context
                            (assoc :initial-trail synthetic-trail))

                ;; Step 6: Start FSM (it will pick up :initial-trail from context)
                {:keys [submit await stop]} (fsm/start-fsm context fsm-def)]

            (try
              ;; Step 7: Submit minimal entry event
              ;; The FSM will prepend the synthetic trail automatically
              (submit entry-event)

              ;; Step 8: Wait for completion (3 minute timeout for multi-LLM coordination)
              (let [result (await 180000)]
                (if (= result :timeout)
                  {:status 504
                   :body {"error" {"message" "FSM execution timed out"
                                   "type" "timeout_error"}}}

                  ;; Step 9: Extract final event from trail
                  (let [[_final-context trail] result
                        final-event (fsm/last-event trail)

                        ;; Step 10: Convert to OpenAI response format
                        response (fsm-output->openai-response model final-event)]

                    {:status 200
                     :body response})))

              (finally
                ;; Clean up FSM
                (future (stop)))))

          ;; FSM not found
          {:status 404
           :body {"error" {"message" (str "FSM not found: " fsm-id)
                           "type" "invalid_request_error"}}})))

    (catch Exception e
      (log/error e "OpenAI compat: Error handling request")
      {:status 500
       :body {"error" {"message" (.getMessage e)
                       "type" "internal_error"}}})))

;;------------------------------------------------------------------------------
;; Models Endpoint
;;------------------------------------------------------------------------------

(defn list-models-handler
  "Handle GET /v1/models - list available FSMs as models.
   
   Returns:
   {\"data\" [{\"id\" \"claij/code-review\"
              \"object\" \"model\"
              \"owned_by\" \"claij\"}
             ...]}"
  [_request]
  (let [;; Get all FSM IDs from registry
        fsm-ids (registry/list-fsm-ids)

        ;; Convert to OpenAI model format
        models (mapv (fn [fsm-id]
                       {"id" (str "claij/" fsm-id)
                        "object" "model"
                        "owned_by" "claij"
                        "created" (quot (System/currentTimeMillis) 1000)})
                     fsm-ids)]

    {:status 200
     :body {"object" "list"
            "data" models}}))
