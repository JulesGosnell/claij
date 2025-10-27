(ns claij.mcp.integration-test
  "Integration test for MCP bridge with mock server
  
  NOTE: These tests are marked with ^:integration metadata and are excluded
  from the default test suite because they spawn subprocesses.
  
  To run integration tests separately:
    clojure -M:test -m kaocha.runner --focus-meta :integration
  
  Or run them manually in the REPL after ensuring no other tests are running."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :refer [chan go-loop <! >!! <!! timeout]]
            [clojure.data.json :as json]
            [claij.agent.bridge :refer [start-mcp-bridge]]))

(defn ask
  "Send a request and wait for response"
  [input-chan output-chan request]
  (>!! input-chan (json/write-str request))
  (let [response-str (<!! output-chan)]
    (json/read-str response-str :key-fn keyword)))

(defn tell
  "Send a notification (no response expected)"
  [input-chan notification]
  (>!! input-chan (json/write-str notification)))

(deftest ^:integration test-mock-server-via-bridge
  (testing "Full MCP session via bridge"
    (let [;; Start mock server via bridge
          config {:command "clojure"
                  :args ["-M" "-m" "claij.mcp.mock-server"]
                  :transport "stdio"}
          input-chan (chan)
          output-chan (chan)
          stop (start-mcp-bridge config input-chan output-chan)]

      (try
        ;; 1. Initialize
        (let [init-response (ask input-chan output-chan
                                 {:jsonrpc "2.0"
                                  :id 1
                                  :method "initialize"
                                  :params {:protocolVersion "2025-06-18"
                                           :capabilities {}
                                           :clientInfo {:name "test-client"
                                                        :version "1.0"}}})]
          (is (= "2.0" (:jsonrpc init-response)))
          (is (= 1 (:id init-response)))
          (is (= "mock-mcp-server" (get-in init-response [:result :serverInfo :name]))))

        ;; 2. Send initialized notification
        (tell input-chan
              {:jsonrpc "2.0"
               :method "notifications/initialized"})

        ;; Wait a bit for notification to be processed
        (<!! (timeout 100))

        ;; 3. List tools
        (let [list-response (ask input-chan output-chan
                                 {:jsonrpc "2.0"
                                  :id 2
                                  :method "tools/list"})]
          (is (= "2.0" (:jsonrpc list-response)))
          (is (= 2 (:id list-response)))
          (is (vector? (get-in list-response [:result :tools])))
          (is (= 3 (count (get-in list-response [:result :tools]))))

          ;; Verify echo tool is present
          (let [tools (get-in list-response [:result :tools])
                echo-tool (first (filter #(= "echo" (:name %)) tools))]
            (is (some? echo-tool))
            (is (= "Echoes back the input text" (:description echo-tool)))))

        ;; 4. Call echo tool
        (let [echo-response (ask input-chan output-chan
                                 {:jsonrpc "2.0"
                                  :id 3
                                  :method "tools/call"
                                  :params {:name "echo"
                                           :arguments {:text "Test message"}}})]
          (is (= "2.0" (:jsonrpc echo-response)))
          (is (= 3 (:id echo-response)))
          (is (false? (get-in echo-response [:result :isError])))
          (is (= "Test message" (get-in echo-response [:result :structuredContent :result]))))

        ;; 5. Call add tool
        (let [add-response (ask input-chan output-chan
                                {:jsonrpc "2.0"
                                 :id 4
                                 :method "tools/call"
                                 :params {:name "add"
                                          :arguments {:a 15 :b 27}}})]
          (is (= "2.0" (:jsonrpc add-response)))
          (is (= 4 (:id add-response)))
          (is (false? (get-in add-response [:result :isError])))
          (is (= 42 (get-in add-response [:result :structuredContent :result]))))

        ;; 6. Call greet tool
        (let [greet-response (ask input-chan output-chan
                                  {:jsonrpc "2.0"
                                   :id 5
                                   :method "tools/call"
                                   :params {:name "greet"
                                            :arguments {:name "Bob"}}})]
          (is (= "2.0" (:jsonrpc greet-response)))
          (is (= 5 (:id greet-response)))
          (is (false? (get-in greet-response [:result :isError])))
          (is (= "Hello, Bob!" (get-in greet-response [:result :structuredContent :result]))))

        (finally
          (stop))))))

(deftest ^:integration test-mock-server-error-handling
  (testing "Error handling via bridge"
    (let [config {:command "clojure"
                  :args ["-M" "-m" "claij.mcp.mock-server"]
                  :transport "stdio"}
          input-chan (chan)
          output-chan (chan)
          stop (start-mcp-bridge config input-chan output-chan)]

      (try
        ;; Initialize first
        (ask input-chan output-chan
             {:jsonrpc "2.0"
              :id 1
              :method "initialize"
              :params {:protocolVersion "2025-06-18"
                       :capabilities {}
                       :clientInfo {:name "test" :version "1.0"}}})

        (tell input-chan
              {:jsonrpc "2.0"
               :method "notifications/initialized"})

        (<!! (timeout 100))

        ;; Try to call unknown tool
        (let [error-response (ask input-chan output-chan
                                  {:jsonrpc "2.0"
                                   :id 2
                                   :method "tools/call"
                                   :params {:name "nonexistent"
                                            :arguments {}}})]
          (is (= "2.0" (:jsonrpc error-response)))
          (is (= 2 (:id error-response)))
          (is (some? (:error error-response)))
          (is (= -32603 (get-in error-response [:error :code]))))

        ;; Try unknown method
        (let [error-response (ask input-chan output-chan
                                  {:jsonrpc "2.0"
                                   :id 3
                                   :method "unknown/method"})]
          (is (= "2.0" (:jsonrpc error-response)))
          (is (= 3 (:id error-response)))
          (is (some? (:error error-response)))
          (is (= -32601 (get-in error-response [:error :code]))))

        (finally
          (stop))))))
