(ns ^:python-required claij.whisper.handler-test
  "Tests for whisper handler that don't require Python environment."
  (:require [clojure.test :refer [deftest is testing]]
            [claij.whisper.handler :as handler]
            [clojure.data.json :as json]))

(deftest test-health-handler
  (testing "health endpoint returns success"
    (let [request {:uri "/health" :request-method :get}
          response (handler/health-handler request)]

      (testing "returns 200 status"
        (is (= 200 (:status response))))

      (testing "returns JSON content type"
        (is (= "application/json" (get-in response [:headers "Content-Type"]))))

      (testing "returns healthy status"
        (let [body (json/read-str (:body response) :key-fn keyword)]
          (is (= "healthy" (:status body)))
          (is (= "whisper-clojure" (:service body))))))))

(deftest test-not-found-handler
  (testing "not found handler returns 404"
    (let [request {:uri "/unknown" :request-method :get}
          response (handler/not-found-handler request)]

      (testing "returns 404 status"
        (is (= 404 (:status response))))

      (testing "returns JSON error"
        (let [body (json/read-str (:body response) :key-fn keyword)]
          (is (= "Not found" (:error body))))))))

(deftest test-app-routing
  (testing "app routes to correct handlers"
    (testing "routes to health handler"
      (let [request {:uri "/health" :request-method :get}
            response (handler/app request)]
        (is (= 200 (:status response)))))

    (testing "routes to not-found for unknown paths"
      (let [request {:uri "/unknown" :request-method :get}
            response (handler/app request)]
        (is (= 404 (:status response)))))

    (testing "routes to not-found for wrong method"
      (let [request {:uri "/transcribe" :request-method :get}
            response (handler/app request)]
        (is (= 404 (:status response)))))))

;; Note: transcribe-handler tests require Python environment with Whisper model loaded.
;; Byte extraction is tested in claij.whisper.multipart-test.
;; Full end-to-end transcription would require audio files and is better suited
;; for manual testing or dedicated integration test infrastructure.
