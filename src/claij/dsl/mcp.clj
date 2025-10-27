(ns claij.dsl.mcp
  "DSL for interacting with MCP servers.
  
  Provides a simple API for initializing MCP bridges and calling tools:
    (initialize-bridge config) -> bridge info
    (call bridge-id tool-name args) -> result
  
  Example:
    (def bridge-info (initialize-bridge {:command \"clojure\"
                                         :args [\"-M\" \"-m\" \"claij.mcp.mock-server\"]
                                         :transport \"stdio\"}))
    
    (call (:bridge-id bridge-info) \"echo\" {:text \"Hello!\"})"
  (:require [clojure.core.async :refer [chan >!! <!! timeout]]
            [clojure.data.json :as json]
            [claij.agent.bridge :refer [start-mcp-bridge]]))

;; =============================================================================
;; Bridge Registry
;; =============================================================================

(defonce bridges
  "Registry of active MCP bridges.
  
  Map of bridge-id -> {:config ...
                       :input-chan ...
                       :output-chan ...
                       :tools [...]
                       :stop-fn ...}"
  (atom {}))

(defonce next-bridge-id
  "Counter for generating unique bridge IDs"
  (atom 0))

(defn list-bridges
  "List all registered bridges with their tools.
  
  Returns vector of maps with :bridge-id, :server-info, and :tools"
  []
  (vec
   (for [[bridge-id bridge] @bridges]
     {:bridge-id bridge-id
      :server-info (:server-info bridge)
      :tools (mapv #(select-keys % [:name :description])
                   (:tools bridge))})))

(defn- generate-bridge-id
  "Generate a unique bridge ID"
  []
  (keyword (str "bridge-" (swap! next-bridge-id inc))))

(defn- register-bridge!
  "Register a bridge in the registry"
  [bridge-id bridge-data]
  (swap! bridges assoc bridge-id bridge-data)
  bridge-id)

(defn- unregister-bridge!
  "Remove a bridge from the registry"
  [bridge-id]
  (swap! bridges dissoc bridge-id))

(defn- get-bridge
  "Get bridge data by ID, throws if not found"
  [bridge-id]
  (or (get @bridges bridge-id)
      (throw (ex-info (str "Bridge not found: " bridge-id)
                      {:bridge-id bridge-id
                       :available-bridges (keys @bridges)}))))

;; =============================================================================
;; MCP Protocol Communication
;; =============================================================================

(defn- ask
  "Send a request and wait for response with timeout.
  
  Returns response map or throws on timeout."
  [input-chan output-chan request timeout-ms]
  (>!! input-chan (json/write-str request))
  (let [response-chan (timeout timeout-ms)
        [response port] (alts!! [output-chan response-chan])]
    (if (= port response-chan)
      (throw (ex-info "MCP request timeout"
                      {:request request
                       :timeout-ms timeout-ms}))
      (json/read-str response :key-fn keyword))))

(defn- tell
  "Send a notification (no response expected)"
  [input-chan notification]
  (>!! input-chan (json/write-str notification)))

(defn- mcp-initialize
  "Send initialize request and notifications/initialized"
  [input-chan output-chan]
  (let [init-response (ask input-chan output-chan
                           {:jsonrpc "2.0"
                            :id 1
                            :method "initialize"
                            :params {:protocolVersion "2025-06-18"
                                     :capabilities {}
                                     :clientInfo {:name "claij"
                                                  :version "1.0-SNAPSHOT"}}}
                           5000)]
    
    ;; Send initialized notification
    (tell input-chan
          {:jsonrpc "2.0"
           :method "notifications/initialized"})
    
    ;; Wait a bit for notification to be processed
    (<!! (timeout 100))
    
    init-response))

(defn- mcp-list-tools
  "Request list of available tools"
  [input-chan output-chan]
  (ask input-chan output-chan
       {:jsonrpc "2.0"
        :id 2
        :method "tools/list"}
       5000))

;; =============================================================================
;; Public API
;; =============================================================================

(defn initialize-bridge
  "Initialize an MCP bridge and register it.
  
  Parameters:
  - config: Bridge configuration map with :command, :args, :transport
  - opts: Optional map with:
    - :bridge-id - Custom bridge ID (keyword), generated if not provided
    - :timeout-ms - Initialization timeout in ms (default: 5000)
  
  Returns:
  - {:bridge-id <keyword>
     :server-info {:name ... :version ...}
     :tools [{:name ... :description ... :inputSchema ... :outputSchema ...}]}
  
  Throws on initialization failure.
  
  Example:
    (initialize-bridge {:command \"clojure\"
                        :args [\"-M\" \"-m\" \"claij.mcp.mock-server\"]
                        :transport \"stdio\"})"
  ([config] (initialize-bridge config {}))
  ([config opts]
   (let [bridge-id (or (:bridge-id opts) (generate-bridge-id))
         input-chan (chan)
         output-chan (chan)
         stop-fn (start-mcp-bridge config input-chan output-chan)]
     
     (try
       ;; Initialize the bridge
       (let [init-response (mcp-initialize input-chan output-chan)
             server-info (get-in init-response [:result :serverInfo])
             
             ;; Get available tools
             tools-response (mcp-list-tools input-chan output-chan)
             tools (get-in tools-response [:result :tools])]
         
         ;; Register the bridge
         (register-bridge! bridge-id
                           {:config config
                            :input-chan input-chan
                            :output-chan output-chan
                            :server-info server-info
                            :tools tools
                            :stop-fn stop-fn
                            :next-request-id (atom 3)}) ; IDs 1 and 2 used for init
         
         ;; Return bridge info
         {:bridge-id bridge-id
          :server-info server-info
          :tools tools})
       
       (catch Exception e
         ;; Cleanup on failure
         (stop-fn)
         (throw (ex-info "Failed to initialize MCP bridge"
                         {:config config
                          :bridge-id bridge-id
                          :error (.getMessage e)}
                         e)))))))

(defn shutdown-bridge
  "Shutdown an MCP bridge and remove it from registry.
  
  Parameters:
  - bridge-id: Bridge identifier (keyword)
  
  Returns: nil"
  [bridge-id]
  (when-let [bridge (get @bridges bridge-id)]
    ((:stop-fn bridge))
    (unregister-bridge! bridge-id)))

(defn call
  "Call an MCP tool on a registered bridge.
  
  Parameters:
  - bridge-id: Bridge identifier (keyword)
  - tool-name: Name of the tool to call (string)
  - arguments: Map of tool arguments
  - opts: Optional map with:
    - :timeout-ms - Request timeout in ms (default: 30000)
  
  Returns:
  - Result map from the tool, typically with :result key
  
  Throws on error or if bridge/tool not found.
  
  Example:
    (call :bridge-1 \"echo\" {:text \"Hello!\"})"
  ([bridge-id tool-name arguments]
   (call bridge-id tool-name arguments {}))
  ([bridge-id tool-name arguments opts]
   (let [bridge (get-bridge bridge-id)
         request-id (swap! (:next-request-id bridge) inc)
         response (ask (:input-chan bridge)
                       (:output-chan bridge)
                       {:jsonrpc "2.0"
                        :id request-id
                        :method "tools/call"
                        :params {:name tool-name
                                 :arguments arguments}}
                       (or (:timeout-ms opts) 30000))]
     
     (if-let [error (:error response)]
       (throw (ex-info (str "MCP tool call failed: " (:message error))
                       {:bridge-id bridge-id
                        :tool-name tool-name
                        :arguments arguments
                        :error error}))
       
       ;; Return the structured content
       (get-in response [:result :structuredContent])))))

(comment
  ;; Example usage
  
  ;; Initialize a mock server bridge
  (def bridge-info
    (initialize-bridge {:command "clojure"
                        :args ["-M" "-m" "claij.mcp.mock-server"]
                        :transport "stdio"}))
  
  ;; Call tools
  (call (:bridge-id bridge-info) "echo" {:text "Hello, World!"})
  ;=> {:result "Hello, World!"}
  
  (call (:bridge-id bridge-info) "add" {:a 5 :b 3})
  ;=> {:result 8}
  
  (call (:bridge-id bridge-info) "greet" {:name "Alice"})
  ;=> {:result "Hello, Alice!"}
  
  ;; List all bridges
  (list-bridges)
  
  ;; Shutdown when done
  (shutdown-bridge (:bridge-id bridge-info))
  )
