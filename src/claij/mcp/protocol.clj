(ns claij.mcp.protocol
  "JSON-RPC message construction for MCP protocol.
   
   This namespace contains the low-level message formats and predicates
   for the Model Context Protocol. It is intentionally stateless and
   focused purely on message structure."
  (:require
   [clojure.string :refer [starts-with?]]))

;; =============================================================================
;; Initialization Messages
;; =============================================================================

(def initialise-request
  "Initial handshake request sent to MCP server."
  {:jsonrpc "2.0"
   "id" 1
   "method" "initialize"
   :params
   {:protocolVersion "2025-06-18"
    :capabilities
    {:elicitation {}}
    :clientInfo
    {:name "claij"
     :version "1.0-SNAPSHOT"}}})

(def initialised-notification
  "Notification sent after successful initialization."
  {:jsonrpc "2.0"
   :method "notifications/initialized"
   :params {}})

;; =============================================================================
;; List Requests
;; =============================================================================

(def list-tools-request
  "Request to list available tools from MCP server."
  {"jsonrpc" "2.0"
   "id" 2
   "method" "tools/list"})

(def list-prompts-request
  "Request to list available prompts from MCP server."
  {"jsonrpc" "2.0"
   "id" 3
   "method" "prompts/list"})

(def list-resources-request
  "Request to list available resources from MCP server."
  {"jsonrpc" "2.0"
   "id" 4
   "method" "resources/list"})

;; =============================================================================
;; Message Predicates
;; =============================================================================

(defn notification?
  "Returns true if message is a notification (no response expected)."
  [{m "method"}]
  (and m (starts-with? m "notifications/")))

(defn list-changed?
  "If message is a list_changed notification, returns the capability name.
   Otherwise returns nil.
   
   Example: 'notifications/tools/list_changed' -> 'tools'"
  [{m "method"}]
  (when-let [[_ capability] (re-matches #"notifications/([^/]+)/list_changed" m)]
    capability))
