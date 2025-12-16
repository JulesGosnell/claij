(ns claij.mcp.bridge
  "Low-level MCP bridge with JSON-RPC ID correlation.
   
   Provides two levels of abstraction:
   1. Basic bridge: Raw channel-based stdio communication
   2. Correlated bridge: Request/response correlation by JSON-RPC ID
   
   The correlated bridge tracks in-flight requests and matches responses
   by their 'id' field, supporting out-of-order responses per MCP spec."
  (:require
   [clojure.core.async :refer [go-loop <! >! >!! close! chan sliding-buffer]]
   [clojure.java.process :refer [start stdin stdout]]
   [clojure.java.io :refer [reader writer]]
   [clojure.data.json :refer [write-str read-str]]))

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
  "Starts an MCP bridge from a config map, dispatching on transport.
   Takes a map with :command (string), :args (vector of strings or nil), and transport (e.g., \"stdio\", \"http\", \"sse\").
   Matches Claude Desktop's proprietary mcpServers config format.
   Returns a stop function to clean up resources.
   Example stdio config: {:command \"bash\" :args [\"-c\" \"script.sh\"] transport \"stdio\"}
   Additional transport methods (e.g., socket) will be added for new server types."
  (fn [{t "transport"} _input-chan _output-chan] t))

(defmethod start-mcp-bridge "stdio"
  [{:strs [command args]} input-chan output-chan]
  (when-not (and (string? command) (not-empty command))
    (throw (IllegalArgumentException. "Command must be a non-empty string")))
  (when-not (or (nil? args) (vector? args))
    (throw (IllegalArgumentException. "Args must be a vector or nil")))
  (when-not (not-empty command)
    (throw (IllegalArgumentException. "Full command must be non-empty")))
  (start-process-bridge command args input-chan output-chan json-string?))

;; =============================================================================
;; Correlated Bridge - Request/Response ID Matching
;; =============================================================================

(defn create-correlated-bridge
  "Creates a correlated bridge that tracks requests by ID and matches responses.
   
   Takes a config map and returns a bridge map with:
   - :pending     - atom of {id -> {:promise p :request r :timestamp t}}
   - :input-chan  - channel for sending raw messages (for notifications)
   - :output-chan - channel for unmatched messages (notifications, etc)
   - :stop        - function to stop the bridge
   
   The bridge automatically correlates responses with requests by 'id' field.
   Notifications and messages without 'id' go to output-chan."
  [config]
  (let [pending (atom {})
        ;; Internal channels to the actual process
        ;; Use sliding buffer for raw-output to handle notification floods
        ;; MCP servers emit many list_changed notifications at startup
        raw-input (chan 100)
        raw-output (chan (sliding-buffer 1000))
        ;; External channel for uncorrelated messages (notifications)
        ;; Use sliding buffer to prevent blocking on notification floods
        notification-chan (chan (sliding-buffer 100))
        ;; Start the underlying bridge
        stop-fn (start-mcp-bridge config raw-input raw-output)

        ;; Response processing loop - matches responses to pending requests
        _ (go-loop []
            (when-some [line (<! raw-output)]
              (let [msg (try (read-str line) (catch Exception _ nil))
                    id (get msg "id")]
                (if-let [{:keys [promise]} (and id (get @pending id))]
                  ;; Response with matching ID - deliver to promise
                  (do
                    (deliver promise msg)
                    (swap! pending dissoc id))
                  ;; No matching ID - forward to notification channel
                  (>! notification-chan msg)))
              (recur)))]

    {:pending pending
     :input-chan raw-input
     :output-chan notification-chan
     :stop (fn []
             (stop-fn)
             (close! notification-chan))}))

(defn send-request
  "Send a single request and return a promise for the response.
   
   The request must have an 'id' field for correlation.
   Returns the promise immediately; deref to get response."
  [{:keys [pending input-chan]} request]
  (let [id (get request "id")
        p (promise)]
    (when-not id
      (throw (IllegalArgumentException. "Request must have 'id' field")))
    (swap! pending assoc id {:promise p
                             :request request
                             :timestamp (System/currentTimeMillis)})
    (>!! input-chan (write-str request))
    p))

(defn send-batch
  "Send multiple requests and return a map of {id -> promise}.
   
   Each request must have an 'id' field for correlation.
   Requests are sent sequentially (use parallel option in client for concurrent)."
  [bridge requests]
  (into {} (map (fn [req]
                  [(get req "id") (send-request bridge req)])
                requests)))

(defn await-response
  "Wait for a single response with timeout.
   
   Returns the response map, or {:error \"timeout\" :id id} on timeout."
  [promise-obj id timeout-ms]
  (let [result (deref promise-obj timeout-ms ::timeout)]
    (if (= result ::timeout)
      {"error" {"message" "timeout"} "id" id}
      result)))

(defn await-responses
  "Wait for all responses with shared timeout.
   
   Returns a map of {id -> response}.
   Any request that times out gets {:error {:message \"timeout\"} :id id}."
  [promises timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (into {}
          (map (fn [[id p]]
                 (let [remaining (max 0 (- deadline (System/currentTimeMillis)))]
                   [id (await-response p id remaining)]))
               promises))))

(defn send-and-await
  "Convenience: send requests and wait for all responses.
   
   Returns vector of responses in same order as requests."
  [bridge requests timeout-ms]
  (let [promises (send-batch bridge requests)
        responses (await-responses promises timeout-ms)]
    (mapv #(get responses (get % "id")) requests)))

(defn pending-count
  "Returns the number of requests awaiting responses."
  [{:keys [pending]}]
  (count @pending))

(defn clear-stale-requests
  "Remove requests older than max-age-ms from pending.
   Returns the number of cleared requests."
  [{:keys [pending]} max-age-ms]
  (let [cutoff (- (System/currentTimeMillis) max-age-ms)
        stale-ids (->> @pending
                       (filter (fn [[_ {:keys [timestamp]}]]
                                 (< timestamp cutoff)))
                       (map first))]
    (doseq [id stale-ids]
      (when-let [{:keys [promise]} (get @pending id)]
        (deliver promise {"error" {"message" "stale"} "id" id}))
      (swap! pending dissoc id))
    (count stale-ids)))
