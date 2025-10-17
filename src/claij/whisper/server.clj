(ns claij.whisper.server
  "HTTP server for Whisper transcription service using Jetty.
   Clojure implementation using libpython-clj to call Python's Whisper library."
  (:require [clojure.tools.logging :as log]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [claij.whisper.handler :as handler]
            [claij.whisper.python :as whisper])
  (:gen-class))

(defonce ^:private server-state (atom nil))

(defn- wrap-logging
  "Middleware to log requests."
  [handler]
  (fn [request]
    (log/debug "Request:" (:request-method request) (:uri request))
    (handler request)))

(defn create-app
  "Create the Ring application with middleware."
  []
  (-> handler/app
      wrap-multipart-params
      wrap-logging))

(defn start-server!
  "Start the Whisper HTTP server.
   Options:
   - :port (default 8000)
   - :host (default \"0.0.0.0\")
   - :model-size (default \"small\") - Options: tiny, small, medium, large-v3
   - :join? (default false) - Whether to block"
  ([] (start-server! {}))
  ([{:keys [port host model-size join?]
     :or {port 8000
          host "0.0.0.0"
          model-size "small"
          join? false}}]

   (log/info "Initializing Whisper service...")

   ;; Pre-load model (which also loads Python modules)
   (whisper/load-model! model-size)

   (log/info "Starting HTTP server on" (str host ":" port))

   (let [app (create-app)
         server (jetty/run-jetty app
                                 {:port port
                                  :host host
                                  :join? join?})]
     (reset! server-state server)
     (log/info "Whisper service ready!")
     server)))

(defn stop-server!
  "Stop the Whisper HTTP server."
  []
  (when-let [^org.eclipse.jetty.server.Server server @server-state]
    (log/info "Stopping Whisper service...")
    (.stop server)
    (reset! server-state nil)
    (log/info "Whisper service stopped.")))

(defn -main
  "Main entry point for the Whisper service.
   Accepts optional arguments: [port] [host] [model-size]"
  [& args]
  (let [port (or (some-> (first args) Integer/parseInt) 8000)
        host (or (second args) "0.0.0.0")
        model-size (or (nth args 2 nil) "small")]

    (log/info "Starting Whisper service with configuration:"
              {:port port :host host :model-size model-size})

    (start-server! {:port port
                    :host host
                    :model-size model-size
                    :join? true})))
