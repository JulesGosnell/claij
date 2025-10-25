(ns claij.new.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [claij.new.core :as core]
            [claij.new.interceptor :as interceptor]
            [clojure.data.json :as json]))

(deftest test-parse-json-response
  (testing "valid JSON"
    (let [result (core/parse-json-response "{\"answer\":\"Hello\",\"state\":\"ready\"}")]
      (is (:success result))
      (is (= "Hello" (:answer (:data result))))
      (is (= "ready" (:state (:data result))))))

  (testing "invalid JSON"
    (let [result (core/parse-json-response "{invalid json")]
      (is (not (:success result)))
      (is (:error result))
      (is (re-find #"parse" (:error result))))))

(deftest test-send-with-validation
  (testing "successful validation on first try"
    (let [llm-fn (fn [prompts]
                   (json/write-str {:answer "Hello" :state "ready"}))
          result (core/send-with-validation
                  llm-fn
                  {:system "System" :user "Hello"}
                  {:type "object"
                   :required ["answer" "state"]
                   :properties {:answer {:type "string"}
                                :state {:type "string"}}}
                  {})]
      (is (:success result))
      (is (= 1 (:attempts result)))
      (is (= "Hello" (:answer (:response result))))))

  (testing "retries on validation failure then succeeds"
    (let [attempt (atom 0)
          llm-fn (fn [prompts]
                   (swap! attempt inc)
                   (if (= @attempt 1)
                     ;; First attempt - missing field
                     (json/write-str {:answer "Hello"})
                     ;; Second attempt - valid
                     (json/write-str {:answer "Hello" :state "ready"})))
          result (core/send-with-validation
                  llm-fn
                  {:system "System" :user "Hello"}
                  {:type "object"
                   :required ["answer" "state"]
                   :properties {:answer {:type "string"}
                                :state {:type "string"}}}
                  {})]
      (is (:success result))
      (is (= 2 (:attempts result)))
      (is (= 2 @attempt))))

  (testing "fails after max retries"
    (let [llm-fn (fn [prompts]
                   ;; Always return invalid response
                   (json/write-str {:answer "Hello"}))
          result (core/send-with-validation
                  llm-fn
                  {:system "System" :user "Hello"}
                  {:type "object"
                   :required ["answer" "state"]
                   :properties {:answer {:type "string"}
                                :state {:type "string"}}}
                  {})]
      (is (not (:success result)))
      (is (= core/max-validation-retries (:attempts result)))
      (is (re-find #"Max retries" (:error result)))))

  (testing "retries on JSON parse failure"
    (let [attempt (atom 0)
          llm-fn (fn [prompts]
                   (swap! attempt inc)
                   (if (= @attempt 1)
                     ;; First attempt - invalid JSON
                     "{invalid json"
                     ;; Second attempt - valid
                     (json/write-str {:answer "Hello" :state "ready"})))
          result (core/send-with-validation
                  llm-fn
                  {:system "System" :user "Hello"}
                  {:type "object"
                   :required ["answer" "state"]
                   :properties {:answer {:type "string"}
                                :state {:type "string"}}}
                  {})]
      (is (:success result))
      (is (= 2 (:attempts result)))))

  (testing "retries include original request and error"
    (let [prompts-seen (atom [])
          llm-fn (fn [prompts]
                   (swap! prompts-seen conj prompts)
                   (if (= (count @prompts-seen) 1)
                     ;; First attempt - invalid
                     (json/write-str {:answer "Hello"})
                     ;; Second attempt - valid
                     (json/write-str {:answer "Hello" :state "ready"})))
          result (core/send-with-validation
                  llm-fn
                  {:system "System" :user "Original message"}
                  {:type "object"
                   :required ["answer" "state"]
                   :properties {:answer {:type "string"}
                                :state {:type "string"}}}
                  {})]
      (is (:success result))
      ;; Second prompt should mention the error
      (is (re-find #"validation" (:system (second @prompts-seen))))
      (is (re-find #"Original message" (:system (second @prompts-seen)))))))

(deftest test-call-llm
  (testing "successful call with no interceptors"
    (let [llm-fn (fn [prompts]
                   (json/write-str {:answer "Hello" :state "ready"}))
          result (core/call-llm llm-fn "Hi there" [])]
      (is (:success result))
      (is (= "Hello" (:answer (:response result))))
      (is (= "ready" (:state (:response result))))))

  (testing "call with memory interceptor"
    (let [llm-fn (fn [prompts]
                   (json/write-str {:answer "I remember!"
                                    :state "ready"
                                    :summary "User likes blue"}))
          result (core/call-llm
                  llm-fn
                  "My favorite color is blue"
                  [interceptor/memory-interceptor])]
      (is (:success result))
      (is (= "User likes blue" (:memory (:ctx result))))))

  (testing "memory persists across calls"
    (let [llm-fn (fn [prompts]
                   (if (re-find #"Previous context" (:system prompts))
                     ;; Second call - sees memory
                     (json/write-str {:answer "Yes, you like blue!"
                                      :state "ready"
                                      :summary "Confirmed color preference"})
                     ;; First call - no memory yet
                     (json/write-str {:answer "Got it"
                                      :state "ready"
                                      :summary "User likes blue"})))

          ;; First call
          result1 (core/call-llm
                   llm-fn
                   "I like blue"
                   [interceptor/memory-interceptor])

          ;; Second call with ctx from first
          result2 (core/call-llm
                   llm-fn
                   "What's my favorite color?"
                   [interceptor/memory-interceptor]
                   (:ctx result1))]

      (is (:success result1))
      (is (:success result2))
      (is (= "User likes blue" (:memory (:ctx result1))))
      (is (= "Confirmed color preference" (:memory (:ctx result2))))))

  (testing "multiple interceptors work together"
    (let [test-int {:name :test
                    :pre-schema (fn [schema ctx]
                                  {:properties {:extra {:type "string"}}})
                    :post-response (fn [response ctx]
                                     (assoc ctx :processed true))}
          llm-fn (fn [prompts]
                   (json/write-str {:answer "Hello"
                                    :state "ready"
                                    :summary "Test"
                                    :extra "data"}))
          result (core/call-llm
                  llm-fn
                  "Test message"
                  [interceptor/memory-interceptor test-int])]
      (is (:success result))
      (is (= "Test" (:memory (:ctx result))))
      (is (:processed (:ctx result)))))

  (testing "invalid schema rejected"
    (let [bad-int {:name :bad
                   :pre-schema (fn [schema ctx]
                                 {:type "array"})} ; Invalid - root must be object
          llm-fn (fn [prompts] "doesn't matter")
          result (core/call-llm llm-fn "Test" [bad-int])]
      (is (not (:success result)))
      (is (re-find #"Invalid schema" (:error result)))))

  (testing "LLM function exception handled"
    (let [llm-fn (fn [prompts]
                   (throw (Exception. "Network error")))
          result (core/call-llm llm-fn "Test" [])]
      (is (not (:success result)))
      (is (:exception result)))))

(deftest test-validation-retry-logic
  (testing "each retry gets clear feedback"
    (let [attempts (atom [])
          llm-fn (fn [prompts]
                   (swap! attempts conj prompts)
                   (cond
                     ;; First try - missing state
                     (= (count @attempts) 1)
                     (json/write-str {:answer "Hello"})

                     ;; Second try - wrong type for state
                     (= (count @attempts) 2)
                     (json/write-str {:answer "Hello" :state 42})

                     ;; Third try - correct
                     :else
                     (json/write-str {:answer "Hello" :state "ready"})))
          result (core/call-llm llm-fn "Test" [])]

      (is (:success result))
      (is (= 3 (:attempts result)))

      ;; Check that retry prompts mentioned the errors
      (is (re-find #"Missing required fields" (:system (second @attempts))))
      (is (re-find #"expected string, got number" (:system (nth @attempts 2)))))))
