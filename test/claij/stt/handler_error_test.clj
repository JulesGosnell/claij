(ns claij.stt.handler-error-test
  "Unit tests for error handling in STT handlers (no Python required)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [claij.stt.core :as stt]
            [claij.stt.handler :as handler]))

;; Mock backend for testing (no Python required)
(defrecord MockBackend []
  stt/STTBackend
  (initialize! [this] this)
  (transcribe [_this _audio-data]
    {:text "mock transcription"
     :language "en"})
  (health-check [_this]
    {:healthy? true
     :backend-type :mock
     :details {:initialized? true}})
  (backend-info [_this]
    {:backend-type :mock
     :version "test"}))

(defn- create-mock-backend []
  (->MockBackend))

(defn- create-test-app []
  (handler/create-app (create-mock-backend) {}))

(deftest test-not-found-handler
  (testing "404 handler returns correct response"
    (let [request {:uri "/nonexistent" :request-method :get}
          app (create-test-app)
          response (app request)]
      (is (= 404 (:status response))
          "Should return 404 status")
      (is (= "application/json" (get-in response [:headers "Content-Type"]))
          "Should return JSON content type")
      (let [body (json/read-str (:body response))]
        (is (= "Not found" (get body "error"))
            "Should return error message")))))

(deftest test-health-handler
  (testing "Health check returns correct response with backend info"
    (let [request {:uri "/health" :request-method :get}
          app (create-test-app)
          response (app request)]
      (is (= 200 (:status response))
          "Should return 200 status")
      (is (= "application/json" (get-in response [:headers "Content-Type"]))
          "Should return JSON content type")
      (let [body (json/read-str (:body response))]
        (is (get body "healthy?")
            "Should return healthy status")
        (is (= "mock" (get body "backend-type"))
            "Should return backend type")))))

(deftest test-app-routing-not-found
  (testing "App returns 404 for unknown routes"
    (let [request {:uri "/unknown" :request-method :get}
          app (create-test-app)
          response (app request)]
      (is (= 404 (:status response))
          "Should return 404 for unknown route"))))

(deftest test-app-routing-wrong-method
  (testing "App returns 404 for correct path but wrong method"
    (let [request {:uri "/transcribe" :request-method :get}
          app (create-test-app)
          response (app request)]
      (is (= 404 (:status response))
          "Should return 404 for GET on /transcribe"))))

(deftest test-app-routing-health-check
  (testing "App routes health check correctly"
    (let [request {:uri "/health" :request-method :get}
          app (create-test-app)
          response (app request)]
      (is (= 200 (:status response))
          "Should return 200 for health check"))))

(deftest test-transcribe-handler-missing-audio-part
  (testing "Transcribe handler fails gracefully with missing audio part"
    (let [request {:uri "/transcribe"
                   :request-method :post
                   :multipart-params {}}
          app (create-test-app)
          response (app request)]
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
          app (create-test-app)
          response (app request)]
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
          app (create-test-app)
          response (app request)]
      (is (= 500 (:status response))
          "Should return 500 for nil audio")
      (let [body (json/read-str (:body response))]
        (is (contains? body "error")
            "Response should contain error key")))))
