(ns ^:python-required claij.stt.handler-test
  "Integration tests for STT handler."
  (:require [clojure.test :refer [deftest is testing]]
            [claij.stt.core :as stt]
            [claij.stt.handler :as handler]
            [clojure.data.json :as json]))

;; Mock backend for testing
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

(deftest test-not-found-handler
  (testing "not found handler returns 404"
    (let [request {:uri "/unknown" :request-method :get}
          app (create-test-app)
          response (app request)]

      (testing "returns 404 status"
        (is (= 404 (:status response))))

      (testing "returns JSON error"
        (let [body (json/read-str (:body response) :key-fn keyword)]
          (is (= "Not found" (:error body))))))))

(deftest test-app-routing
  (testing "app routes to correct handlers"
    (let [app (create-test-app)]
      (testing "routes to health handler"
        (let [request {:uri "/health" :request-method :get}
              response (app request)]
          (is (= 200 (:status response)))))

      (testing "routes to not-found for unknown paths"
        (let [request {:uri "/unknown" :request-method :get}
              response (app request)]
          (is (= 404 (:status response)))))

      (testing "routes to not-found for wrong method"
        (let [request {:uri "/transcribe" :request-method :get}
              response (app request)]
          (is (= 404 (:status response))))))))

;; Note: transcribe-handler tests with actual Whisper backend would require
;; Python environment with Whisper model loaded. Those tests should be in
;; a separate integration test suite with appropriate setup/teardown.
