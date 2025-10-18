(ns claij.tts.core-test
  "Unit tests for TTS core protocol."
  (:require [clojure.test :refer [deftest is testing]]
            [claij.tts.core :as tts]))

;; Mock backend for testing
(defrecord TestBackend [initialized?-atom]
  tts/TTSBackend
  (initialize! [this]
    (reset! initialized?-atom true)
    this)
  (synthesize [_this text]
    {:audio-bytes (.getBytes text "UTF-8")
     :sample-rate 22050})
  (synthesize [_this text options]
    {:audio-bytes (.getBytes text "UTF-8")
     :sample-rate (:sample-rate options 22050)})
  (health-check [_this]
    {:healthy? @initialized?-atom
     :backend-type :test})
  (backend-info [_this]
    {:backend-type :test
     :initialized? @initialized?-atom}))

(deftest test-backend-initialization
  (testing "initialize! makes backend ready"
    (let [backend (->TestBackend (atom false))]
      (is (false? @(:initialized?-atom backend))
          "Should start uninitialized")

      (tts/initialize! backend)

      (is (true? @(:initialized?-atom backend))
          "Should be initialized after initialize!"))))

(deftest test-synthesize-basic
  (testing "synthesize converts text to audio"
    (let [backend (->TestBackend (atom true))
          text "Hello world"
          result (tts/synthesize backend text)]

      (is (map? result)
          "Should return a map")
      (is (contains? result :audio-bytes)
          "Should contain audio-bytes")
      (is (contains? result :sample-rate)
          "Should contain sample-rate")
      (is (bytes? (:audio-bytes result))
          "audio-bytes should be a byte array")
      (is (= 22050 (:sample-rate result))
          "Should have correct sample rate"))))

(deftest test-synthesize-with-options
  (testing "synthesize accepts options"
    (let [backend (->TestBackend (atom true))
          text "Hello"
          result (tts/synthesize backend text {:sample-rate 44100})]

      (is (= 44100 (:sample-rate result))
          "Should use custom sample rate from options"))))

(deftest test-health-check
  (testing "health-check returns status"
    (let [backend (->TestBackend (atom true))
          health (tts/health-check backend)]

      (is (map? health)
          "Should return a map")
      (is (contains? health :healthy?)
          "Should contain healthy? key")
      (is (contains? health :backend-type)
          "Should contain backend-type key")
      (is (:healthy? health)
          "Should be healthy when initialized"))))

(deftest test-backend-info
  (testing "backend-info returns configuration"
    (let [backend (->TestBackend (atom false))
          info (tts/backend-info backend)]

      (is (map? info)
          "Should return a map")
      (is (contains? info :backend-type)
          "Should contain backend-type")
      (is (= :test (:backend-type info))
          "Should have correct backend type"))))

(deftest test-synthesize-text-convenience
  (testing "synthesize-text ensures initialization"
    (let [backend (->TestBackend (atom false))]
      (is (false? @(:initialized?-atom backend))
          "Should start uninitialized")

      ;; synthesize-text should initialize automatically
      (tts/synthesize-text backend "Hello")

      (is (true? @(:initialized?-atom backend))
          "Should be initialized after synthesize-text"))))

(deftest test-synthesize-text-with-options
  (testing "synthesize-text accepts options"
    (let [backend (->TestBackend (atom true))
          result (tts/synthesize-text backend "Hello" {:sample-rate 48000})]

      (is (= 48000 (:sample-rate result))
          "Should pass options through to synthesize"))))
