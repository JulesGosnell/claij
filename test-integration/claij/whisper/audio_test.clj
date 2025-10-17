(ns ^:python-required claij.whisper.audio-test
  "Tests for audio utilities that don't require Python environment."
  (:require [clojure.test :refer [deftest is testing]]
            [claij.whisper.audio :as audio]))

(deftest test-validate-audio
  (testing "validate-audio with valid input"
    (let [valid-bytes (byte-array 1000)]
      (is (true? (audio/validate-audio valid-bytes)))))

  (testing "validate-audio with nil input"
    (is (thrown-with-msg? Exception #"Audio data is nil"
                          (audio/validate-audio nil))))

  (testing "validate-audio with empty array"
    (is (thrown-with-msg? Exception #"Audio data is empty"
                          (audio/validate-audio (byte-array 0))))))

;; Note: wav-bytes->audio-array tests require Python environment with
;; numpy, soundfile, etc. These are better suited for integration tests
;; that run with: clojure -M:whisper:test
