(ns claij.agent.repl
  (:require
   [clojure.core.async :as async :refer [<!! close! thread]]
   [clojure.core.server :as server])
  (:import
   [java.io PipedReader PipedWriter]
   [clojure.lang LineNumberingPushbackReader]))

(defn start-prepl
  "Starts a prepl with provided input-chan and output-chan, returning stop-prepl.
   input-chan: channel to send forms to the REPL.
   output-chan: channel for nrepl-events (e.g., {:tag :ret, :val ...}, {:tag :input, :val ...}).
   stop-prepl: function to stop the REPL and close streams."
  [input-chan output-chan]
  (let [in-w (PipedWriter.)
        in-r (PipedReader. in-w 8192)
        in (LineNumberingPushbackReader. in-r)
        input-thread (thread
                       (loop []
                         (when-let [sexpr-str (<!! input-chan)]
                           (.write in-w sexpr-str)
                           (.write in-w "\n")
                           (.flush in-w)
                           (recur))))
        prepl-thread (thread
                       (try
                         (server/prepl in (fn [event] (async/>!! output-chan event)))
                         (catch Exception e
                           (println "prepl error:" (.getMessage e))
                           (close! output-chan))
                         (finally
                           (close! output-chan))))]
    ;; needs more work...
    ;; (fn stop-prepl []
    ;;   (close! input-chan)
    ;;   (.close in-w)
    ;;   (.close in-r)
    ;;   (.join input-thread 1000)
    ;;   (.join prepl-thread 5000)
    ;;   (when (.isAlive prepl-thread)
    ;;     (.interrupt prepl-thread)
    ;;     (.join prepl-thread 1000))
    ;;   (close! output-chan))
    ))
