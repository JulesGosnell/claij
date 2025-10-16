(ns claij.clojure-mcp
  (:require
   [nrepl.server :refer [start-server] :rename {start-server start-nrepl-server}]
   [clojure-mcp.main :refer [start-mcp-server]]))

;; integrate nrepl and clojure-mcp in-vm together so that we:
;; a) only have to start a single process
;; b) don't have to be explicit about a port
;; c) avoid an annoying race condition at startup

(defn -main []
  (let [{p :port} (start-nrepl-server)]
    (println "connecting clojure-mcp to nrepl on port:" p)
    (start-mcp-server {:port p})))
