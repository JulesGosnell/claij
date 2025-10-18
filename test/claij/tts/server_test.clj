(ns claij.tts.server-test
  "Unit tests for TTS server."
  (:require [clojure.test :refer [deftest is testing]]
            [claij.tts.core :as tts]
            [claij.tts.server :as server]))

;; Mock backend for testing
(defrecord MockBackend []
  tts/TTSBackend
  (initialize! [this] this)
  (synthesize [_this text]
    {:audio-bytes (byte-array 100)
     :sample-rate 22050})
  (synthesize [_this text _options]
    {:audio-bytes (byte-array 100)
     :sample-rate 22050})
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
          app (#'server/create-app backend)]
      (is (fn? app)))))

(deftest test-wrap-logging
  (testing "wrap-logging middleware passes through response"
    (let [test-handler (fn [req] {:status 200 :body "test"})
          wrapped (#'server/wrap-logging test-handler)
          request {:request-method :get :uri "/test"}
          response (wrapped request)]

      (is (= 200 (:status response)))
      (is (= "test" (:body response))))))

;; Note: start-server! and stop-server! tests with actual Piper backend
;; require Python environment with piper-tts and voice models.
;; These are integration tests that should run with: clojure -M:piper:test
