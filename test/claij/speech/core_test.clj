(ns claij.speech.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [claij.speech.core :as speech]))

(deftest test-has-audio?
  (testing "has-audio? validates audio data"
    (testing "nil audio data"
      (is (false? (speech/has-audio? nil))))

    (testing "empty audio data"
      (is (false? (speech/has-audio? (byte-array 0)))))

    (testing "insufficient audio data"
      (is (false? (speech/has-audio? (byte-array 1000)))))

    (testing "sufficient audio data"
      (is (true? (speech/has-audio? (byte-array 32000)))))

    (testing "more than sufficient audio data"
      (is (true? (speech/has-audio? (byte-array 64000)))))))

(deftest test-audio-data->wav-bytes
  (testing "audio-data->wav-bytes creates WAV format in memory"
    (let [audio-data (byte-array 32000)
          wav-bytes (speech/audio-data->wav-bytes audio-data)]

      (testing "returns byte array"
        (is (bytes? wav-bytes)))

      (testing "result is not empty"
        (is (pos? (alength wav-bytes))))

      (testing "result is larger than input (due to WAV headers)"
        (is (> (alength wav-bytes) (alength audio-data))))

      (testing "starts with RIFF header"
        (let [header (String. (java.util.Arrays/copyOfRange wav-bytes 0 4) "UTF-8")]
          (is (= "RIFF" header))))

      (testing "contains WAVE format identifier"
        (let [wave-id (String. (java.util.Arrays/copyOfRange wav-bytes 8 12) "UTF-8")]
          (is (= "WAVE" wave-id)))))))

(deftest test-prepare-audio-xf
  (testing "prepare-audio-xf transducer"
    (let [audio-data (byte-array 32000)
          ctx {:audio-data audio-data}
          xf speech/prepare-audio-xf
          result (into [] xf [ctx])]

      (testing "returns one result"
        (is (= 1 (count result))))

      (testing "adds :wav-bytes to context"
        (is (contains? (first result) :wav-bytes)))

      (testing "wav-bytes is a byte array"
        (is (bytes? (:wav-bytes (first result)))))

      (testing "preserves original context"
        (is (= audio-data (:audio-data (first result))))))))

(deftest test-log-result-xf
  (testing "log-result-xf transducer"
    (testing "with successful transcription"
      (let [ctx {:text "Hello world"}
            xf speech/log-result-xf
            result (into [] xf [ctx])]

        (is (= 1 (count result)))
        (is (= ctx (first result)))))

    (testing "with failed transcription"
      (let [ctx {:text nil}
            xf speech/log-result-xf
            result (into [] xf [ctx])]

        (is (= 1 (count result)))
        (is (= ctx (first result)))))))

(deftest test-process-audio-xf-pipeline
  (testing "complete in-memory audio processing pipeline"
    (testing "filters out empty audio"
      (let [xf (speech/process-audio-xf "http://test:8000")
            contexts [{:audio-data (byte-array 100)} ; Too small
                      {:audio-data (byte-array 32000)}] ; OK
            ;; Only test filtering, not actual HTTP
            filter-only (comp (filter (comp speech/has-audio? :audio-data)))]
        (is (= 1 (count (sequence filter-only contexts))))))

    (testing "passes through sufficient audio"
      (let [filter-xf (comp (filter (comp speech/has-audio? :audio-data)))
            contexts [{:audio-data (byte-array 32000)}
                      {:audio-data (byte-array 64000)}]]
        (is (= 2 (count (sequence filter-xf contexts))))))))
