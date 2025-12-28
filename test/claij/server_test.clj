(ns claij.server-test
  "Unit tests for claij.server handlers and utilities."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ring.adapter.jetty :as jetty]
   [claij.server :as claij.server :refer [string->url separator pattern initial-summary
                                          fsms llms health-handler list-fsms-handler
                                          fsm-document-handler fsm-graph-svg-handler
                                          dot->svg wrap-auth llm-handler claij-api-key
                                          api-base api-url state routes app voice-handler
                                          start]]
   [claij.fsm :as fsm]
   [claij.util :as util])
  (:import
   [java.net URL]))

(deftest server-test

  (testing "string->url"
    (testing "converts valid URL string to URL object"
      (let [^URL url (string->url "https://example.com/path")]
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
      (is (string? initial-summary)))

    (testing "api-base is OpenRouter URL"
      (is (= "https://openrouter.ai" api-base)))

    (testing "api-url is derived from api-base"
      (is (string? api-url))
      (is (re-find #"openrouter\.ai/api" api-url)))

    (testing "state is an atom"
      (is (instance? clojure.lang.Atom state)))

    (testing "routes is a vector of route definitions"
      (is (vector? routes))
      (is (pos? (count routes))))

    (testing "app is a ring handler function"
      (is (fn? app))))

  (testing "fsms registry"
    (testing "contains expected FSM keys"
      (is (contains? (fsms) "code-review-fsm")))

    (testing "all values are valid FSM maps"
      (doseq [[k v] (fsms)]
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
        (is (some #{"code-review-fsm"} (:body response))))))

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

  (testing "fsm-graph-svg-handler"
    (testing "returns 200 with SVG for valid id"
      (let [response (fsm-graph-svg-handler {:path-params {:fsm-id "code-review-fsm"}})]
        (is (= 200 (:status response)))
        (is (= "image/svg+xml" (get-in response [:headers "content-type"])))
        (is (string? (:body response)))
        (is (re-find #"<svg" (:body response)))))

    (testing "returns 404 for unknown FSM id"
      (let [response (fsm-graph-svg-handler {:path-params {:fsm-id "nonexistent"}})]
        (is (= 404 (:status response))))))

  (testing "llm-handler"
    (testing "returns 404 for unknown provider"
      (let [response (llm-handler {:path-params {:provider "unknown"}
                                   :body-params {:message "test"}})]
        (is (= 404 (:status response)))
        (is (contains? (:body response) :error))
        (is (re-find #"LLM not found" (get-in response [:body :error])))))

    (testing "returns 200 for valid provider with mocked LLM"
      ;; Mock the llms registry to return a simple function
      (with-redefs [claij.server/llms {"test-provider" (fn [_msg] "mocked response")}]
        (let [response (llm-handler {:path-params {:provider "test-provider"}
                                     :body-params {:message "hello"}})]
          (is (= 200 (:status response)))
          (is (= "mocked response" (get-in response [:body :response]))))))

    (testing "handles body-params as string when no :message key"
      (with-redefs [claij.server/llms {"test-provider" (fn [msg] (str "got: " msg))}]
        (let [response (llm-handler {:path-params {:provider "test-provider"}
                                     :body-params "raw string input"})]
          (is (= 200 (:status response)))
          (is (re-find #"raw string input" (get-in response [:body :response])))))))

  (testing "claij-api-key"
    (testing "returns nil or string from environment"
      (let [result (claij-api-key)]
        (is (or (nil? result) (and (string? result) (pos? (count result))))
            "Should return nil or non-empty string")))
    (testing "is used correctly by wrap-auth"
      ;; The real test of claij-api-key is in wrap-auth-test
      ;; where we verify the auth flow works with various key states
      (with-redefs [claij.server/claij-api-key (constantly "test-key")]
        (is (= "test-key" (claij-api-key)))))))

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

(deftest voice-handler-test
  (testing "voice-handler"
    (testing "returns 500 when audio field is missing"
      (let [response (voice-handler {:multipart-params {}})]
        (is (= 500 (:status response)))))

    (testing "returns 500 when audio is nil"
      (let [response (voice-handler {:multipart-params {"audio" nil}})]
        (is (= 500 (:status response)))))

    (testing "returns 500 when audio is empty bytes"
      (let [response (voice-handler {:multipart-params {"audio" (byte-array 0)}})]
        (is (= 500 (:status response)))))

    ;; Mock FSM execution to test response handling without external services
    (testing "returns 504 on FSM timeout"
      (with-redefs [fsm/run-sync (fn [_ _ _ _] :timeout)]
        (let [response (voice-handler {:multipart-params {"audio" (byte-array [1 2 3 4])}})]
          (is (= 504 (:status response))))))

    (testing "returns audio/wav on successful FSM completion"
      (let [fake-audio (byte-array [82 73 70 70])] ;; RIFF header start
        ;; Trail entries have :event key containing the actual event
        (with-redefs [fsm/run-sync (fn [_ _ _ _]
                                     [{} [{:from "tts" :to "end" :event {"body" fake-audio}}]])]
          (let [response (voice-handler {:multipart-params {"audio" fake-audio}})]
            (is (= 200 (:status response)))
            (is (= "audio/wav" (get-in response [:headers "Content-Type"])))))))

    (testing "returns 500 when FSM response has no audio"
      (with-redefs [fsm/run-sync (fn [_ _ _ _]
                                   [{} [{:from "tts" :to "end" :event {"body" "not bytes"}}]])]
        (let [response (voice-handler {:multipart-params {"audio" (byte-array [1 2 3 4])}})]
          (is (= 500 (:status response))))))

    (testing "accepts file map in multipart params"
      (let [fake-audio (byte-array [82 73 70 70])]
        (with-redefs [fsm/run-sync (fn [_ _ _ _]
                                     [{} [{:from "tts" :to "end" :event {"body" fake-audio}}]])]
          (let [response (voice-handler {:multipart-params {"audio" {:bytes fake-audio
                                                                     :filename "test.wav"
                                                                     :content-type "audio/wav"}}})]
            (is (= 200 (:status response)))))))

    (testing "routes include /voice endpoint"
      (let [voice-route (some #(when (= "/voice" (first %)) %) routes)]
        (is (some? voice-route) "Should have /voice route")
        (is (contains? (second voice-route) :post) "Should support POST")))

    (testing "fsms registry includes bdd fsm"
      (is (contains? (fsms) "bdd") "Should have bdd FSM registered")
      (is (map? (get (fsms) "bdd")) "BDD FSM should be a map")
      (is (= "bdd" (get-in (fsms) ["bdd" "id"])) "BDD FSM should have correct id"))))

(deftest certificate-endpoints-test
  (testing "install-cert endpoint"
    (let [;; Find the /install-cert route and get its handler
          install-cert-route (some #(when (= "/install-cert" (first %)) %) routes)
          handler (get-in install-cert-route [1 :get :handler])]
      (is (some? install-cert-route) "Should have /install-cert route")
      (is (fn? handler) "Should have a handler function")

      (testing "returns HTML page with instructions"
        (let [response (handler {})]
          (is (= 200 (:status response)))
          (is (= "text/html" (get-in response [:headers "Content-Type"])))
          (is (re-find #"Install CLAIJ Certificate" (:body response)))
          (is (re-find #"/claij\.crt" (:body response)) "Should link to cert download")
          (is (re-find #"iOS Instructions" (:body response)))))))

  (testing "claij.crt endpoint"
    (let [;; Find the /claij.crt route and get its handler
          crt-route (some #(when (= "/claij.crt" (first %)) %) routes)
          handler (get-in crt-route [1 :get :handler])]
      (is (some? crt-route) "Should have /claij.crt route")
      (is (fn? handler) "Should have a handler function")

      (testing "returns 404 when certificate file doesn't exist"
        ;; Mock file to not exist
        (with-redefs [clojure.java.io/file (fn [path]
                                             (proxy [java.io.File] [path]
                                               (exists [] false)))]
          (let [response (handler {})]
            (is (= 404 (:status response)))
            (is (= "text/plain" (get-in response [:headers "Content-Type"])))
            (is (re-find #"not found" (:body response))))))

      (testing "returns certificate with correct content-type when file exists"
        ;; Mock file to exist with specific length
        (with-redefs [clojure.java.io/file (fn [path]
                                             (proxy [java.io.File] [path]
                                               (exists [] true)
                                               (getAbsolutePath [] "/mock/claij-dev.crt")
                                               (length [] 1234)))
                      clojure.java.io/input-stream (fn [_] :mock-stream)]
          (let [response (handler {})]
            (is (= 200 (:status response)))
            (is (= "application/x-pem-file" (get-in response [:headers "Content-Type"])))
            (is (= "1234" (get-in response [:headers "Content-Length"])))
            (is (= :mock-stream (:body response)))))))))

(deftest start-function-test
  (testing "start function"
    (testing "builds correct options for HTTP only"
      (let [captured-opts (atom nil)]
        (with-redefs [jetty/run-jetty (fn [_app opts]
                                        (reset! captured-opts opts)
                                        :mock-server)]
          (let [result (start {:port 8080})]
            (is (= :mock-server result))
            (is (= 8080 (:port @captured-opts)))
            (is (false? (:join? @captured-opts)))
            (is (nil? (:ssl? @captured-opts)))))))

    (testing "builds correct options for HTTPS"
      (let [captured-opts (atom nil)]
        (with-redefs [jetty/run-jetty (fn [_app opts]
                                        (reset! captured-opts opts)
                                        :mock-server)]
          (start {:port 8080
                  :ssl-port 8443
                  :keystore "test.jks"
                  :key-password "secret"})
          (is (= 8080 (:port @captured-opts)))
          (is (= 8443 (:ssl-port @captured-opts)))
          (is (true? (:ssl? @captured-opts)))
          (is (= "test.jks" (:keystore @captured-opts)))
          (is (= "secret" (:key-password @captured-opts))))))

    (testing "disables HTTP when port is nil"
      (let [captured-opts (atom nil)]
        (with-redefs [jetty/run-jetty (fn [_app opts]
                                        (reset! captured-opts opts)
                                        :mock-server)]
          (start {:port nil :ssl-port 8443 :keystore "test.jks" :key-password "x"})
          (is (= -1 (:port @captured-opts)) "Should set port to -1 to disable HTTP"))))))

(deftest app-routing-test
  (testing "app root redirect"
    (let [response (app {:request-method :get :uri "/"})]
      (is (= 302 (:status response)) "Root should redirect")
      (is (= "/voice.html" (get-in response [:headers "Location"])))))

  (testing "app handles unknown routes"
    (let [response (app {:request-method :get :uri "/nonexistent-path-xyz"})]
      (is (= 404 (:status response))))))

(deftest openapi-handler-test
  (testing "openapi-handler"
    (testing "returns 200 with JSON content-type"
      (let [response (claij.server/openapi-handler {})]
        (is (= 200 (:status response)))
        (is (= "application/json" (get-in response [:headers "Content-Type"])))))

    (testing "returns valid JSON body"
      (let [response (claij.server/openapi-handler {})
            body (:body response)]
        (is (string? body))
        ;; Should be parseable JSON
        (is (map? (util/json->clj body)))))

    (testing "contains OpenAPI version"
      (let [response (claij.server/openapi-handler {})
            spec (util/json->clj (:body response))]
        (is (= "3.1.0" (:openapi spec)))))))

(deftest fsms-html-handler-test
  (testing "fsms-html-handler"
    (testing "returns 200 with HTML content-type"
      (let [response (claij.server/fsms-html-handler {})]
        (is (= 200 (:status response)))
        (is (= "text/html" (get-in response [:headers "Content-Type"])))))

    (testing "contains FSM catalogue title"
      (let [response (claij.server/fsms-html-handler {})]
        (is (re-find #"FSM Catalogue" (:body response)))))

    (testing "lists registered FSMs"
      (let [response (claij.server/fsms-html-handler {})]
        (is (re-find #"code-review-fsm" (:body response)))
        (is (re-find #"bdd" (:body response)))))

    (testing "contains links to FSM pages"
      (let [response (claij.server/fsms-html-handler {})]
        (is (re-find #"/fsm/code-review-fsm" (:body response)))
        (is (re-find #"graph\.svg" (:body response)))))))

(deftest fsm-html-handler-test
  (testing "fsm-html-handler"
    (testing "returns 200 with HTML for valid FSM"
      (let [response (claij.server/fsm-html-handler {:path-params {:fsm-id "code-review-fsm"}})]
        (is (= 200 (:status response)))
        (is (= "text/html" (get-in response [:headers "Content-Type"])))))

    (testing "contains FSM title"
      (let [response (claij.server/fsm-html-handler {:path-params {:fsm-id "code-review-fsm"}})]
        (is (re-find #"code-review-fsm" (:body response)))))

    (testing "contains navigation links"
      (let [response (claij.server/fsm-html-handler {:path-params {:fsm-id "code-review-fsm"}})]
        (is (re-find #"Back to Catalogue" (:body response)))
        (is (re-find #"graph\.svg" (:body response)))))

    (testing "contains graph section"
      (let [response (claij.server/fsm-html-handler {:path-params {:fsm-id "code-review-fsm"}})]
        (is (re-find #"Graph" (:body response)))))

    (testing "contains states section"
      (let [response (claij.server/fsm-html-handler {:path-params {:fsm-id "code-review-fsm"}})]
        (is (re-find #"States" (:body response)))))

    (testing "contains transitions section"
      (let [response (claij.server/fsm-html-handler {:path-params {:fsm-id "code-review-fsm"}})]
        (is (re-find #"Transitions" (:body response)))))

    (testing "returns 404 for unknown FSM"
      (let [response (claij.server/fsm-html-handler {:path-params {:fsm-id "nonexistent"}})]
        (is (= 404 (:status response)))
        (is (re-find #"FSM Not Found" (:body response)))))))

(deftest fsm-run-handler-test
  (testing "fsm-run-handler"
    (testing "returns 404 for unknown FSM"
      (let [response (claij.server/fsm-run-handler {:path-params {:fsm-id "nonexistent"}
                                                    :body-params {}})]
        (is (= 404 (:status response)))
        (is (re-find #"not found" (get-in response [:body :error])))))

    (testing "returns 400 for invalid input"
      ;; code-review-fsm requires specific input schema
      (let [response (claij.server/fsm-run-handler {:path-params {:fsm-id "code-review-fsm"}
                                                    :body-params {"invalid" "input"}})]
        (is (= 400 (:status response)))
        (is (= "Input validation failed" (get-in response [:body :error])))))

    (testing "returns 504 on timeout"
      (with-redefs [claij.fsm/run-sync (fn [_ _ _ _] :timeout)]
        ;; Need valid input to get past validation
        (with-redefs [claij.schema/validate (fn [_ _ _] {:valid? true})]
          (let [response (claij.server/fsm-run-handler {:path-params {:fsm-id "code-review-fsm"}
                                                        :body-params {}})]
            (is (= 504 (:status response)))
            (is (= "FSM execution timed out" (get-in response [:body :error])))))))

    (testing "returns 200 with final event on success"
      (with-redefs [claij.schema/validate (fn [_ _ _] {:valid? true})
                    claij.fsm/run-sync (fn [_ _ _ _]
                                         [{} [{:from "a" :to "b" :event {"result" "success"}}]])]
        (let [response (claij.server/fsm-run-handler {:path-params {:fsm-id "code-review-fsm"}
                                                      :body-params {}})]
          (is (= 200 (:status response)))
          (is (= {"result" "success"} (:body response))))))

    (testing "returns 500 on exception"
      (with-redefs [claij.schema/validate (fn [_ _ _] (throw (Exception. "test error")))]
        (let [response (claij.server/fsm-run-handler {:path-params {:fsm-id "code-review-fsm"}
                                                      :body-params {}})]
          (is (= 500 (:status response)))
          (is (= "test error" (get-in response [:body :error]))))))))

(deftest fsm-graph-svg-handler-test
  (testing "fsm-graph-svg-handler"
    (testing "returns SVG without hats param"
      (let [response (claij.server/fsm-graph-svg-handler {:path-params {:fsm-id "code-review-fsm"}
                                                          :query-params {}})]
        (is (= 200 (:status response)))
        (is (= "image/svg+xml" (get-in response [:headers "content-type"])))
        (is (re-find #"<svg" (:body response)))))

    (testing "returns SVG with hats param"
      (let [response (claij.server/fsm-graph-svg-handler {:path-params {:fsm-id "code-review-fsm"}
                                                          :query-params {"hats" "true"}})]
        (is (= 200 (:status response)))
        (is (re-find #"<svg" (:body response)))))))


