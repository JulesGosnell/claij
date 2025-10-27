(ns claij.mcp.mock-server
  "Mock MCP server for testing. Implements MCP protocol over stdio.
  
  Provides simple tools for testing:
  - echo: Returns input text
  - add: Adds two numbers
  - greet: Greets a person by name"
  (:require [clojure.data.json :as json]))

(def ^:private tools
  "Available tools in this mock MCP server"
  [{:name "echo"
    :description "Echoes back the input text"
    :inputSchema
    {:type "object"
     :properties {:text {:type "string" :description "Text to echo"}}
     :required ["text"]}
    :outputSchema
    {:type "object"
     :properties {:result {:type "string"}}
     :required ["result"]}}

   {:name "add"
    :description "Adds two numbers together"
    :inputSchema
    {:type "object"
     :properties {:a {:type "number" :description "First number"}
                  :b {:type "number" :description "Second number"}}
     :required ["a" "b"]}
    :outputSchema
    {:type "object"
     :properties {:result {:type "number"}}
     :required ["result"]}}

   {:name "greet"
    :description "Greets a person by name"
    :inputSchema
    {:type "object"
     :properties {:name {:type "string" :description "Name to greet"}}
     :required ["name"]}
    :outputSchema
    {:type "object"
     :properties {:result {:type "string"}}
     :required ["result"]}}])

(defn- handle-initialize
  "Handle initialize request"
  [request]
  {:jsonrpc "2.0"
   :id (:id request)
   :result
   {:protocolVersion "2025-06-18"
    :capabilities
    {:tools {:listChanged false}
     :prompts {:listChanged false}
     :resources {:subscribe false :listChanged false}
     :experimental {}}
    :serverInfo
    {:name "mock-mcp-server"
     :version "1.0.0"}}})

(defn- handle-tools-list
  "Handle tools/list request"
  [request]
  {:jsonrpc "2.0"
   :id (:id request)
   :result {:tools tools}})

(defn- execute-tool
  "Execute a tool by name with given arguments"
  [tool-name arguments]
  (case tool-name
    "echo"
    {:result (:text arguments)}

    "add"
    {:result (+ (:a arguments) (:b arguments))}

    "greet"
    {:result (str "Hello, " (:name arguments) "!")}

    ;; Unknown tool
    (throw (ex-info (str "Unknown tool: " tool-name)
                    {:tool-name tool-name}))))

(defn- handle-tools-call
  "Handle tools/call request"
  [request]
  (try
    (let [{:keys [name arguments]} (:params request)
          result (execute-tool name arguments)]
      {:jsonrpc "2.0"
       :id (:id request)
       :result
       {:content [{:type "text"
                   :text (str (:result result))}]
        :structuredContent result
        :isError false}})
    (catch Exception e
      {:jsonrpc "2.0"
       :id (:id request)
       :error
       {:code -32603
        :message (.getMessage e)}})))

(defn handle-request
  "Handle a single MCP request and return response.
  
  Notifications (no :id) return nil.
  Requests return a response map."
  [request]
  (let [method (:method request)]
    (cond
      ;; Notifications have no id and no response
      (= method "notifications/initialized")
      nil

      ;; Request methods
      (= method "initialize")
      (handle-initialize request)

      (= method "tools/list")
      (handle-tools-list request)

      (= method "tools/call")
      (handle-tools-call request)

      ;; Unknown method
      :else
      {:jsonrpc "2.0"
       :id (:id request)
       :error
       {:code -32601
        :message (str "Method not found: " method)}})))

(defn -main
  "Run mock MCP server on stdio.
  
  Reads JSON-RPC requests line-by-line from stdin,
  writes JSON-RPC responses line-by-line to stdout."
  []
  (doseq [line (line-seq (java.io.BufferedReader. *in*))]
    (try
      (let [request (json/read-str line :key-fn keyword)
            response (handle-request request)]
        (when response
          (println (json/write-str response))
          (flush)))
      (catch Exception e
        (println (json/write-str
                  {:jsonrpc "2.0"
                   :error
                   {:code -32700
                    :message (str "Parse error: " (.getMessage e))}}))
        (flush)))))

(comment
  ;; Manual testing
  (handle-request
   {:jsonrpc "2.0"
    :id 1
    :method "initialize"
    :params {:protocolVersion "2025-06-18"
             :capabilities {}
             :clientInfo {:name "test" :version "1.0"}}})

  (handle-request
   {:jsonrpc "2.0"
    :method "notifications/initialized"})

  (handle-request
   {:jsonrpc "2.0"
    :id 2
    :method "tools/list"})

  (handle-request
   {:jsonrpc "2.0"
    :id 3
    :method "tools/call"
    :params {:name "echo" :arguments {:text "Hello!"}}}))
