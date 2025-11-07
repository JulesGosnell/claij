(ns claij.retry-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.tools.logging :as log]
   [claij.llm.open-router :as llm]))

(deftest json-parse-retry-mock-test
  (testing "JSON parse errors trigger retries with error feedback"
    (let [;; Track attempts and prompts
          attempts (atom [])

          ;; Mock HTTP post that simulates LLM behavior
          mock-post (fn [_url {:keys [body]} success-callback _error-callback]
                      (let [parsed-body (clojure.data.json/read-str body)
                            messages (get parsed-body "messages")
                            attempt-num (count @attempts)]
                        (swap! attempts conj messages)

                        (cond
                          ;; First attempt: return malformed JSON
                          (= attempt-num 0)
                          (success-callback {:body (clojure.data.json/write-str
                                                    {"choices" [{"message" {"content" "[{\"$ref\":\"#/$defs/foo\"}, {\"bad\": json"}}]})})

                          ;; Second attempt: return valid JSON
                          :else
                          (success-callback {:body (clojure.data.json/write-str
                                                    {"choices" [{"message" {"content" "{\"id\": [\"a\", \"b\"], \"data\": \"ok\"}"}}]})}))))

          result (promise)]

      ;; Temporarily replace the HTTP post function and headers
      (with-redefs [clj-http.client/post mock-post
                    llm/headers (fn [] {"Authorization" "Bearer mock-key"})]
        (llm/open-router-async
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
          (is (re-find #"clojure\.data\.json" content) "Error message should mention json parser"))

        ;; Should eventually succeed
        (is (= (get final-result "id") ["a" "b"]) "Should parse valid JSON on retry")))))

(deftest json-parse-max-retries-test
  (testing "JSON parse gives up after max retries"
    (let [;; Track attempts
          attempt-count (atom 0)

          ;; Mock HTTP post that always returns bad JSON
          mock-post (fn [_url _opts success-callback _error-callback]
                      (swap! attempt-count inc)
                      (success-callback {:body (clojure.data.json/write-str
                                                {"choices" [{"message" {"content" "bad json {"}}]})}))

          result (promise)
          error-called (atom false)]

      (with-redefs [clj-http.client/post mock-post
                    llm/headers (fn [] {"Authorization" "Bearer mock-key"})]
        (llm/open-router-async
         "test" "model"
         [{"role" "user" "content" "test"}]
         (partial deliver result)
         {:max-retries 2
          :error (fn [_] (reset! error-called true))}))

      ;; Give it time to exhaust retries
      (Thread/sleep 2000)

      ;; Should have tried 3 times (initial + 2 retries)
      (is (= @attempt-count 3) "Should try initial + 2 retries")

      ;; Error handler should be called
      (is @error-called "Error handler should be called after max retries"))))