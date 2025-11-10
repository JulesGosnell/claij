(ns claij.mcp.bridge
  (:require
   [clojure.core.async :refer [go-loop <! >! close!]]
   [clojure.java.process :refer [start stdin stdout]]
   [clojure.java.io :refer [reader writer]]))

(defn- start-process-bridge
  "Forks a process, connecting its stdin to input-chan and stdout to output-chan for line-based I/O.
   Takes a string command (e.g., 'bash' or 'bash -c script'). Returns a stop function."
  [cmd args input-chan output-chan]
  (let [process (apply start cmd args)
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
        ;;(when (and line (re-matches #"\s*\{.*" line))
          (>! output-chan line)
          ;;)
        (recur)))
    (fn stop []
      (.close in-writer)
      (.close out-reader)
      (.destroy process)
      (close! input-chan)
      (close! output-chan))))

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
  (start-process-bridge command args input-chan output-chan))

(comment
  (require '[clojure.core.async :refer [chan >!! <!!]])
  (require '[clojure.data.json :refer [write-str read-str]])

  (def config
    {:command "uv"
     :args ["--directory"
            "/home/jules/src/claij/rocket-chat/mcp-rocketchat"
            "run"
            "rocketchat.py"
            "--server-url"
            "http://localhost:3000"
            "--username"
            "claude"
            "--password"
            "claude"]
     :transport "stdio"})

  (def ic (chan))
  (def oc (chan))
  (def stop (claij.agent.bridge/start-mcp-bridge config ic oc))

  (defn ask [m] (>!! ic (write-str m)) (read-str (<!! oc) :key-fn keyword))
  (defn tell [m] (>!! ic (write-str m)) nil)

  ;; make initialisation request​

  (ask
   {:jsonrpc "2.0"
    :id 1
    :method "initialize"
    :params
    {:protocolVersion "2025-06-18"
     :capabilities
     {:elicitation {}}
     :clientInfo
     {:name "claij"
      :version "1.0-SNAPSHOT"}}})

  ;; response​
  {:jsonrpc "2.0"
   :id 1
   :result
   {:protocolVersion "2025-06-18"
    :capabilities
    {:experimental
     {}
     :prompts
     {:listChanged false}
     :resources
     {:subscribe false
      :listChanged false}
     :tools
     {:listChanged false}}
    :serverInfo
    {:name "rocketchat"
     :version "1.12.3"}}}

  ;; client declares that it is ready​
  (tell
   {:jsonrpc "2.0"
    :method "notifications/initialized"})

  ;; tool discovery​
  (ask
   {:jsonrpc "2.0"
    :id 2
    :method "tools/list"})

  ;; response
  {:jsonrpc "2.0",
   :id 2,
   :result
   {:tools
    [{:name "list_users",
      :description "List all users available to the user.",
      :inputSchema
      {:properties {}, :title "list_usersArguments", :type "object"},
      :outputSchema
      {:properties {:result {:title "Result", :type "string"}},
       :required ["result"],
       :title "list_usersOutput",
       :type "object"}}
     {:name "send_message_in_channel",
      :description
      "Send a message to a RocketChat channel.\n\nArgs:\n    channel​ Channel name (e.g., 'general') or channel ID\n    text​ Message text to send\n",
      :inputSchema
      {:properties
       {:channel {:title "Channel", :type "string"},
        :text {:title "Text", :type "string"}},
       :required ["channel" "text"],
       :title "send_message_in_channelArguments",
       :type "object"},
      :outputSchema
      {:properties {:result {:title "Result", :type "string"}},
       :required ["result"],
       :title "send_message_in_channelOutput",
       :type "object"}}
     {:name "list_channels",
      :description "List all channels available to the user.",
      :inputSchema
      {:properties {},
       :title "list_channelsArguments",
       :type "object"},
      :outputSchema
      {:properties {:result {:title "Result", :type "string"}},
       :required ["result"],
       :title "list_channelsOutput",
       :type "object"}}
     {:name "list_all_rooms",
      :description
      "List all rooms (channels and groups) available to the user.",
      :inputSchema
      {:properties {},
       :title "list_all_roomsArguments",
       :type "object"},
      :outputSchema
      {:properties {:result {:title "Result", :type "string"}},
       :required ["result"],
       :title "list_all_roomsOutput",
       :type "object"}}
     {:name "get_user_info",
      :description
      "Get information about a specific user.\n\nArgs:\n    username​ Username to get information about\n",
      :inputSchema
      {:properties {:username {:title "Username", :type "string"}},
       :required ["username"],
       :title "get_user_infoArguments",
       :type "object"},
      :outputSchema
      {:properties {:result {:title "Result", :type "string"}},
       :required ["result"],
       :title "get_user_infoOutput",
       :type "object"}}
     {:name "create_channel",
      :description
      "Create a new channel.\n\nArgs:\n    name​ Name of the channel to create\n",
      :inputSchema
      {:properties {:name {:title "Name", :type "string"}},
       :required ["name"],
       :title "create_channelArguments",
       :type "object"},
      :outputSchema
      {:properties {:result {:title "Result", :type "string"}},
       :required ["result"],
       :title "create_channelOutput",
       :type "object"}}
     {:name "get_channel_messages",
      :description
      "Get messages from a specific channel.\n\nArgs:\n    room_id​ ID of the room/channel\n    count​ Number of messages to retrieve (default​ 20, max​ 100)\n",
      :inputSchema
      {:properties
       {:room_id {:title "Room Id", :type "string"},
        :count {:default 20, :title "Count", :type "integer"}},
       :required ["room_id"],
       :title "get_channel_messagesArguments",
       :type "object"},
      :outputSchema
      {:properties {:result {:title "Result", :type "string"}},
       :required ["result"],
       :title "get_channel_messagesOutput",
       :type "object"}}]}}

  ;; a tool call
  (ask
   {:jsonrpc "2.0"
    :id 3
    :method "tools/call"
    :params
    {:name "send_message_in_channel"
     :arguments
     {:channel "general"
      :text "Hello!"}}})

  ;; response
  {:jsonrpc "2.0"
   :id 3
   :result
   {:content
    [{:type "text"
      :text "Message sent successfully to general"}]
    :structuredContent
    {:result "Message sent successfully to general"}
    :isError false}})
