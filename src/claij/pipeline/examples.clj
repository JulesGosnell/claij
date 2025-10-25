(ns claij.pipeline.examples
  (:require [claij.pipeline.core :refer [->pipeline]]
            [claij.pipeline.components :refer [record-source whisper-transformer piper-transformer playback-sink]]))

(defn echo-pipeline [whisper-url piper-config]
  (->pipeline
   (record-source)
   (whisper-transformer whisper-url)
   (piper-transformer piper-config)
   (playback-sink)))

(defn start-echo []
  (echo-pipeline
   "http://localhost:8000/transcribe"
   {:voice-path "EDIT-THIS-PATH"}))
