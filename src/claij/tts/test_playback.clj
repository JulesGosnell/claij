(ns claij.tts.test-playback
  "Quick test script for TTS playback"
  (:require [claij.tts.piper.python :as piper]
            [claij.tts.playback :as playback]
            [claij.tts.core :as tts]))

(defn -main [& args]
  (let [text (or (first args) "Hello from Clojure! This is a test of text to speech.")
        voice-path "/home/jules/piper-voices/en_US-lessac-medium.onnx"]

    (println "Creating backend...")
    (let [backend (piper/create-backend {:voice-path voice-path})]
      (tts/initialize! backend)

      (println "Speaking:" text)
      (playback/speak backend text)

      (println "Done!")
      (System/exit 0))))
