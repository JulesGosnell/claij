(ns claij.mcp.client
  "High-level MCP client API for batched tool execution.
   
   This namespace provides batched request/response handling while
   preserving the exact JSON-RPC format that LLMs already use.
   
   Input format (what LLM produces):
   [{\"jsonrpc\" \"2.0\" \"id\" 1 \"method\" \"tools/call\" \"params\" {...}}
    {\"jsonrpc\" \"2.0\" \"id\" 2 \"method\" \"tools/call\" \"params\" {...}}]
   
   Output format (what LLM expects):
   [{\"jsonrpc\" \"2.0\" \"id\" 1 \"result\" {...}}
    {\"jsonrpc\" \"2.0\" \"id\" 2 \"result\" {...}}]
   
   The client handles ID correlation and timeout, nothing more."
  (:require
   [claij.mcp.bridge :as bridge]))

;; =============================================================================
;; Main API
;; =============================================================================

(defn call-batch
  "Execute a batch of JSON-RPC requests. Returns responses in request order.
   
   Takes requests exactly as LLM produces them (JSON-RPC format).
   Returns responses exactly as LLM expects them (JSON-RPC format).
   
   Options:
   - :timeout-ms - Per-batch timeout in milliseconds (default 30000)
   
   Example:
   (call-batch bridge
     [{\"jsonrpc\" \"2.0\" \"id\" 1 \"method\" \"tools/call\" 
       \"params\" {\"name\" \"calc\" \"arguments\" {:x 1}}}
      {\"jsonrpc\" \"2.0\" \"id\" 2 \"method\" \"tools/call\"
       \"params\" {\"name\" \"read_file\" \"arguments\" {:path \"x.clj\"}}}]
     {:timeout-ms 5000})
   
   => [{\"jsonrpc\" \"2.0\" \"id\" 1 \"result\" {...}}
       {\"jsonrpc\" \"2.0\" \"id\" 2 \"error\" {...}}]"
  [bridge requests & [{:keys [timeout-ms] :or {timeout-ms 30000}}]]
  (if (empty? requests)
    []
    (bridge/send-and-await bridge requests timeout-ms)))

(defn call-one
  "Execute a single JSON-RPC request. Convenience wrapper around call-batch.
   
   Returns a single response (not wrapped in vector)."
  [bridge request & [opts]]
  (first (call-batch bridge [request] opts)))

;; =============================================================================
;; Health Check
;; =============================================================================

(defn ping
  "Check if MCP server is responsive.
   
   Sends a ping request and waits for response.
   Returns true if server responds, false on timeout.
   
   Options:
   - :timeout-ms - Timeout in milliseconds (default 5000)"
  [bridge & [{:keys [timeout-ms] :or {timeout-ms 5000}}]]
  (let [request {"jsonrpc" "2.0"
                 "id" "ping"
                 "method" "ping"}
        promise (bridge/send-request bridge request)
        response (bridge/await-response promise "ping" timeout-ms)]
    (not (contains? response "error"))))

;; =============================================================================
;; Notification Sending
;; =============================================================================

(defn send-notification
  "Send a notification to the MCP server (no response expected).
   
   Notifications don't have an id and don't expect a response."
  [bridge method params]
  (let [notification {"jsonrpc" "2.0"
                      "method" method
                      "params" params}]
    (clojure.core.async/>!! (:input-chan bridge)
                            (clojure.data.json/write-str notification))))
