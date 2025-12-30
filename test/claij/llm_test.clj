(ns claij.llm-test
  "Tests for LLM integration including EDN parse retry."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.data.json :as json]
   [claij.llm :as llm]
   [claij.llm.service :as svc]))

(def test-registry
  {"test-service" {:strategy "openai-compat"
                   :url "http://test.local/v1/chat/completions"
                   :auth nil}})

(deftest edn-parse-retry-mock-test
  (testing "EDN parse errors trigger retries with error feedback"
    (let [;; Track attempts and prompts
          attempts (atom [])

          ;; Mock HTTP post that simulates LLM behavior
          mock-post (fn [_url {:keys [body]} success-callback _error-callback]
                      (let [parsed-body (json/read-str body)
                            messages (get parsed-body "messages")
                            attempt-num (count @attempts)]
                        (swap! attempts conj messages)

                        (cond
                          ;; First attempt: return malformed JSON (has EDN keyword :bad)
                          (= attempt-num 0)
                          (success-callback {:body (json/write-str
                                                    {"choices" [{"message" {"content" "{\"id\": [\"a\", \"b\"], :bad"}}]})})

                          ;; Second attempt: return valid JSON
                          :else
                          (success-callback {:body (json/write-str
                                                    {"choices" [{"message" {"content" "{\"id\": [\"a\", \"b\"], \"data\": \"ok\"}"}}]})}))))

          result (promise)]

      ;; Temporarily replace HTTP post
      (with-redefs [clj-http.client/post mock-post]
        (llm/call
         "test-service" "model"
         [{"role" "user" "content" "test"}]
         (partial deliver result)
         {:registry test-registry
          :max-retries 3}))

      ;; Wait for result
      (let [final-result (deref result 5000 ::timeout)]
        (is (not= final-result ::timeout) "Should complete")
        (is (= (count @attempts) 2) "Should make 2 attempts")

        ;; Second attempt should include error message
        (let [retry-messages (second @attempts)
              last-message (last retry-messages)
              content (get last-message "content")]
          (is (re-find #"not valid JSON" content) "Error message should mention invalid JSON")
          (is (re-find #"JSON" content) "Error message should mention JSON"))

        ;; Should eventually succeed
        (is (= (get final-result "id") ["a" "b"]) "Should parse valid JSON on retry")))))

(deftest edn-parse-max-retries-test
  (testing "EDN parse gives up after max retries"
    (let [;; Track attempts
          attempt-count (atom 0)
          ;; Use promise for coordination instead of Thread/sleep
          error-promise (promise)

          ;; Mock HTTP post that always returns bad EDN
          mock-post (fn [_url _opts success-callback _error-callback]
                      (swap! attempt-count inc)
                      (success-callback {:body (json/write-str
                                                {"choices" [{"message" {"content" "{:bad edn {"}}]})}))]

      (with-redefs [clj-http.client/post mock-post]
        (llm/call
         "test-service" "model"
         [{"role" "user" "content" "test"}]
         (fn [_] nil) ; success handler (won't be called)
         {:registry test-registry
          :max-retries 2
          :error (fn [_] (deliver error-promise true))}))

      ;; Wait for error handler with timeout
      (let [error-called (deref error-promise 5000 :timeout)]
        (is (not= error-called :timeout) "Error handler should be called within timeout")
        (is (= @attempt-count 3) "Should try initial + 2 retries")))))

(deftest strip-md-json-test
  (testing "strips markdown code fences"
    (is (= "{\"key\": \"value\"}"
           (llm/strip-md-json "```json\n{\"key\": \"value\"}\n```")))
    (is (= "{\"key\": \"value\"}"
           (llm/strip-md-json "```\n{\"key\": \"value\"}\n```")))
    (is (= "(+ 1 2)"
           (llm/strip-md-json "```clojure\n(+ 1 2)\n```")))
    (is (= "{:a 1}"
           (llm/strip-md-json "```edn\n{:a 1}\n```"))))

  (testing "handles content without fences"
    (is (= "{\"key\": \"value\"}"
           (llm/strip-md-json "{\"key\": \"value\"}")))
    (is (= "plain text"
           (llm/strip-md-json "plain text")))))

(deftest call-nil-content-test
  (testing "nil content from LLM triggers error handler"
    (let [error-promise (promise)
          mock-post (fn [_url _opts success-callback _error-callback]
                      ;; Return response with nil content (missing choices)
                      (success-callback {:body (json/write-str {"choices" []})}))]
      (with-redefs [clj-http.client/post mock-post]
        (llm/call
         "test-service" "model"
         [{"role" "user" "content" "test"}]
         (fn [_] (is false "Handler should not be called"))
         {:registry test-registry
          :error (fn [e] (deliver error-promise e))}))

      (let [error (deref error-promise 2000 :timeout)]
        (is (not= error :timeout) "Error handler should be called")
        (is (= "nil-content" (get error "error")))))))

(deftest call-non-map-json-test
  (testing "non-map JSON response triggers retry"
    (let [attempts (atom 0)
          result-promise (promise)
          mock-post (fn [_url _opts success-callback _error-callback]
                      (swap! attempts inc)
                      (if (= @attempts 1)
                        ;; First attempt: return array (not a map)
                        (success-callback {:body (json/write-str
                                                  {"choices" [{"message" {"content" "[1, 2, 3]"}}]})})
                        ;; Second attempt: return valid map
                        (success-callback {:body (json/write-str
                                                  {"choices" [{"message" {"content" "{\"id\": \"ok\"}"}}]})})))]
      (with-redefs [clj-http.client/post mock-post]
        (llm/call
         "test-service" "model"
         [{"role" "user" "content" "test"}]
         (fn [result] (deliver result-promise result))
         {:registry test-registry
          :max-retries 3}))

      (let [result (deref result-promise 5000 :timeout)]
        (is (not= result :timeout) "Should complete")
        (is (= 2 @attempts) "Should retry once")
        (is (= "ok" (get result "id")))))))

(deftest call-http-error-test
  (testing "HTTP error calls error handler"
    (let [error-promise (promise)
          mock-post (fn [_url _opts _success-callback error-callback]
                      ;; Simulate HTTP error with body containing error details
                      (error-callback (ex-info "Connection refused"
                                               {:body (json/write-str {"error" "service_unavailable"})})))]
      (with-redefs [clj-http.client/post mock-post]
        (llm/call
         "test-service" "model"
         [{"role" "user" "content" "test"}]
         (fn [_] (is false "Handler should not be called"))
         {:registry test-registry
          :error (fn [e] (deliver error-promise e))}))

      (let [error (deref error-promise 2000 :timeout)]
        (is (not= error :timeout) "Error handler should be called")
        (is (= "service_unavailable" (get error "error")))))))

(deftest call-http-error-no-body-test
  (testing "HTTP error without body still calls error handler"
    (let [error-promise (promise)
          mock-post (fn [_url _opts _success-callback error-callback]
                      ;; Simulate HTTP error without body
                      (error-callback (Exception. "Network timeout")))]
      (with-redefs [clj-http.client/post mock-post]
        (llm/call
         "test-service" "model"
         [{"role" "user" "content" "test"}]
         (fn [_] (is false "Handler should not be called"))
         {:registry test-registry
          :error (fn [e] (deliver error-promise e))}))

      (let [error (deref error-promise 2000 :timeout)]
        (is (not= error :timeout) "Error handler should be called")
        (is (= "request-failed" (get error "error")))
        (is (= "Network timeout" (get error "message")))))))

(deftest call-tool-calls-response-test
  (testing "tool_calls response passes through to handler"
    (let [result-promise (promise)
          mock-post (fn [_url _opts success-callback _error-callback]
                      ;; Return response with tool_calls
                      (success-callback {:body (json/write-str
                                                {"choices" [{"message" 
                                                             {"content" nil
                                                              "tool_calls" [{"id" "call_123"
                                                                             "function" {"name" "get_weather"
                                                                                         "arguments" "{\"city\":\"London\"}"}}]}}]})}))]
      (with-redefs [clj-http.client/post mock-post]
        (llm/call
         "test-service" "model"
         [{"role" "user" "content" "test"}]
         (fn [result] (deliver result-promise result))
         {:registry test-registry}))

      (let [result (deref result-promise 2000 :timeout)]
        (is (not= result :timeout) "Handler should be called")
        (is (= [{"id" "call_123" "name" "get_weather" "arguments" {"city" "London"}}]
               (get result "tool_calls")))))))
