(ns claij.stt.core-test
  "Tests for STT protocol and utilities."
  (:require
   [clojure.test :refer [deftest testing is]]
   [claij.stt.core :as stt]))

;; Mock backend implementation for testing
(defrecord MockSTTBackend [initialized? transcription health-result info]
  stt/STTBackend
  (initialize! [this]
    (reset! initialized? true)
    this)
  (transcribe [_this _audio-data]
    @transcription)
  (health-check [_this]
    @health-result)
  (backend-info [_this]
    @info))

(defn make-mock-backend
  "Create a mock backend with configurable responses."
  [{:keys [transcription health info]
    :or {transcription {:text "hello world" :language "en"}
         health {:healthy? true :backend-type :mock}
         info {:backend-type :mock :version "1.0.0"}}}]
  (->MockSTTBackend (atom false)
                    (atom transcription)
                    (atom health)
                    (atom info)))

(deftest stt-protocol-test
  (testing "initialize! sets initialized flag"
    (let [backend (make-mock-backend {})]
      (is (false? @(:initialized? backend)))
      (stt/initialize! backend)
      (is (true? @(:initialized? backend)))))

  (testing "transcribe returns transcription result"
    (let [backend (make-mock-backend {:transcription {:text "test audio" :language "en"}})]
      (let [result (stt/transcribe backend (byte-array [1 2 3]))]
        (is (= "test audio" (:text result)))
        (is (= "en" (:language result))))))

  (testing "health-check returns health status"
    (let [backend (make-mock-backend {:health {:healthy? true :backend-type :mock :details {:gpu "available"}}})]
      (let [result (stt/health-check backend)]
        (is (:healthy? result))
        (is (= :mock (:backend-type result))))))

  (testing "health-check can return unhealthy status"
    (let [backend (make-mock-backend {:health {:healthy? false :error "Model not loaded"}})]
      (let [result (stt/health-check backend)]
        (is (false? (:healthy? result)))
        (is (= "Model not loaded" (:error result))))))

  (testing "backend-info returns configuration"
    (let [backend (make-mock-backend {:info {:backend-type :whisper :model "small" :device "cpu"}})]
      (let [result (stt/backend-info backend)]
        (is (= :whisper (:backend-type result)))
        (is (= "small" (:model result)))))))

(deftest transcribe-audio-test
  (testing "transcribe-audio initializes backend before transcribing"
    (let [backend (make-mock-backend {:transcription {:text "initialized and transcribed"}})]
      (is (false? @(:initialized? backend)))
      (let [result (stt/transcribe-audio backend (byte-array [1 2 3]))]
        (is (true? @(:initialized? backend)))
        (is (= "initialized and transcribed" (:text result))))))

  (testing "transcribe-audio works with already initialized backend"
    (let [backend (make-mock-backend {:transcription {:text "already ready"}})]
      (stt/initialize! backend)
      (is (true? @(:initialized? backend)))
      (let [result (stt/transcribe-audio backend (byte-array [4 5 6]))]
        (is (= "already ready" (:text result)))))))
