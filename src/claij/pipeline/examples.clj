(ns claij.pipeline.examples
  (:require [claij.pipeline.core :as p]
            [claij.pipeline.components :as c]))

(defn echo-pipeline [whisper-url piper-config]
  (p/->pipeline
   (c/record-source)
   (c/whisper-transformer whisper-url)
   (c/piper-transformer piper-config)
   (c/playback-sink)))

(defn start-echo []
  (echo-pipeline 
    "http://localhost:8000/transcribe"
    {:voice-path "EDIT-THIS-PATH"}))
