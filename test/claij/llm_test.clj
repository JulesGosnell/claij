(ns claij.llm-test
  "Tests for LLM integration including EDN parse retry."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.tools.logging :as log]
   [claij.llm :as llm]
   [claij.llm :as llm-dispatch]))

(deftest edn-parse-retry-mock-test
  (testing "EDN parse errors trigger retries with error feedback"
    (let [;; Track attempts and prompts
          attempts (atom [])

          ;; Mock HTTP post that simulates LLM behavior
          mock-post (fn [_url {:keys [body]} success-callback _error-callback]
                      (let [parsed-body (clojure.data.json/read-str body)
                            messages (get parsed-body "messages")
                            attempt-num (count @attempts)]
                        (swap! attempts conj messages)

                        (cond
                          ;; First attempt: return malformed EDN (unbalanced braces)
                          (= attempt-num 0)
                          (success-callback {:body (clojure.data.json/write-str
                                                    {"choices" [{"message" {"content" "{\"id\" [\"a\" \"b\"] :bad"}}]})})

                          ;; Second attempt: return valid EDN
                          :else
                          (success-callback {:body (clojure.data.json/write-str
                                                    {"choices" [{"message" {"content" "{\"id\" [\"a\" \"b\"] \"data\" \"ok\"}"}}]})}))))

          result (promise)]

      ;; Temporarily replace HTTP post and API key
      (with-redefs [clj-http.client/post mock-post
                    llm-dispatch/openrouter-api-key (fn [] "mock-key")]
        (llm/call
         "test" "model"
         [{"role" "user" "content" "test"}]
         (partial deliver result)
         {:max-retries 3}))

      ;; Wait for result
      (let [final-result (deref result 5000 ::timeout)]
        (is (not= final-result ::timeout) "Should complete")
        (is (= (count @attempts) 2) "Should make 2 attempts")

        ;; Second attempt should include error message
        (let [retry-messages (second @attempts)
              last-message (last retry-messages)
              content (get last-message "content")]
          (is (re-find #"could not unmarshal" content) "Error message should mention unmarshaling")
          (is (re-find #"EDN" content) "Error message should mention EDN"))

        ;; Should eventually succeed
        (is (= (get final-result "id") ["a" "b"]) "Should parse valid EDN on retry")))))

(deftest edn-parse-max-retries-test
  (testing "EDN parse gives up after max retries"
    (let [;; Track attempts
          attempt-count (atom 0)
          ;; Use promise for coordination instead of Thread/sleep
          error-promise (promise)

          ;; Mock HTTP post that always returns bad EDN
          mock-post (fn [_url _opts success-callback _error-callback]
                      (swap! attempt-count inc)
                      (success-callback {:body (clojure.data.json/write-str
                                                {"choices" [{"message" {"content" "{:bad edn {"}}]})}))]

      (with-redefs [clj-http.client/post mock-post
                    llm-dispatch/openrouter-api-key (fn [] "mock-key")]
        (llm/call
         "test" "model"
         [{"role" "user" "content" "test"}]
         (fn [_] nil) ; success handler (won't be called)
         {:max-retries 2
          :error (fn [_] (deliver error-promise true))}))

      ;; Wait for error handler with timeout
      (let [error-called (deref error-promise 5000 :timeout)]
        (is (not= error-called :timeout) "Error handler should be called within timeout")
        (is (= @attempt-count 3) "Should try initial + 2 retries")))))
