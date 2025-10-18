(ns claij.stt.server
  "Generic HTTP server for STT services using Jetty.
   
   This server works with any STTBackend implementation, making it easy to
   swap between different speech-to-text engines (Whisper, Google, AWS, etc.)
   
   Usage:
   (require '[claij.stt.server :as server])
   (require '[claij.stt.whisper.python :as whisper])
   
   (def backend (whisper/create-backend {:model-size \"small\"}))
   (server/start-server! backend {:port 8000})
   
   Public API:
   - start-server!: Start HTTP server with a backend
   - stop-server!: Stop the running server"
  (:require [clojure.tools.logging :as log]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [claij.stt.core :as stt]
            [claij.stt.handler :as handler]
            [claij.stt.whisper.python :as whisper])
  (:gen-class))

(set! *warn-on-reflection* true)

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
   
   backend - STTBackend instance
   module-cache - Backend's Python module cache"
  [backend module-cache]
  (-> (handler/create-app backend module-cache)
      wrap-multipart-params
      wrap-logging))

(defn start-server!
  "Start the STT HTTP server with the given backend.
   
   backend - Implementation of STTBackend protocol
   
   Options:
   - :port (default 8000) - HTTP port
   - :host (default \"0.0.0.0\") - Bind address
   - :join? (default false) - Whether to block
   
   Example:
   (def backend (whisper/create-backend {:model-size \"small\"}))
   (start-server! backend {:port 8000})
   
   Returns the Jetty server instance."
  ([backend] (start-server! backend {}))
  ([backend {:keys [port host join?]
             :or {port 8000
                  host "0.0.0.0"
                  join? false}}]

   (log/info "Initializing STT service...")
   (let [backend-info (stt/backend-info backend)]
     (log/info "Backend:" backend-info))

   ;; Initialize the backend (load models, connect to services, etc.)
   (stt/initialize! backend)
   (log/info "Backend initialized successfully")

   ;; Get module cache from backend for audio processing
   ;; Note: This is Whisper-specific. In the future, we might make audio
   ;; processing more generic or handle it within the backend.
   (let [module-cache (whisper/get-module-cache backend)]

     (log/info "Starting HTTP server on" (str host ":" port))

     (let [app (create-app backend module-cache)
           server (jetty/run-jetty app
                                   {:port port
                                    :host host
                                    :join? join?})]
       (reset! server-state server)
       (log/info "STT service ready!")
       server))))

(defn stop-server!
  "Stop the STT HTTP server."
  []
  (when-let [^org.eclipse.jetty.server.Server server @server-state]
    (log/info "Stopping STT service...")
    (.stop server)
    (reset! server-state nil)
    (log/info "STT service stopped.")))

;;; Main Entry Point

(defn -main
  "Main entry point for the STT service using Whisper backend.
   Accepts optional arguments: [port] [host] [model-size]
   
   Example:
   clojure -M:whisper -m claij.stt.server 8080 0.0.0.0 small"
  [& args]
  (let [port (or (some-> (first args) Integer/parseInt) 8000)
        host (or (second args) "0.0.0.0")
        model-size (or (nth args 2 nil) "small")]

    (log/info "Starting STT service with Whisper backend:"
              {:port port :host host :model-size model-size})

    ;; Create Whisper backend
    (let [backend (whisper/create-backend {:model-size model-size})]
      (start-server! backend {:port port
                              :host host
                              :join? true}))))
