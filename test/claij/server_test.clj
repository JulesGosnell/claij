(ns claij.server-test
  "Unit tests for claij.server handlers and utilities."
  (:require
   [clojure.test :refer [deftest testing is]]
   [claij.server :refer [string->url separator pattern initial-summary
                         fsms llms health-handler list-fsms-handler
                         fsm-document-handler fsm-graph-dot-handler
                         fsm-graph-svg-handler dot->svg wrap-auth]])
  (:import
   [java.net URL]))

(deftest server-test

  (testing "string->url"
    (testing "converts valid URL string to URL object"
      (let [url (string->url "https://example.com/path")]
        (is (instance? URL url))
        (is (= "https" (.getProtocol url)))
        (is (= "example.com" (.getHost url)))
        (is (= "/path" (.getPath url)))))

    (testing "throws on invalid URL"
      (is (thrown? java.net.MalformedURLException
                   (string->url "not-a-url")))))

  (testing "constants"
    (testing "separator is a UUID-like string"
      (is (string? separator))
      (is (re-matches #"[0-9a-f-]+" separator)))

    (testing "pattern is compiled from separator"
      (is (instance? java.util.regex.Pattern pattern)))

    (testing "initial-summary is a string"
      (is (string? initial-summary))))

  (testing "fsms registry"
    (testing "contains expected FSM keys"
      (is (contains? fsms "code-review-fsm"))
      (is (contains? fsms "mcp-fsm")))

    (testing "all values are valid FSM maps"
      (doseq [[k v] fsms]
        (is (map? v) (str "FSM " k " should be a map"))
        (is (string? (get v "id")) (str "FSM " k " should have string id")))))

  (testing "llms registry"
    (testing "contains expected LLM keys"
      (is (contains? llms "grok"))
      (is (contains? llms "gpt"))
      (is (contains? llms "claude"))
      (is (contains? llms "gemini")))

    (testing "all values are functions"
      (doseq [[k v] llms]
        (is (fn? v) (str "LLM " k " should be a function")))))

  (testing "dot->svg"
    (testing "converts simple DOT to SVG"
      (let [dot "digraph G { A -> B; }"
            svg (dot->svg dot)]
        (is (string? svg))
        (is (re-find #"<svg" svg) "Output should contain SVG element")
        (is (re-find #"</svg>" svg) "Output should be complete SVG")))

    (testing "throws on invalid DOT syntax"
      (is (thrown? clojure.lang.ExceptionInfo
                   (dot->svg "not valid dot syntax {{{")))))

  (testing "health-handler"
    (testing "returns 200 with ok body"
      (let [response (health-handler {})]
        (is (= 200 (:status response)))
        (is (string? (:body response)))
        (is (re-find #"ok" (:body response))))))

  (testing "list-fsms-handler"
    (testing "returns 200 with list of FSM ids"
      (let [response (list-fsms-handler {})]
        (is (= 200 (:status response)))
        (is (vector? (:body response)))
        (is (every? string? (:body response)))
        (is (some #{"code-review-fsm"} (:body response)))
        (is (some #{"mcp-fsm"} (:body response))))))

  (testing "fsm-document-handler"
    (testing "returns 200 with FSM document for valid id"
      (let [response (fsm-document-handler {:path-params {:fsm-id "code-review-fsm"}})]
        (is (= 200 (:status response)))
        (is (map? (:body response)))
        (is (= "code-review" (get-in response [:body "id"])))))

    (testing "returns 404 for unknown FSM id"
      (let [response (fsm-document-handler {:path-params {:fsm-id "nonexistent"}})]
        (is (= 404 (:status response)))
        (is (contains? (:body response) :error)))))

  (testing "fsm-graph-dot-handler"
    (testing "returns 200 with DOT source for valid id"
      (let [response (fsm-graph-dot-handler {:path-params {:fsm-id "code-review-fsm"}})]
        (is (= 200 (:status response)))
        (is (string? (:body response)))
        (is (re-find #"digraph" (:body response)))))

    (testing "returns 404 for unknown FSM id"
      (let [response (fsm-graph-dot-handler {:path-params {:fsm-id "nonexistent"}})]
        (is (= 404 (:status response))))))

  (testing "fsm-graph-svg-handler"
    (testing "returns 200 with SVG for valid id"
      (let [response (fsm-graph-svg-handler {:path-params {:fsm-id "code-review-fsm"}})]
        (is (= 200 (:status response)))
        (is (= "image/svg+xml" (get-in response [:headers "content-type"])))
        (is (string? (:body response)))
        (is (re-find #"<svg" (:body response)))))

    (testing "returns 404 for unknown FSM id"
      (let [response (fsm-graph-svg-handler {:path-params {:fsm-id "nonexistent"}})]
        (is (= 404 (:status response)))))))

(deftest wrap-auth-test
  (testing "wrap-auth middleware"
    (let [mock-handler (fn [_req] {:status 200 :body "success"})]

      (testing "allows requests when no API key configured"
        ;; When CLAIJ_API_KEY is blank/nil, all requests pass through
        (with-redefs [claij.server/claij-api-key (constantly nil)]
          (let [wrapped (wrap-auth mock-handler)
                response (wrapped {:headers {}})]
            (is (= 200 (:status response)))
            (is (= "success" (:body response))))))

      (testing "allows requests when no API key configured (empty string)"
        (with-redefs [claij.server/claij-api-key (constantly "")]
          (let [wrapped (wrap-auth mock-handler)
                response (wrapped {:headers {}})]
            (is (= 200 (:status response))))))

      (testing "allows requests with valid Bearer token"
        (with-redefs [claij.server/claij-api-key (constantly "secret-key")]
          (let [wrapped (wrap-auth mock-handler)
                response (wrapped {:headers {"authorization" "Bearer secret-key"}})]
            (is (= 200 (:status response)))
            (is (= "success" (:body response))))))

      (testing "rejects requests with missing authorization header"
        (with-redefs [claij.server/claij-api-key (constantly "secret-key")]
          (let [wrapped (wrap-auth mock-handler)
                response (wrapped {:headers {}})]
            (is (= 401 (:status response)))
            (is (= "Unauthorized" (get-in response [:body :error]))))))

      (testing "rejects requests with invalid token"
        (with-redefs [claij.server/claij-api-key (constantly "secret-key")]
          (let [wrapped (wrap-auth mock-handler)
                response (wrapped {:headers {"authorization" "Bearer wrong-key"}})]
            (is (= 401 (:status response)))
            (is (= "Unauthorized" (get-in response [:body :error]))))))

      (testing "rejects requests with malformed authorization header"
        (with-redefs [claij.server/claij-api-key (constantly "secret-key")]
          (let [wrapped (wrap-auth mock-handler)
                response (wrapped {:headers {"authorization" "Basic abc123"}})]
            (is (= 401 (:status response))))))

      (testing "returns WWW-Authenticate header on 401"
        (with-redefs [claij.server/claij-api-key (constantly "secret-key")]
          (let [wrapped (wrap-auth mock-handler)
                response (wrapped {:headers {}})]
            (is (= "Bearer realm=\"claij\""
                   (get-in response [:headers "WWW-Authenticate"])))))))))
