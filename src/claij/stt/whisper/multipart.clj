(ns claij.stt.whisper.multipart
  "Multipart file handling and validation utilities for Whisper backend.
   
   Provides utilities for working with Ring multipart file uploads:
   - Byte extraction from different upload formats (in-memory or file-based)
   - Basic audio data validation (nil/empty checks)
   
   Public API:
   - extract-bytes: Extract byte array from multipart file upload
   - validate-audio: Validate audio byte array (basic checks)"
  (:require [clojure.java.io :refer [input-stream copy]])
  (:import [java.io ByteArrayOutputStream]))

(defn extract-bytes
  "Extract bytes from a multipart file upload.
   Handles both in-memory uploads (:bytes) and file-based uploads (:tempfile).
   Returns a byte array."
  ^bytes [file-part]
  (cond
    (bytes? file-part)
    file-part

    (map? file-part)
    (cond
      (:bytes file-part)
      ^bytes (:bytes file-part)

      (:tempfile file-part)
      (let [^java.io.File temp-file (:tempfile file-part)
            ^java.io.ByteArrayOutputStream baos (ByteArrayOutputStream.)]
        (with-open [in (input-stream temp-file)]
          (copy in baos)
          (.toByteArray baos)))

      :else
      (throw (ex-info "Invalid file part format - no :bytes or :tempfile"
                      {:part file-part})))

    :else
    (throw (ex-info "Unexpected file part type"
                    {:type (type file-part)}))))

(defn validate-audio
  "Validate audio byte array. Returns true if valid, throws exception otherwise.
   
   Args:
     wav-bytes - Byte array containing audio data
     
   Returns:
     true if valid
     
   Throws:
     ExceptionInfo if audio data is nil or empty"
  [^bytes wav-bytes]
  (when (nil? wav-bytes)
    (throw (ex-info "Audio data is nil" {})))

  (when (zero? (alength wav-bytes))
    (throw (ex-info "Audio data is empty" {})))

  true)
