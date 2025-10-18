(ns ^:integration claij.stt.whisper.audio-test
  "Integration tests for audio conversion that require Python environment.
   These tests need: numpy, soundfile, and libpython-clj.
   Run with: clojure -M:whisper:test --focus integration"
  (:require [clojure.test :refer [deftest is testing]]
            [claij.stt.whisper.audio :as audio]
            [claij.stt.whisper.multipart :as multipart]
            [claij.stt.whisper.python :as whisper]
            [claij.stt.core :as stt])
  (:import [javax.sound.sampled AudioFormat AudioSystem AudioFileFormat$Type AudioInputStream]
           [java.io ByteArrayOutputStream ByteArrayInputStream]))

;;; Dynamic Vars for Test Context

(def ^:dynamic *whisper-backend* nil)
(def ^:dynamic *module-cache* nil)

;;; Test Fixtures

(defn with-whisper-backend
  "Test fixture that creates and initializes a Whisper backend for audio tests."
  [test-fn]
  (let [backend (whisper/create-backend {:model-size "tiny"})
        _ (stt/initialize! backend)
        module-cache (whisper/get-module-cache backend)]
    (binding [*whisper-backend* backend
              *module-cache* module-cache]
      (test-fn))))

;;; Tests

(deftest test-validate-audio
  (testing "validate-audio with valid input"
    (let [valid-bytes (byte-array 1000)]
      (is (true? (multipart/validate-audio valid-bytes)))))

  (testing "validate-audio with nil input"
    (is (thrown-with-msg? Exception #"Audio data is nil"
                          (multipart/validate-audio nil))))

  (testing "validate-audio with empty array"
    (is (thrown-with-msg? Exception #"Audio data is empty"
                          (multipart/validate-audio (byte-array 0))))))

;; Helper function to create valid WAV bytes
(defn- create-test-wav
  "Create valid WAV format bytes for testing.
   Args:
     num-samples - number of audio samples
     sample-rate - audio sample rate (default 16000)"
  ([num-samples] (create-test-wav num-samples 16000))
  ([num-samples sample-rate]
   (let [audio-format (AudioFormat. (float sample-rate) 16 1 true false)
         audio-data (byte-array (* num-samples 2)) ; 2 bytes per 16-bit sample
         audio-in-stream (ByteArrayInputStream. audio-data)
         audio-stream (AudioInputStream. audio-in-stream audio-format num-samples)
         baos (ByteArrayOutputStream.)]
     (AudioSystem/write audio-stream AudioFileFormat$Type/WAVE baos)
     (.toByteArray baos))))

(deftest test-wav-bytes->audio-array-valid-input
  (with-whisper-backend
    (fn []
      (testing "wav-bytes->audio-array with valid 16kHz WAV"
        (let [wav-bytes (create-test-wav 16000) ; 1 second of audio
              audio-array (audio/wav-bytes->audio-array *module-cache* wav-bytes)]
          (is (some? audio-array)
              "Should return audio array for valid input"))))))

(deftest test-wav-bytes->audio-array-wrong-sample-rate
  (with-whisper-backend
    (fn []
      (testing "wav-bytes->audio-array rejects non-16kHz audio"
        (testing "44.1kHz audio"
          (let [wav-bytes (create-test-wav 44100 44100)]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"Audio must be 16kHz"
                                  (audio/wav-bytes->audio-array *module-cache* wav-bytes))
                "Should throw exception for 44.1kHz audio")))

        (testing "8kHz audio"
          (let [wav-bytes (create-test-wav 8000 8000)]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"Audio must be 16kHz"
                                  (audio/wav-bytes->audio-array *module-cache* wav-bytes))
                "Should throw exception for 8kHz audio")))

        (testing "48kHz audio"
          (let [wav-bytes (create-test-wav 48000 48000)]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"Audio must be 16kHz"
                                  (audio/wav-bytes->audio-array *module-cache* wav-bytes))
                "Should throw exception for 48kHz audio")))))))

(deftest test-wav-bytes->audio-array-edge-cases
  (with-whisper-backend
    (fn []
      (testing "wav-bytes->audio-array with edge case inputs"
        (testing "very small audio (100 samples)"
          (let [wav-bytes (create-test-wav 100)
                audio-array (audio/wav-bytes->audio-array *module-cache* wav-bytes)]
            (is (some? audio-array)
                "Should handle small audio files")))

        (testing "large audio (10 seconds)"
          (let [wav-bytes (create-test-wav 160000) ; 10 seconds at 16kHz
                audio-array (audio/wav-bytes->audio-array *module-cache* wav-bytes)]
            (is (some? audio-array)
                "Should handle larger audio files")))))))

(deftest test-wav-bytes->audio-array-invalid-data
  (with-whisper-backend
    (fn []
      (testing "wav-bytes->audio-array with invalid WAV data"
        (testing "empty byte array"
          (let [empty-bytes (byte-array 0)]
            (is (thrown? Exception
                         (audio/wav-bytes->audio-array *module-cache* empty-bytes))
                "Should throw exception for empty data")))

        (testing "random non-WAV bytes"
          (let [random-bytes (.getBytes "not a wav file" "UTF-8")]
            (is (thrown? Exception
                         (audio/wav-bytes->audio-array *module-cache* random-bytes))
                "Should throw exception for non-WAV data")))

        (testing "truncated WAV file (incomplete header)"
          (let [truncated-bytes (byte-array 10)] ; Too small to be valid WAV
            (is (thrown? Exception
                         (audio/wav-bytes->audio-array *module-cache* truncated-bytes))
                "Should throw exception for truncated WAV")))))))

(deftest test-java-bytes->python-bytes-boundary-values
  (with-whisper-backend
    (fn []
      (testing "Byte conversion handles Java signed/Python unsigned correctly"
        ;; This test verifies the conversion at the unit test level would be nice,
        ;; but since java-bytes->python-bytes is private and requires Python,
        ;; we test it indirectly through wav-bytes->audio-array
        (let [wav-bytes (create-test-wav 1000)]
          ;; If byte conversion fails, wav parsing will fail
          (is (some? (audio/wav-bytes->audio-array *module-cache* wav-bytes))
              "Byte conversion should handle signed/unsigned properly"))))))
