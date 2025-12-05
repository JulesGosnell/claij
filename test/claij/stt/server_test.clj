(ns ^:integration claij.stt.server-test
  "Integration tests for STT server.
   Run with: clojure -M:test --focus :integration"
  (:require [clojure.test :refer [deftest is testing]]
            [claij.stt.core :as stt]
            [claij.stt.server :as server]))

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

(deftest test-create-app
  (testing "create-app returns a function"
    (let [backend (->MockBackend)
          app (#'server/create-app backend {})]
      (is (fn? app)))))

(deftest test-wrap-logging
  (testing "wrap-logging middleware passes through response"
    (let [test-handler (fn [req] {:status 200 :body "test"})
          wrapped (#'server/wrap-logging test-handler)
          request {:request-method :get :uri "/test"}
          response (wrapped request)]

      (is (= 200 (:status response)))
      (is (= "test" (:body response))))))
