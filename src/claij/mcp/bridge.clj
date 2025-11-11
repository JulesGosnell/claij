(ns claij.mcp.bridge
  (:require
   [clojure.core.async :refer [go-loop <! >! close!]]
   [clojure.java.process :refer [start stdin stdout]]
   [clojure.java.io :refer [reader writer]]))

(defn start-process-bridge
  "Forks a process, connecting its stdin to input-chan and stdout to output-chan for line-based I/O.
   Takes a string command (e.g., 'bash' or 'bash -c script'). Returns a stop function."
  [cmd args input-chan output-chan & [p?]]
  (let [p? (or p? (constantly true))
        process (apply start cmd args)
        in-writer (writer (stdin process) :encoding "UTF-8")
        out-reader (reader (stdout process) :encoding "UTF-8")]
    (go-loop []
      (when-some [line (<! input-chan)]
        (.write in-writer (str line "\n"))
        (.flush in-writer)
        (recur)))
    (go-loop []
      (when-some [line (try (.readLine out-reader) (catch java.io.IOException _ nil))]
        ;; Only pass through lines that look like JSON (start with '{')
        ;; This filters out logging and debug output from the MCP server
        (when (and line (p? line))
          (>! output-chan line))
        (recur)))
    (fn stop []
      (.close in-writer)
      (.close out-reader)
      (.destroy process)
      (close! input-chan)
      (close! output-chan))))

(defn json-string? [s]
  (re-matches #"\s*\{.*" s))

(defmulti start-mcp-bridge
  "Starts an MCP bridge from a config map, dispatching on :transport.
   Takes a map with :command (string), :args (vector of strings or nil), and :transport (e.g., \"stdio\", \"http\", \"sse\").
   Matches Claude Desktop's proprietary mcpServers config format.
   Returns a stop function to clean up resources.
   Example stdio config: {:command \"bash\" :args [\"-c\" \"script.sh\"] :transport \"stdio\"}
   Additional transport methods (e.g., socket) will be added for new server types."
  (fn [{t :transport} _input-chan _output-chan] t))

(defmethod start-mcp-bridge "stdio"
  [{:keys [command args]} input-chan output-chan]
  (when-not (and (string? command) (not-empty command))
    (throw (IllegalArgumentException. "Command must be a non-empty string")))
  (when-not (or (nil? args) (vector? args))
    (throw (IllegalArgumentException. "Args must be a vector or nil")))
  (when-not (not-empty command)
    (throw (IllegalArgumentException. "Full command must be non-empty")))
  (start-process-bridge command args input-chan output-chan json-string?))
