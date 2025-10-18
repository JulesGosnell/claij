(ns claij.stt.whisper.multipart-test
  "Tests for multipart file handling utilities."
  (:require [clojure.test :refer [deftest is testing]]
            [claij.stt.whisper.multipart :as multipart])
  (:import [java.io File ByteArrayOutputStream]
           [java.nio.file Files]))

(deftest test-extract-bytes-from-byte-array
  (testing "Extracting bytes from raw byte array"
    (let [test-bytes (.getBytes "test data" "UTF-8")
          result (multipart/extract-bytes test-bytes)]
      (is (= (seq test-bytes) (seq result))
          "Should return the same byte array"))))

(deftest test-extract-bytes-from-map-with-bytes-key
  (testing "Extracting bytes from map with :bytes key"
    (let [test-bytes (.getBytes "test data" "UTF-8")
          file-part {:bytes test-bytes
                     :filename "test.wav"
                     :content-type "audio/wav"}
          result (multipart/extract-bytes file-part)]
      (is (= (seq test-bytes) (seq result))
          "Should extract bytes from :bytes key"))))

(deftest test-extract-bytes-from-map-with-tempfile-key
  (testing "Extracting bytes from map with :tempfile key"
    (let [test-data "test audio data"
          test-bytes (.getBytes test-data "UTF-8")
          temp-file (File/createTempFile "whisper-test" ".wav")]
      (try
        ;; Write test data to temp file
        (with-open [out (java.io.FileOutputStream. temp-file)]
          (.write out test-bytes))

        ;; Test extraction
        (let [file-part {:tempfile temp-file
                         :filename "test.wav"
                         :content-type "audio/wav"}
              result (multipart/extract-bytes file-part)]
          (is (= (seq test-bytes) (seq result))
              "Should read bytes from temp file"))
        (finally
          (.delete temp-file))))))

(deftest test-extract-bytes-empty-byte-array
  (testing "Extracting empty byte array"
    (let [empty-bytes (byte-array 0)
          result (multipart/extract-bytes empty-bytes)]
      (is (= 0 (alength result))
          "Should handle empty byte array"))))

(deftest test-extract-bytes-invalid-map-no-bytes-or-tempfile
  (testing "Extracting from map without :bytes or :tempfile throws error"
    (let [invalid-part {:filename "test.wav"
                        :content-type "audio/wav"}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (multipart/extract-bytes invalid-part))
          "Should throw exception for invalid file part"))))

(deftest test-extract-bytes-unexpected-type
  (testing "Extracting from unexpected type throws error"
    (is (thrown? clojure.lang.ExceptionInfo
                 (multipart/extract-bytes "invalid-string"))
        "Should throw exception for string input")

    (is (thrown? clojure.lang.ExceptionInfo
                 (multipart/extract-bytes 123))
        "Should throw exception for numeric input")))

(deftest test-extract-bytes-large-file
  (testing "Extracting bytes from large file"
    (let [large-data (byte-array 1000000 (byte 42)) ; 1MB of data
          temp-file (File/createTempFile "whisper-large" ".wav")]
      (try
        (with-open [out (java.io.FileOutputStream. temp-file)]
          (.write out large-data))

        (let [file-part {:tempfile temp-file
                         :filename "large.wav"}
              result (multipart/extract-bytes file-part)]
          (is (= (alength large-data) (alength result))
              "Should handle large files correctly"))
        (finally
          (.delete temp-file))))))
