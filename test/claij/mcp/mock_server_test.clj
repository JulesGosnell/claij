(ns claij.mcp.mock-server-test
  "Tests for the mock MCP server"
  (:require [clojure.test :refer [deftest is testing]]
            [claij.mcp.mock-server :as mock-server]))

(deftest test-initialize
  (testing "initialize request returns proper capabilities"
    (let [request {:jsonrpc "2.0"
                   :id 1
                   :method "initialize"
                   :params {:protocolVersion "2025-06-18"
                            :capabilities {}
                            :clientInfo {:name "test" :version "1.0"}}}
          response (mock-server/handle-request request)]

      (is (= "2.0" (:jsonrpc response)))
      (is (= 1 (:id response)))
      (is (= "2025-06-18" (get-in response [:result :protocolVersion])))
      (is (= "mock-mcp-server" (get-in response [:result :serverInfo :name])))
      (is (= "1.0.0" (get-in response [:result :serverInfo :version])))
      (is (false? (get-in response [:result :capabilities :tools :listChanged])))
      (is (false? (get-in response [:result :capabilities :prompts :listChanged])))
      (is (false? (get-in response [:result :capabilities :resources :listChanged]))))))

(deftest test-notifications-initialized
  (testing "initialized notification returns nil"
    (let [request {:jsonrpc "2.0"
                   :method "notifications/initialized"}
          response (mock-server/handle-request request)]
      (is (nil? response) "Notifications should not return a response"))))

(deftest test-tools-list
  (testing "tools/list returns available tools"
    (let [request {:jsonrpc "2.0"
                   :id 2
                   :method "tools/list"}
          response (mock-server/handle-request request)]

      (is (= "2.0" (:jsonrpc response)))
      (is (= 2 (:id response)))
      (is (vector? (get-in response [:result :tools])))
      (is (= 3 (count (get-in response [:result :tools]))))

      ;; Check that echo tool exists
      (let [tools (get-in response [:result :tools])
            echo-tool (first (filter #(= "echo" (:name %)) tools))]
        (is (some? echo-tool))
        (is (= "Echoes back the input text" (:description echo-tool)))
        (is (= ["text"] (get-in echo-tool [:inputSchema :required])))))))

(deftest test-tool-echo
  (testing "echo tool returns input text"
    (let [request {:jsonrpc "2.0"
                   :id 3
                   :method "tools/call"
                   :params {:name "echo"
                            :arguments {:text "Hello, World!"}}}
          response (mock-server/handle-request request)]

      (is (= "2.0" (:jsonrpc response)))
      (is (= 3 (:id response)))
      (is (false? (get-in response [:result :isError])))
      (is (= "Hello, World!" (get-in response [:result :structuredContent :result])))
      (is (= "Hello, World!" (get-in response [:result :content 0 :text]))))))

(deftest test-tool-add
  (testing "add tool adds two numbers"
    (let [request {:jsonrpc "2.0"
                   :id 4
                   :method "tools/call"
                   :params {:name "add"
                            :arguments {:a 5 :b 3}}}
          response (mock-server/handle-request request)]

      (is (= "2.0" (:jsonrpc response)))
      (is (= 4 (:id response)))
      (is (false? (get-in response [:result :isError])))
      (is (= 8 (get-in response [:result :structuredContent :result])))))

  (testing "add tool works with decimals"
    (let [request {:jsonrpc "2.0"
                   :id 5
                   :method "tools/call"
                   :params {:name "add"
                            :arguments {:a 2.5 :b 3.7}}}
          response (mock-server/handle-request request)]

      (is (= 6.2 (get-in response [:result :structuredContent :result]))))))

(deftest test-tool-greet
  (testing "greet tool greets by name"
    (let [request {:jsonrpc "2.0"
                   :id 6
                   :method "tools/call"
                   :params {:name "greet"
                            :arguments {:name "Alice"}}}
          response (mock-server/handle-request request)]

      (is (= "2.0" (:jsonrpc response)))
      (is (= 6 (:id response)))
      (is (false? (get-in response [:result :isError])))
      (is (= "Hello, Alice!" (get-in response [:result :structuredContent :result]))))))

(deftest test-unknown-tool
  (testing "unknown tool returns error"
    (let [request {:jsonrpc "2.0"
                   :id 7
                   :method "tools/call"
                   :params {:name "nonexistent"
                            :arguments {}}}
          response (mock-server/handle-request request)]

      (is (= "2.0" (:jsonrpc response)))
      (is (= 7 (:id response)))
      (is (some? (:error response)))
      (is (= -32603 (get-in response [:error :code])))
      (is (re-find #"Unknown tool" (get-in response [:error :message]))))))

(deftest test-unknown-method
  (testing "unknown method returns error"
    (let [request {:jsonrpc "2.0"
                   :id 8
                   :method "unknown/method"}
          response (mock-server/handle-request request)]

      (is (= "2.0" (:jsonrpc response)))
      (is (= 8 (:id response)))
      (is (some? (:error response)))
      (is (= -32601 (get-in response [:error :code])))
      (is (re-find #"Method not found" (get-in response [:error :message]))))))

(deftest test-full-session
  (testing "complete MCP session flow"
    ;; 1. Initialize
    (let [init-response (mock-server/handle-request
                         {:jsonrpc "2.0"
                          :id 1
                          :method "initialize"
                          :params {:protocolVersion "2025-06-18"
                                   :capabilities {}
                                   :clientInfo {:name "test" :version "1.0"}}})]
      (is (= "mock-mcp-server" (get-in init-response [:result :serverInfo :name]))))

    ;; 2. Send initialized notification
    (let [init-notif-response (mock-server/handle-request
                               {:jsonrpc "2.0"
                                :method "notifications/initialized"})]
      (is (nil? init-notif-response)))

    ;; 3. List tools
    (let [list-response (mock-server/handle-request
                         {:jsonrpc "2.0"
                          :id 2
                          :method "tools/list"})]
      (is (= 3 (count (get-in list-response [:result :tools])))))

    ;; 4. Call a tool
    (let [call-response (mock-server/handle-request
                         {:jsonrpc "2.0"
                          :id 3
                          :method "tools/call"
                          :params {:name "add"
                                   :arguments {:a 10 :b 20}}})]
      (is (= 30 (get-in call-response [:result :structuredContent :result]))))))
