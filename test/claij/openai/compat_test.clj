(ns claij.openai.compat-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [claij.openai.compat :as compat]
   [claij.fsm.registry :as registry]))

;;------------------------------------------------------------------------------
;; Message → Trail Conversion Tests
;;------------------------------------------------------------------------------

(deftest openai-message->trail-entry-test
  (testing "converts system message"
    (let [message {"role" "system" "content" "You are helpful"}
          entry (compat/openai-message->trail-entry message)]
      (is (= "chat" (:from entry)))
      (is (= "chat" (:to entry)))
      (is (= message (:event entry)))))

  (testing "converts user message"
    (let [message {"role" "user" "content" "Hello"}
          entry (compat/openai-message->trail-entry message)]
      (is (= "chat" (:from entry)))
      (is (= "chat" (:to entry)))
      (is (= message (:event entry)))))

  (testing "converts assistant message"
    (let [message {"role" "assistant" "content" "Hi there"}
          entry (compat/openai-message->trail-entry message)]
      (is (= "chat" (:from entry)))
      (is (= "chat" (:to entry)))
      (is (= message (:event entry))))))

(deftest openai-messages->trail-test
  (testing "converts multiple messages to trail, filtering system messages"
    (let [messages [{"role" "system" "content" "You are helpful"}
                    {"role" "user" "content" "Hello"}
                    {"role" "assistant" "content" "Hi there"}
                    {"role" "user" "content" "Help me"}]
          trail (compat/openai-messages->trail messages)
          ;; System messages are filtered out, expect only 3 messages
          expected-messages [{"role" "user" "content" "Hello"}
                             {"role" "assistant" "content" "Hi there"}
                             {"role" "user" "content" "Help me"}]]

      (is (= 3 (count trail)))
      (is (every? #(= "chat" (:from %)) trail))
      (is (every? #(= "chat" (:to %)) trail))
      (is (= expected-messages (mapv :event trail)))))

  (testing "handles empty message array"
    (let [trail (compat/openai-messages->trail [])]
      (is (= [] trail))))

  (testing "filters out all system messages"
    (let [messages [{"role" "system" "content" "First system"}
                    {"role" "system" "content" "Second system"}
                    {"role" "user" "content" "Hello"}]
          trail (compat/openai-messages->trail messages)]
      (is (= 1 (count trail)))
      (is (= "Hello" (get-in trail [0 :event "content"]))))))

;;------------------------------------------------------------------------------
;; Model Name Parsing Tests
;;------------------------------------------------------------------------------

(deftest parse-model-name-test
  (testing "parses claij/ prefix"
    (is (= "code-review" (compat/parse-model-name "claij/code-review")))
    (is (= "bdd" (compat/parse-model-name "claij/bdd")))
    (is (= "triage-fsm" (compat/parse-model-name "claij/triage-fsm"))))

  (testing "parses claij- prefix"
    (is (= "code-review" (compat/parse-model-name "claij-code-review")))
    (is (= "bdd" (compat/parse-model-name "claij-bdd"))))

  (testing "passes through without prefix"
    (is (= "code-review" (compat/parse-model-name "code-review")))
    (is (= "my-fsm" (compat/parse-model-name "my-fsm"))))

  (testing "handles edge cases"
    (is (nil? (compat/parse-model-name nil)))
    (is (nil? (compat/parse-model-name "")))
    (is (nil? (compat/parse-model-name "  ")))
    (is (nil? (compat/parse-model-name "claij/")))
    (is (nil? (compat/parse-model-name "claij-")))))

;;------------------------------------------------------------------------------
;; FSM Output → OpenAI Response Tests
;;------------------------------------------------------------------------------

(deftest fsm-output->openai-response-test
  (testing "converts FSM output to OpenAI response format"
    (let [fsm-output {"id" ["process" "end"]
                      "result" "success"
                      "data" "some data"}
          response (compat/fsm-output->openai-response "claij/test-fsm" fsm-output)]

      ;; Check required fields
      (is (string? (get response "id")))
      (is (.startsWith (get response "id") "chatcmpl-"))
      (is (= "chat.completion" (get response "object")))
      (is (number? (get response "created")))
      (is (= "claij/test-fsm" (get response "model")))

      ;; Check choices structure
      (let [choices (get response "choices")]
        (is (vector? choices))
        (is (= 1 (count choices)))

        (let [choice (first choices)]
          (is (= 0 (get choice "index")))
          (is (= "stop" (get choice "finish_reason")))

          (let [message (get choice "message")]
            (is (= "assistant" (get message "role")))
            ;; Content should be string representation of FSM output
            (is (string? (get message "content")))
            (is (.contains (get message "content") "result"))
            (is (.contains (get message "content") "success")))))))

  (testing "handles minimal FSM output"
    (let [fsm-output {"id" ["x" "y"]}
          response (compat/fsm-output->openai-response "claij/minimal" fsm-output)]
      (is (.startsWith (get response "id") "chatcmpl-"))
      (is (get-in response ["choices" 0 "message" "content"]))))

  (testing "extracts summary field for society FSM"
    (let [fsm-output {"id" ["synthesize" "end"]
                      "summary" "Claude says X. Grok says Y."}
          response (compat/fsm-output->openai-response "claij/society" fsm-output)]
      (is (= "Claude says X. Grok says Y." (get-in response ["choices" 0 "message" "content"])))))

  (testing "extracts notes field for code-review FSM"
    (let [fsm-output {"id" ["review" "end"]
                      "notes" "Code looks good!"}
          response (compat/fsm-output->openai-response "claij/code-review-fsm" fsm-output)]
      (is (= "Code looks good!" (get-in response ["choices" 0 "message" "content"]))))))

;;------------------------------------------------------------------------------
;; Chat Completion Handler Tests
;;------------------------------------------------------------------------------

(deftest chat-completion-handler-streaming-rejection-test
  (testing "rejects streaming requests with 400"
    (let [request {:body-params {"model" "claij/test-fsm"
                                 "messages" [{"role" "user" "content" "Hello"}]
                                 "stream" true}}
          response (compat/chat-completion-handler request)]
      (is (= 400 (:status response)))
      (is (= "invalid_request_error" (get-in response [:body "error" "type"])))
      (is (.contains (get-in response [:body "error" "message"]) "not currently supported")))))

(deftest chat-completion-handler-fsm-not-found-test
  (testing "returns 404 for non-existent FSM"
    (let [request {:body-params {"model" "claij/nonexistent-fsm-12345"
                                 "messages" [{"role" "user" "content" "Hello"}]}}
          response (compat/chat-completion-handler request)]
      (is (= 404 (:status response)))
      (is (= "invalid_request_error" (get-in response [:body "error" "type"])))
      (is (.contains (get-in response [:body "error" "message"]) "FSM not found")))))

;;------------------------------------------------------------------------------
;; Make Entry Event Tests  
;;------------------------------------------------------------------------------

(deftest make-entry-event-test
  (testing "creates entry event for society FSM"
    ;; Register a mock society FSM
    (registry/register-fsm! "society"
                            {"id" "society"
                             "states" [{"id" "start"}
                                       {"id" "chat" "action" "llm-call"}]
                             "xitions" [{"id" ["start" "chat"]}]})

    (let [messages [{"role" "system" "content" "You are helpful"}
                    {"role" "user" "content" "Hello, world!"}]
          event (compat/make-entry-event "society" messages)]

      (is (= ["start" "chat"] (get event "id")))
      (is (= "Hello, world!" (get event "message")))))

  (testing "creates entry event for generic FSM"
    (registry/register-fsm! "generic-test"
                            {"id" "generic-test"
                             "states" [{"id" "start"}
                                       {"id" "process" "action" "llm-call"}]
                             "xitions" [{"id" ["start" "process"]}]})

    (let [messages [{"role" "user" "content" "Process this"}]
          event (compat/make-entry-event "generic-test" messages)]

      (is (= ["start" "process"] (get event "id")))
      ;; Generic FSMs don't include message content in event
      (is (nil? (get event "message")))))

  (testing "handles empty messages array"
    (registry/register-fsm! "empty-test"
                            {"id" "empty-test"
                             "states" [{"id" "start"}
                                       {"id" "next"}]
                             "xitions" [{"id" ["start" "next"]}]})

    (let [messages []
          event (compat/make-entry-event "empty-test" messages)]
      (is (= ["start" "next"] (get event "id")))))

  (testing "extracts last user message from conversation"
    (registry/register-fsm! "society"
                            {"id" "society"
                             "states" [{"id" "start"}
                                       {"id" "chat"}]
                             "xitions" [{"id" ["start" "chat"]}]})

    (let [messages [{"role" "user" "content" "First message"}
                    {"role" "assistant" "content" "Response"}
                    {"role" "user" "content" "Second message"}]
          event (compat/make-entry-event "society" messages)]
      ;; Should use the last user message
      (is (= "Second message" (get event "message"))))))

(deftest chat-completion-handler-invalid-model-test
  (testing "returns 400 for invalid model name (empty)"
    (let [request {:body-params {"model" ""
                                 "messages" [{"role" "user" "content" "Hello"}]}}
          response (compat/chat-completion-handler request)]
      (is (= 400 (:status response)))
      (is (= "invalid_request_error" (get-in response [:body "error" "type"])))))

  (testing "returns 400 for invalid model name (just prefix)"
    (let [request {:body-params {"model" "claij/"
                                 "messages" [{"role" "user" "content" "Hello"}]}}
          response (compat/chat-completion-handler request)]
      (is (= 400 (:status response)))
      (is (= "invalid_request_error" (get-in response [:body "error" "type"]))))))

(deftest list-models-handler-test
  (testing "returns list of registered FSMs as models"
    ;; Register a test FSM
    (registry/register-fsm! "test-fsm"
                            {"id" "test-fsm"
                             "states" [{"id" "start" "action" "end"}]
                             "xitions" []})

    (let [response (compat/list-models-handler {})
          body (:body response)]
      (is (= 200 (:status response)))
      (is (= "list" (get body "object")))
      (is (vector? (get body "data")))
      (is (pos? (count (get body "data"))))

      ;; Check model format
      (let [models (get body "data")
            test-model (first (filter #(= "claij/test-fsm" (get % "id")) models))]
        (is (some? test-model))
        (is (= "model" (get test-model "object")))
        (is (= "claij" (get test-model "owned_by")))
        (is (number? (get test-model "created")))))))
