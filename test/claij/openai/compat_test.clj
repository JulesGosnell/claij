(ns claij.openai.compat-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [claij.openai.compat :as compat]))

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
  (testing "converts multiple messages to trail"
    (let [messages [{"role" "system" "content" "You are helpful"}
                    {"role" "user" "content" "Hello"}
                    {"role" "assistant" "content" "Hi there"}
                    {"role" "user" "content" "Help me"}]
          trail (compat/openai-messages->trail messages)]

      (is (= 4 (count trail)))
      (is (every? #(= "chat" (:from %)) trail))
      (is (every? #(= "chat" (:to %)) trail))
      (is (= messages (mapv :event trail)))))

  (testing "handles empty message array"
    (let [trail (compat/openai-messages->trail [])]
      (is (= [] trail)))))

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
      (is (.startsWith (get response "id") "chatcmpl-")) ;; Should have completion ID
      (is (get-in response ["choices" 0 "message" "content"])))))
