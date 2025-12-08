(ns claij.tts.server
  "Generic HTTP server for TTS services using Jetty.
   
   This server works with any TTSBackend implementation, making it easy to
   swap between different text-to-speech engines (Piper, Google TTS, AWS Polly, etc.)
   
   Usage:
   (require '[claij.tts.server :as server])
   (require '[claij.tts.piper.python :as piper])
   
   (def backend (piper/create-backend {:voice-path \"/path/to/model.onnx\"}))
   (server/start-server! backend {:port 8001})
   
   Public API:
   - start-server!: Start HTTP server with a backend
   - stop-server!: Stop the running server"
  (:require [clojure.tools.logging :as log]
            [ring.adapter.jetty :refer [run-jetty]]
            [claij.tts.core :refer [backend-info initialize!]]
            [claij.tts.handler :refer [create-app] :rename {create-app handler-app}]
            [claij.tts.piper.python :refer [create-backend]])
  (:gen-class))

(defonce ^:private server-state (atom nil))

;;; Middleware

(defn- wrap-logging
  "Middleware to log requests."
  [handler]
  (fn [request]
    (log/debug "Request:" (:request-method request) (:uri request))
    (handler request)))

;;; Server Lifecycle

(defn- create-app
  "Create the Ring application with middleware.
   
   backend - TTSBackend instance"
  [backend]
  (-> (handler-app backend)
      wrap-logging))

(defn start-server!
  "Start the TTS HTTP server with the given backend.
   
   backend - Implementation of TTSBackend protocol
   
   Options:
   - :port (default 8001) - HTTP port
   - :host (default \"0.0.0.0\") - Bind address
   - :join? (default false) - Whether to block
   
   Example:
   (def backend (piper/create-backend {:voice-path \"/path/to/model.onnx\"}))
   (start-server! backend {:port 8001})
   
   Returns the Jetty server instance."
  ([backend] (start-server! backend {}))
  ([backend {:keys [port host join?]
             :or {port 8001
                  host "0.0.0.0"
                  join? false}}]

   (log/info "Initializing TTS service...")
   (let [backend-info (backend-info backend)]
     (log/info "Backend:" backend-info))

   ;; Initialize the backend (load models, connect to services, etc.)
   (initialize! backend)
   (log/info "Backend initialized successfully")

   (log/info "Starting HTTP server on" (str host ":" port))

   (let [app (create-app backend)
         server (run-jetty app
                           {:port port
                            :host host
                            :join? join?})]
     (reset! server-state server)
     (log/info "TTS service ready!")
     server)))

(defn stop-server!
  "Stop the TTS HTTP server."
  []
  (when-let [^org.eclipse.jetty.server.Server server @server-state]
    (log/info "Stopping TTS service...")
    (.stop server)
    (reset! server-state nil)
    (log/info "TTS service stopped.")))

;;; Main Entry Point

(defn -main
  "Main entry point for the TTS service using Piper backend.
   Accepts optional arguments: [port] [host] [voice-path]
   
   Example:
   clojure -M:piper -m claij.tts.server 8001 0.0.0.0 /path/to/model.onnx"
  [& args]
  (let [port (or (some-> (first args) Integer/parseInt) 8001)
        host (or (second args) "0.0.0.0")
        voice-path (or (nth args 2 nil)
                       (System/getenv "PIPER_VOICE_PATH")
                       (throw (ex-info "Voice path required. Provide as argument or set PIPER_VOICE_PATH"
                                       {:type :missing-voice-path})))]

    (log/info "Starting TTS service with Piper backend:"
              {:port port :host host :voice-path voice-path})

    ;; Create Piper backend
    (let [backend (create-backend {:voice-path voice-path})]
      (start-server! backend {:port port
                              :host host
                              :join? true}))))
