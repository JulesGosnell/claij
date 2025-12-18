(ns claij.stt.handler-test
  "Tests for STT handler - both unit tests (with mocks) and routing tests."
  (:require [clojure.test :refer [deftest is testing]]
            [claij.stt.core :as stt]
            [claij.stt.handler :as handler]
            [clojure.data.json :as json]))

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

(defn- create-test-app []
  (handler/create-app (->MockBackend) {}))

;;------------------------------------------------------------------------------
;; Health Handler Tests
;;------------------------------------------------------------------------------

(deftest test-health-handler
  (testing "health endpoint returns success"
    (let [request {:uri "/health" :request-method :get}
          app (create-test-app)
          response (app request)]

      (testing "returns 200 status"
        (is (= 200 (:status response))))

      (testing "returns JSON content type"
        (is (= "application/json" (get-in response [:headers "Content-Type"]))))

      (testing "returns healthy status with backend info"
        (let [body (json/read-str (:body response) :key-fn keyword)]
          (is (:healthy? body))
          (is (= "mock" (:backend-type body))))))))

;;------------------------------------------------------------------------------
;; Not Found Handler Tests
;;------------------------------------------------------------------------------

(deftest test-not-found-handler
  (testing "not found handler returns 404"
    (let [request {:uri "/unknown" :request-method :get}
          app (create-test-app)
          response (app request)]

      (testing "returns 404 status"
        (is (= 404 (:status response))))

      (testing "returns JSON content type"
        (is (= "application/json" (get-in response [:headers "Content-Type"]))))

      (testing "returns JSON error message"
        (let [body (json/read-str (:body response) :key-fn keyword)]
          (is (= "Not found" (:error body))))))))

;;------------------------------------------------------------------------------
;; Routing Tests
;;------------------------------------------------------------------------------

(deftest test-app-routing
  (testing "app routes to correct handlers"
    (let [app (create-test-app)]

      (testing "routes to health handler"
        (let [response (app {:uri "/health" :request-method :get})]
          (is (= 200 (:status response)))))

      (testing "routes to not-found for unknown paths"
        (let [response (app {:uri "/unknown" :request-method :get})]
          (is (= 404 (:status response)))))

      (testing "routes to not-found for wrong method on /transcribe"
        (let [response (app {:uri "/transcribe" :request-method :get})]
          (is (= 404 (:status response))))))))

(deftest test-openapi-endpoint
  (testing "GET /openapi.json returns valid OpenAPI spec"
    (let [app (create-test-app)
          response (app {:uri "/openapi.json" :request-method :get})
          body (json/read-str (:body response))]
      (is (= 200 (:status response)))
      (is (= "application/json" (get-in response [:headers "Content-Type"])))
      (is (= "3.0.0" (get body "openapi")))
      (is (= "CLAIJ STT Service" (get-in body ["info" "title"])))
      (is (contains? (get body "paths") "/transcribe"))
      (is (contains? (get body "paths") "/health")))))

;;------------------------------------------------------------------------------
;; Transcribe Handler Error Cases
;;------------------------------------------------------------------------------

(deftest test-transcribe-handler-missing-audio-part
  (testing "Transcribe handler fails gracefully with missing audio part"
    (let [request {:uri "/transcribe"
                   :request-method :post
                   :multipart-params {}}
          app (create-test-app)
          response (app request)]
      (is (= 500 (:status response)))
      (is (= "application/json" (get-in response [:headers "Content-Type"])))
      (let [body (json/read-str (:body response))]
        (is (contains? body "error"))))))

(deftest test-transcribe-handler-empty-audio-bytes
  (testing "Transcribe handler fails gracefully with empty audio bytes"
    (let [request {:uri "/transcribe"
                   :request-method :post
                   :multipart-params {"audio" {:filename "empty.wav"
                                               :bytes (byte-array 0)}}}
          app (create-test-app)
          response (app request)]
      (is (= 500 (:status response)))
      (let [body (json/read-str (:body response))]
        (is (contains? body "error"))))))

(deftest test-transcribe-handler-nil-audio-bytes
  (testing "Transcribe handler fails gracefully with nil audio bytes"
    (let [request {:uri "/transcribe"
                   :request-method :post
                   :multipart-params {"audio" {:filename "test.wav"
                                               :bytes nil}}}
          app (create-test-app)
          response (app request)]
      (is (= 500 (:status response)))
      (let [body (json/read-str (:body response))]
        (is (contains? body "error"))))))
