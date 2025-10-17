(ns claij.whisper.handler-error-test
  "Unit tests for error handling in Whisper handlers (no Python required)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [claij.whisper.handler :as handler]))

(deftest test-not-found-handler
  (testing "404 handler returns correct response"
    (let [request {:uri "/nonexistent" :request-method :get}
          response (handler/not-found-handler request)]
      (is (= 404 (:status response))
          "Should return 404 status")
      (is (= "application/json" (get-in response [:headers "Content-Type"]))
          "Should return JSON content type")
      (let [body (json/read-str (:body response))]
        (is (= "Not found" (get body "error"))
            "Should return error message")))))

(deftest test-health-handler
  (testing "Health check returns correct response"
    (let [request {:uri "/health" :request-method :get}
          response (handler/health-handler request)]
      (is (= 200 (:status response))
          "Should return 200 status")
      (is (= "application/json" (get-in response [:headers "Content-Type"]))
          "Should return JSON content type")
      (let [body (json/read-str (:body response))]
        (is (= "healthy" (get body "status"))
            "Should return healthy status")
        (is (= "whisper-clojure" (get body "service"))
            "Should return service name")))))

(deftest test-app-routing-not-found
  (testing "App returns 404 for unknown routes"
    (let [request {:uri "/unknown" :request-method :get}
          response (handler/app request)]
      (is (= 404 (:status response))
          "Should return 404 for unknown route"))))

(deftest test-app-routing-wrong-method
  (testing "App returns 404 for correct path but wrong method"
    (let [request {:uri "/transcribe" :request-method :get}
          response (handler/app request)]
      (is (= 404 (:status response))
          "Should return 404 for GET on /transcribe"))))

(deftest test-app-routing-health-check
  (testing "App routes health check correctly"
    (let [request {:uri "/health" :request-method :get}
          response (handler/app request)]
      (is (= 200 (:status response))
          "Should return 200 for health check"))))

(deftest test-transcribe-handler-missing-audio-part
  (testing "Transcribe handler fails gracefully with missing audio part"
    (let [request {:uri "/transcribe"
                   :request-method :post
                   :multipart-params {}}
          response (handler/transcribe-handler request)]
      (is (= 500 (:status response))
          "Should return 500 for missing audio part")
      (is (= "application/json" (get-in response [:headers "Content-Type"]))
          "Should return JSON content type")
      (let [body (json/read-str (:body response))]
        (is (contains? body "error")
            "Response should contain error key")))))

(deftest test-transcribe-handler-empty-audio-bytes
  (testing "Transcribe handler fails gracefully with empty audio bytes"
    (let [request {:uri "/transcribe"
                   :request-method :post
                   :multipart-params {"audio" {:filename "empty.wav"
                                               :bytes (byte-array 0)}}}
          response (handler/transcribe-handler request)]
      (is (= 500 (:status response))
          "Should return 500 for empty audio")
      (let [body (json/read-str (:body response))]
        (is (contains? body "error")
            "Response should contain error key")))))

(deftest test-transcribe-handler-nil-audio-bytes
  (testing "Transcribe handler fails gracefully with nil audio bytes"
    (let [request {:uri "/transcribe"
                   :request-method :post
                   :multipart-params {"audio" {:filename "test.wav"
                                               :bytes nil}}}
          response (handler/transcribe-handler request)]
      (is (= 500 (:status response))
          "Should return 500 for nil audio")
      (let [body (json/read-str (:body response))]
        (is (contains? body "error")
            "Response should contain error key")))))
