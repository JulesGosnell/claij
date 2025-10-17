(ns claij.speech.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-http.client]
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

(deftest test-audio-data->wav-bytes-edge-cases
  (testing "audio-data->wav-bytes with edge case inputs"
    (testing "very small audio array (10 bytes)"
      (let [tiny-audio (byte-array 10)
            wav-bytes (speech/audio-data->wav-bytes tiny-audio)]
        (is (bytes? wav-bytes))
        (is (> (alength wav-bytes) (alength tiny-audio))
            "WAV headers should make result larger than input")))

    (testing "exact threshold boundary (32000 bytes)"
      (let [exact-audio (byte-array 32000)
            wav-bytes (speech/audio-data->wav-bytes exact-audio)]
        (is (bytes? wav-bytes))
        (is (> (alength wav-bytes) 32000))))

    (testing "odd number of bytes (not 16-bit aligned)"
      (let [odd-audio (byte-array 32001)
            wav-bytes (speech/audio-data->wav-bytes odd-audio)]
        (is (bytes? wav-bytes))
        (is (pos? (alength wav-bytes)))))

    (testing "large audio array (1MB)"
      (let [large-audio (byte-array 1000000)
            wav-bytes (speech/audio-data->wav-bytes large-audio)]
        (is (bytes? wav-bytes))
        (is (> (alength wav-bytes) 1000000))))))

(deftest test-post-to-whisper-error-handling
  (testing "post-to-whisper error handling"
    (testing "handles malformed JSON response"
      (with-redefs [clj-http.client/post (fn [_ _] {:status 200 :body "not json"})]
        (let [wav-bytes (byte-array 100)
              result (speech/post-to-whisper wav-bytes "http://test:8000")]
          (is (nil? result)
              "Should return nil for malformed JSON"))))

    (testing "handles HTTP 404 error"
      (with-redefs [clj-http.client/post (fn [_ _] {:status 404 :body "Not found"})]
        (let [wav-bytes (byte-array 100)
              result (speech/post-to-whisper wav-bytes "http://test:8000")]
          (is (nil? result)
              "Should return nil for 404 error"))))

    (testing "handles HTTP 500 error"
      (with-redefs [clj-http.client/post (fn [_ _] {:status 500 :body "Internal error"})]
        (let [wav-bytes (byte-array 100)
              result (speech/post-to-whisper wav-bytes "http://test:8000")]
          (is (nil? result)
              "Should return nil for 500 error"))))

    (testing "handles network exception"
      (with-redefs [clj-http.client/post (fn [_ _] (throw (Exception. "Connection refused")))]
        (let [wav-bytes (byte-array 100)
              result (speech/post-to-whisper wav-bytes "http://test:8000")]
          (is (nil? result)
              "Should return nil for connection exception"))))

    (testing "handles timeout exception"
      (with-redefs [clj-http.client/post (fn [_ _] (throw (java.net.SocketTimeoutException. "Timeout")))]
        (let [wav-bytes (byte-array 100)
              result (speech/post-to-whisper wav-bytes "http://test:8000")]
          (is (nil? result)
              "Should return nil for timeout exception"))))

    (testing "handles successful response with text"
      (with-redefs [clj-http.client/post (fn [_ _] {:status 200 :body "{\"text\": \"hello world\"}"})]
        (let [wav-bytes (byte-array 100)
              result (speech/post-to-whisper wav-bytes "http://test:8000")]
          (is (= "hello world" result)
              "Should return transcribed text for successful response"))))

    (testing "handles response with empty text"
      (with-redefs [clj-http.client/post (fn [_ _] {:status 200 :body "{\"text\": \"\"}"})]
        (let [wav-bytes (byte-array 100)
              result (speech/post-to-whisper wav-bytes "http://test:8000")]
          (is (= "" result)
              "Should return empty string if transcription is empty"))))

    (testing "handles response with missing text field"
      (with-redefs [clj-http.client/post (fn [_ _] {:status 200 :body "{\"result\": \"something\"}"})]
        (let [wav-bytes (byte-array 100)
              result (speech/post-to-whisper wav-bytes "http://test:8000")]
          (is (nil? result)
              "Should return nil if text field is missing"))))))
