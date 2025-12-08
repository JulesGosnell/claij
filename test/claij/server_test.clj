(ns claij.server-test
  "Unit tests for claij.server handlers and utilities."
  (:require
   [clojure.test :refer [deftest testing is]]
   [claij.server :refer [string->url separator pattern initial-summary
                         fsms llms health-handler list-fsms-handler
                         fsm-document-handler fsm-graph-dot-handler]])
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
        (is (= 404 (:status response)))))))
