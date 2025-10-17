(ns claij.speech.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [claij.speech.core :as speech]
            [clojure.java.io :as io])
  (:import [java.io File]))

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

(deftest test-save-audio
  (testing "save-audio creates a WAV file"
    (let [audio-data (byte-array 32000)
          filename (speech/save-audio audio-data)
          file (io/file filename)]

      (testing "returns a filename"
        (is (string? filename))
        (is (not (empty? filename))))

      (testing "creates a file that exists"
        (is (.exists file)))

      (testing "creates a file with .wav extension"
        (is (.endsWith filename ".wav")))

      (testing "creates a file in temp directory"
        (is (.startsWith filename (System/getProperty "java.io.tmpdir"))))

      (testing "creates a file with claij-speech prefix"
        (is (.contains (.getName file) "claij-speech-")))

      ;; Cleanup
      (io/delete-file file true))))

(deftest test-cleanup-file
  (testing "cleanup-file deletes files safely"
    (testing "with nil filename"
      (is (nil? (speech/cleanup-file nil))))

    (testing "with existing file"
      (let [temp-file (File/createTempFile "test-" ".tmp")
            filename (.getAbsolutePath temp-file)]
        (is (.exists temp-file))
        (speech/cleanup-file filename)
        (is (not (.exists temp-file)))))

    (testing "with non-existent file"
      (is (nil? (speech/cleanup-file "/tmp/does-not-exist-12345.wav"))))))

(deftest test-save-audio-xf
  (testing "save-audio-xf transducer"
    (let [audio-data (byte-array 32000)
          ctx {:audio-data audio-data}
          xf speech/save-audio-xf
          result (into [] xf [ctx])]

      (testing "returns one result"
        (is (= 1 (count result))))

      (testing "adds :filename to context"
        (is (contains? (first result) :filename)))

      (testing "filename is a string"
        (is (string? (:filename (first result)))))

      (testing "preserves original context"
        (is (= audio-data (:audio-data (first result)))))

      ;; Cleanup
      (speech/cleanup-file (:filename (first result))))))

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

(deftest test-cleanup-xf
  (testing "cleanup-xf transducer"
    (let [temp-file (File/createTempFile "test-" ".wav")
          filename (.getAbsolutePath temp-file)
          ctx {:filename filename :text "test"}
          xf speech/cleanup-xf]

      (testing "file exists before cleanup"
        (is (.exists temp-file)))

      (testing "transducer processes context"
        (let [result (into [] xf [ctx])]
          (is (= 1 (count result)))
          (is (= ctx (first result)))))

      (testing "file is deleted after cleanup"
        (into [] xf [ctx])
        (is (not (.exists temp-file)))))))

(deftest test-process-audio-xf-pipeline
  (testing "complete audio processing pipeline (without HTTP)"
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
