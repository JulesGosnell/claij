(ns claij.stt.whisper.audio-validation-test
  "Unit tests for audio validation (no Python required).
   These tests can run without libpython-clj or Python dependencies."
  (:require [clojure.test :refer [deftest is testing]]
            [claij.stt.whisper.multipart :as multipart]))

(deftest test-validate-audio-with-valid-bytes
  (testing "Validation passes with valid byte array"
    (let [valid-bytes (.getBytes "RIFF....WAVEfmt " "UTF-8")]
      (is (true? (multipart/validate-audio valid-bytes))
          "Should return true for valid byte array"))))

(deftest test-validate-audio-with-nil
  (testing "Validation throws exception for nil input"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Audio data is nil"
                          (multipart/validate-audio nil))
        "Should throw exception with 'Audio data is nil' message")))

(deftest test-validate-audio-with-empty-array
  (testing "Validation throws exception for empty byte array"
    (let [empty-bytes (byte-array 0)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Audio data is empty"
                            (multipart/validate-audio empty-bytes))
          "Should throw exception with 'Audio data is empty' message"))))

(deftest test-validate-audio-with-single-byte
  (testing "Validation passes with single byte"
    (let [single-byte (byte-array 1 (byte 0))]
      (is (true? (multipart/validate-audio single-byte))
          "Should accept single byte array"))))

(deftest test-validate-audio-with-large-array
  (testing "Validation passes with large byte array"
    (let [large-bytes (byte-array 1000000 (byte 42))] ; 1MB
      (is (true? (multipart/validate-audio large-bytes))
          "Should accept large byte arrays"))))

(deftest test-validate-audio-error-data-structure
  (testing "Exception contains proper error information"
    (try
      (multipart/validate-audio nil)
      (is false "Should have thrown exception")
      (catch clojure.lang.ExceptionInfo e
        (is (= "Audio data is nil" (ex-message e))
            "Exception message should describe the error")
        (is (map? (ex-data e))
            "Exception should contain ex-data map")))))
