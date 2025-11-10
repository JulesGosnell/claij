(ns claij.mcp-standalone-test
  "Standalone test of MCP glue layer without FSM"
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.tools.logging :as log]
   [claij.mcp :refer [mcp-sessions
                      start-mcp-service!
                      initialize-mcp-service!
                      send-mcp-request!
                      default-mcp-service
                      default-mcp-config]]))

(deftest ^:integration mcp-service-lifecycle-test
  (testing "MCP service starts, initializes, and responds to requests"
    ;; Step 1: Start the service
    (let [service (start-mcp-service! default-mcp-service default-mcp-config)]
      (is service "Service should start")
      (is (get @mcp-sessions default-mcp-service) "Service should be in sessions")

      ;; Step 2: Initialize the service
      (let [init-result (initialize-mcp-service! default-mcp-service)]
        (log/info "Initialization result:" init-result)
        (is init-result "Should get initialization result")
        (is (get init-result "protocolVersion") "Should have protocol version")
        (is (get init-result "serverInfo") "Should have server info")
        (log/info "Server:" (get init-result "serverInfo"))

        ;; Step 3: List tools
        (let [tools-promise (send-mcp-request! default-mcp-service "tools/list" {})
              tools-response (deref tools-promise 30000 ::timeout)]
          (is (not= tools-response ::timeout) "Tools request should not timeout")
          (log/info "Tools response:" (get tools-response "result"))
          (is (get tools-response "result") "Should have result")
          (is (get-in tools-response ["result" "tools"]) "Should have tools list"))

        ;; Step 4: Try to evaluate something simple
        (let [eval-promise (send-mcp-request! default-mcp-service
                                              "tools/call"
                                              {"name" "clojure_eval"
                                               "arguments" {"code" "(+ 1 2)"}})
              eval-response (deref eval-promise 30000 ::timeout)]
          (is (not= eval-response ::timeout) "Eval request should not timeout")
          (log/info "Eval response:" (get eval-response "result"))
          (is (get eval-response "result") "Should have eval result"))))))
