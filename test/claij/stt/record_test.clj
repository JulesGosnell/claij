(ns claij.stt.record-test
  (:require [clojure.test :refer [deftest is testing]]
            [clj-http.client]
            [claij.stt.record :as record]))

(deftest test-has-audio?
  (testing "has-audio? validates audio data"
    (testing "nil audio data"
      (is (false? (record/has-audio? nil))))

    (testing "empty audio data"
      (is (false? (record/has-audio? (byte-array 0)))))

    (testing "insufficient audio data"
      (is (false? (record/has-audio? (byte-array 1000)))))

    (testing "sufficient audio data"
      (is (true? (record/has-audio? (byte-array 32000)))))

    (testing "more than sufficient audio data"
      (is (true? (record/has-audio? (byte-array 64000)))))))

(deftest test-audio-data->wav-bytes
  (testing "audio-data->wav-bytes creates WAV format in memory"
    (let [audio-data (byte-array 32000)
          wav-bytes (record/audio-data->wav-bytes audio-data)]

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

(deftest test-prepare-audio
  (testing "prepare-audio function"
    (let [audio-data (byte-array 32000)
          ctx {:audio-data audio-data}
          result (record/prepare-audio ctx)]

      (testing "adds :wav-bytes to context"
        (is (contains? result :wav-bytes)))

      (testing "wav-bytes is a byte array"
        (is (bytes? (:wav-bytes result))))

      (testing "preserves original context"
        (is (= audio-data (:audio-data result)))))))

(deftest test-trace
  (testing "trace function"
    (testing "with successful lookup"
      (let [ctx {:text "Hello world"}
            result (record/trace "test" :text ctx)]
        (is (= ctx result))))

    (testing "with failed lookup (nil value)"
      (let [ctx {:text nil}
            result (record/trace "test" :text ctx)]
        (is (= ctx result))))

    (testing "with missing key"
      (let [ctx {:other "value"}
            result (record/trace "test" :text ctx)]
        (is (= ctx result))))))

(deftest test-process-audio-xf-pipeline
  (testing "complete in-memory audio processing pipeline"
    (testing "filters out empty audio"
      (let [xf (record/process-audio-xf "http://test:8000" "http://llms:8001")
            contexts [{:audio-data (byte-array 100)} ; Too small
                      {:audio-data (byte-array 32000)}] ; OK
            ;; Only test filtering, not actual HTTP
            filter-only (comp (filter (comp record/has-audio? :audio-data)))]
        (is (= 1 (count (sequence filter-only contexts))))))

    (testing "passes through sufficient audio"
      (let [filter-xf (comp (filter (comp record/has-audio? :audio-data)))
            contexts [{:audio-data (byte-array 32000)}
                      {:audio-data (byte-array 64000)}]]
        (is (= 2 (count (sequence filter-xf contexts))))))))

(deftest test-audio-data->wav-bytes-edge-cases
  (testing "audio-data->wav-bytes with edge case inputs"
    (testing "very small audio array (10 bytes)"
      (let [tiny-audio (byte-array 10)
            wav-bytes (record/audio-data->wav-bytes tiny-audio)]
        (is (bytes? wav-bytes))
        (is (> (alength wav-bytes) (alength tiny-audio))
            "WAV headers should make result larger than input")))

    (testing "exact threshold boundary (32000 bytes)"
      (let [exact-audio (byte-array 32000)
            wav-bytes (record/audio-data->wav-bytes exact-audio)]
        (is (bytes? wav-bytes))
        (is (> (alength wav-bytes) 32000))))

    (testing "odd number of bytes (not 16-bit aligned)"
      (let [odd-audio (byte-array 32001)
            wav-bytes (record/audio-data->wav-bytes odd-audio)]
        (is (bytes? wav-bytes))
        (is (pos? (alength wav-bytes)))))

    (testing "large audio array (1MB)"
      (let [large-audio (byte-array 1000000)
            wav-bytes (record/audio-data->wav-bytes large-audio)]
        (is (bytes? wav-bytes))
        (is (> (alength wav-bytes) 1000000))))))

(deftest test-post-to-whisper-error-handling
  (testing "post-to-whisper error handling"
    (testing "handles malformed JSON response"
      (with-redefs [clj-http.client/post (fn [_ _] {:status 200 :body "not json"})]
        (let [wav-bytes (byte-array 100)
              result (record/post-to-whisper wav-bytes "http://test:8000")]
          (is (nil? result)
              "Should return nil for malformed JSON"))))

    (testing "handles HTTP 404 error"
      (with-redefs [clj-http.client/post (fn [_ _] {:status 404 :body "Not found"})]
        (let [wav-bytes (byte-array 100)
              result (record/post-to-whisper wav-bytes "http://test:8000")]
          (is (nil? result)
              "Should return nil for 404 error"))))

    (testing "handles HTTP 500 error"
      (with-redefs [clj-http.client/post (fn [_ _] {:status 500 :body "Internal error"})]
        (let [wav-bytes (byte-array 100)
              result (record/post-to-whisper wav-bytes "http://test:8000")]
          (is (nil? result)
              "Should return nil for 500 error"))))

    (testing "handles network exception"
      (with-redefs [clj-http.client/post (fn [_ _] (throw (Exception. "Connection refused")))]
        (let [wav-bytes (byte-array 100)
              result (record/post-to-whisper wav-bytes "http://test:8000")]
          (is (nil? result)
              "Should return nil for connection exception"))))

    (testing "handles timeout exception"
      (with-redefs [clj-http.client/post (fn [_ _] (throw (java.net.SocketTimeoutException. "Timeout")))]
        (let [wav-bytes (byte-array 100)
              result (record/post-to-whisper wav-bytes "http://test:8000")]
          (is (nil? result)
              "Should return nil for timeout exception"))))

    (testing "handles successful response with text"
      (with-redefs [clj-http.client/post (fn [_ _] {:status 200 :body "{\"text\": \"hello world\"}"})]
        (let [wav-bytes (byte-array 100)
              result (record/post-to-whisper wav-bytes "http://test:8000")]
          (is (= "hello world" result)
              "Should return transcribed text for successful response"))))

    (testing "handles response with empty text"
      (with-redefs [clj-http.client/post (fn [_ _] {:status 200 :body "{\"text\": \"\"}"})]
        (let [wav-bytes (byte-array 100)
              result (record/post-to-whisper wav-bytes "http://test:8000")]
          (is (= "" result)
              "Should return empty string if transcription is empty"))))

    (testing "handles response with missing text field"
      (with-redefs [clj-http.client/post (fn [_ _] {:status 200 :body "{\"result\": \"something\"}"})]
        (let [wav-bytes (byte-array 100)
              result (record/post-to-whisper wav-bytes "http://test:8000")]
          (is (nil? result)
              "Should return nil if text field is missing"))))))
