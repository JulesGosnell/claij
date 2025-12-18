(ns claij.hat.mcp-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [claij.hat :as hat]
   [claij.hat.mcp :refer [mcp-hat-maker format-tools-prompt format-tool-schema
                          hat-mcp-request-schema-fn hat-mcp-response-schema-fn
                          mcp-service-action normalize-mcp-config]]
   [claij.mcp.bridge :as bridge]))

;;------------------------------------------------------------------------------
;; Prompt Generation Tests (no bridge needed)
;;------------------------------------------------------------------------------

(deftest format-tools-prompt-test
  (testing "formats empty tools map"
    (is (= "No MCP tools available." (format-tools-prompt "mc" "mc-mcp" {}))))

  (testing "formats single server with tools"
    (let [tools-by-server {"default" [{"name" "read_file"
                                       "description" "Read a file"
                                       "inputSchema" {"type" "object"
                                                      "properties" {"path" {"type" "string"}}}}]}
          prompt (format-tools-prompt "mc" "mc-mcp" tools-by-server)]
      (is (clojure.string/includes? prompt "read_file"))
      (is (clojure.string/includes? prompt "Read a file"))
      ;; Check for new calls format
      (is (clojure.string/includes? prompt "\"calls\":"))
      (is (clojure.string/includes? prompt "\"default\":"))
      (is (clojure.string/includes? prompt "jsonrpc"))
      ;; Single server shouldn't show server header
      (is (not (clojure.string/includes? prompt "### Server:")))
      ;; Should mention results field
      (is (clojure.string/includes? prompt "results"))))

  (testing "formats multiple servers with grouped tools"
    (let [tools-by-server {"github" [{"name" "list_issues" "description" "List issues"}
                                     {"name" "create_pr" "description" "Create PR"}]
                           "tools" [{"name" "bash" "description" "Run bash"}]}
          prompt (format-tools-prompt "mc" "mc-mcp" tools-by-server)]
      ;; All tools present
      (is (clojure.string/includes? prompt "list_issues"))
      (is (clojure.string/includes? prompt "create_pr"))
      (is (clojure.string/includes? prompt "bash"))
      ;; Server headers present for multi-server
      (is (clojure.string/includes? prompt "### Server: github"))
      (is (clojure.string/includes? prompt "### Server: tools"))
      ;; Available servers listed
      (is (clojure.string/includes? prompt "Available servers:"))
      ;; Mentions cross-server batching
      (is (clojure.string/includes? prompt "multiple servers"))))

  (testing "formats state IDs correctly"
    (let [tools-by-server {"default" [{"name" "test" "description" "Test"}]}
          prompt (format-tools-prompt "llm" "llm-mcp" tools-by-server)]
      (is (clojure.string/includes? prompt "\"llm\""))
      (is (clojure.string/includes? prompt "\"llm-mcp\"")))))

;;------------------------------------------------------------------------------
;; Hat Maker Contract Tests (no actual bridge)
;;------------------------------------------------------------------------------

(deftest mcp-hat-maker-contract-test
  (testing "mcp-hat-maker returns a function"
    (let [hat-fn (mcp-hat-maker "mc" nil)]
      (is (fn? hat-fn))))

  (testing "hat-fn with existing server reuses it"
    (let [fake-bridge {:fake true}
          fake-cache {"tools" [{"name" "test_tool" "description" "Test"}]}
          ;; New structure: servers map with server name -> {:bridge :cache}
          context {:hats {:mcp {:servers {"default" {:bridge fake-bridge
                                                     :cache fake-cache}}}}}
          hat-fn (mcp-hat-maker "mc" nil)
          [ctx' fragment] (hat-fn context)]
      ;; Server reused (same bridge)
      (is (= fake-bridge (get-in ctx' [:hats :mcp :servers "default" :bridge])))
      ;; Schema functions registered
      (is (fn? (get-in ctx' [:id->schema "mc-mcp-request"])))
      (is (fn? (get-in ctx' [:id->schema "mc-mcp-response"])))
      ;; Action var registered (for metadata detection)
      (is (var? (get-in ctx' [:id->action "mcp-service"])))
      (is (= #'mcp-service-action (get-in ctx' [:id->action "mcp-service"])))
      ;; Fragment has correct structure
      (is (= 1 (count (get fragment "states"))))
      (is (= "mc-mcp" (get-in fragment ["states" 0 "id"])))
      (is (= 2 (count (get fragment "xitions"))))
      ;; Xitions reference the registered schema IDs
      (is (= "mc-mcp-request" (get-in fragment ["xitions" 0 "schema"])))
      (is (= "mc-mcp-response" (get-in fragment ["xitions" 1 "schema"])))
      ;; Prompts include tool
      (is (some #(clojure.string/includes? % "test_tool")
                (get fragment "prompts"))))))

;;------------------------------------------------------------------------------
;; Integration Tests (require actual MCP server)
;;------------------------------------------------------------------------------

(deftest ^:integration mcp-hat-integration-test
  (testing "hat initializes bridge and populates cache"
    (let [hat-fn (mcp-hat-maker "mc" nil)
          [ctx' fragment] (hat-fn {})]
      (try
        ;; Bridge should be initialized
        (is (some? (get-in ctx' [:hats :mcp :bridge])))
        ;; Cache should have tools
        (is (seq (get-in ctx' [:hats :mcp :cache "tools"])))
        ;; Schema functions should be registered
        (is (fn? (get-in ctx' [:id->schema "mc-mcp-request"])))
        (is (fn? (get-in ctx' [:id->schema "mc-mcp-response"])))
        ;; Stop hook should be registered
        (is (seq (get-in ctx' [:hats :stop-hooks])))
        ;; Fragment should have states/xitions/prompts
        (is (= "mc-mcp" (get-in fragment ["states" 0 "id"])))
        (is (= 2 (count (get fragment "xitions"))))
        (is (seq (get fragment "prompts")))
        (finally
          ;; Cleanup
          (hat/run-stop-hooks ctx'))))))

(deftest ^:integration mcp-hat-reuse-test
  (testing "second hat on different state reuses bridge"
    (let [hat-fn1 (mcp-hat-maker "mc" nil)
          [ctx1 _] (hat-fn1 {})
          hat-fn2 (mcp-hat-maker "worker" nil)
          [ctx2 fragment2] (hat-fn2 ctx1)]
      (try
        ;; Same bridge object
        (is (identical? (get-in ctx1 [:hats :mcp :bridge])
                        (get-in ctx2 [:hats :mcp :bridge])))
        ;; But different service state ID
        (is (= "worker-mcp" (get-in fragment2 ["states" 0 "id"])))
        (finally
          (hat/run-stop-hooks ctx2))))))

;;------------------------------------------------------------------------------
;; format-tool-schema Tests
;;------------------------------------------------------------------------------

(deftest format-tool-schema-test
  (testing "formats tool with description and schema"
    (let [tool {"name" "read_file"
                "description" "Read a file"
                "inputSchema" {"type" "object"}}
          result (format-tool-schema tool)]
      (is (clojure.string/starts-with? result "- read_file:"))
      (is (clojure.string/includes? result "Read a file"))
      (is (clojure.string/includes? result "Input:"))))

  (testing "formats tool without inputSchema"
    (let [tool {"name" "simple" "description" "Simple tool"}
          result (format-tool-schema tool)]
      (is (clojure.string/includes? result "simple"))
      (is (not (clojure.string/includes? result "Input:")))))

  (testing "formats tool without description"
    (let [tool {"name" "nodesc"}
          result (format-tool-schema tool)]
      (is (clojure.string/includes? result "No description")))))

;;------------------------------------------------------------------------------
;; Schema Function Tests
;;------------------------------------------------------------------------------

(deftest hat-mcp-request-schema-fn-test
  (testing "generates request schema with calls field"
    (let [context {:hats {:mcp {:servers {"default" {:cache {"tools" [{"name" "test_tool"
                                                                       "inputSchema" {"type" "object"}}]}}}}}}
          xition {"id" ["mc" "mc-mcp"]}
          schema (hat-mcp-request-schema-fn context xition)]
      (is (vector? schema))
      (is (= :map (first schema)))
      ;; Find calls field spec
      (let [fields (filter vector? (rest schema))
            calls-spec (some (fn [[k v]] (when (= k "calls") v)) fields)]
        (is (some? calls-spec))
        ;; Should be map-of with server enum as key
        (is (= :map-of (first calls-spec))))))

  (testing "generates request schema with multiple server enum"
    (let [context {:hats {:mcp {:servers {"github" {:cache {"tools" [{"name" "list_issues"}]}}
                                          "tools" {:cache {"tools" [{"name" "bash"}]}}}}}}
          xition {"id" ["mc" "mc-mcp"]}
          schema (hat-mcp-request-schema-fn context xition)]
      (is (vector? schema))
      ;; Calls field should have server enum with both servers
      (let [fields (filter vector? (rest schema))
            calls-spec (some (fn [[k v]] (when (= k "calls") v)) fields)
            ;; calls-spec is [:map-of server-enum batch-schema]
            server-enum (second calls-spec)]
        (is (= :enum (first server-enum)))
        (is (= #{"github" "tools"} (set (rest server-enum))))))))

(deftest hat-mcp-response-schema-fn-test
  (testing "generates response schema with results field"
    (let [context {:hats {:mcp {:servers {"default" {:cache {"tools" [{"name" "test_tool"}]}}}}}}
          xition {"id" ["mc-mcp" "mc"]}
          schema (hat-mcp-response-schema-fn context xition)]
      (is (vector? schema))
      (is (= :map (first schema)))
      ;; Find results field spec
      (let [fields (filter vector? (rest schema))
            results-spec (some (fn [[k v]] (when (= k "results") v)) fields)]
        (is (some? results-spec))
        ;; Should be map-of string to batch response
        (is (= :map-of (first results-spec)))))))

;;------------------------------------------------------------------------------
;; mcp-service-action Tests
;;------------------------------------------------------------------------------

(deftest mcp-service-action-test
  (testing "returns error when no servers configured"
    (let [action-fn (mcp-service-action {} nil nil {"id" "svc"})
          result (atom nil)
          handler (fn [_ctx event] (reset! result event))
          context {}
          event {"id" ["mc" "svc"] "calls" {"default" [{"test" true}]}}]
      (action-fn context event [] handler)
      (is (clojure.string/includes? (get-in @result ["results" "error"]) "No MCP servers"))))

  (testing "returns error when server not found"
    (let [mock-bridge {:mock true}
          action-fn (mcp-service-action {} nil nil {"id" "svc"})
          result (atom nil)
          handler (fn [_ctx event] (reset! result event))
          context {:hats {:mcp {:servers {"github" {:bridge mock-bridge}}}}}
          event {"id" ["mc" "svc"] "calls" {"unknown" [{"test" true}]}}]
      (action-fn context event [] handler)
      (is (clojure.string/includes? (get-in @result ["results" "error"]) "Unknown servers"))))

  (testing "routes requests to single server"
    (let [mock-bridge {:mock true}
          action-fn (mcp-service-action {} nil nil {"id" "svc"})
          result (atom nil)
          handler (fn [_ctx event] (reset! result event))
          context {:hats {:mcp {:servers {"default" {:bridge mock-bridge}}}}}
          event {"id" ["mc" "svc"] "calls" {"default" [{"jsonrpc" "2.0" "method" "test"}]}}]
      (with-redefs [bridge/send-and-await (fn [_b requests _timeout]
                                            (mapv (fn [_] {"result" "ok"}) requests))
                    bridge/drain-notifications (fn [_b] nil)]
        (action-fn context event [] handler)
        ;; Should return to caller with results keyed by server
        (is (= ["svc" "mc"] (get @result "id")))
        (is (= {"default" [{"result" "ok"}]} (get @result "results"))))))

  (testing "routes batch requests to single server"
    (let [mock-bridge {:mock true}
          action-fn (mcp-service-action {} nil nil {"id" "svc"})
          result (atom nil)
          handler (fn [_ctx event] (reset! result event))
          context {:hats {:mcp {:servers {"default" {:bridge mock-bridge}}}}}
          event {"id" ["mc" "svc"] "calls" {"default" [{"id" 1} {"id" 2}]}}]
      (with-redefs [bridge/send-and-await (fn [_b requests _timeout]
                                            (mapv (fn [r] {"result" (get r "id")}) requests))
                    bridge/drain-notifications (fn [_b] nil)]
        (action-fn context event [] handler)
        ;; Should return vector of responses
        (is (= 2 (count (get-in @result ["results" "default"]))))
        (is (= [{"result" 1} {"result" 2}] (get-in @result ["results" "default"]))))))

  (testing "routes to multiple servers in single call"
    (let [github-bridge {:server "github"}
          tools-bridge {:server "tools"}
          action-fn (mcp-service-action {} nil nil {"id" "svc"})
          result (atom nil)
          handler (fn [_ctx event] (reset! result event))
          context {:hats {:mcp {:servers {"github" {:bridge github-bridge}
                                          "tools" {:bridge tools-bridge}}}}}
          ;; Call both servers at once!
          event {"id" ["mc" "svc"]
                 "calls" {"github" [{"method" "list_issues"}]
                          "tools" [{"method" "bash"} {"method" "read_file"}]}}]
      (with-redefs [bridge/send-and-await (fn [b requests _timeout]
                                            (mapv (fn [r] {"from" (:server b) "method" (get r "method")}) requests))
                    bridge/drain-notifications (fn [_b] nil)]
        (action-fn context event [] handler)
        ;; Results keyed by server
        (is (contains? (get @result "results") "github"))
        (is (contains? (get @result "results") "tools"))
        ;; GitHub got 1 call
        (is (= 1 (count (get-in @result ["results" "github"]))))
        (is (= "github" (get-in @result ["results" "github" 0 "from"])))
        ;; Tools got 2 calls  
        (is (= 2 (count (get-in @result ["results" "tools"]))))
        (is (= "tools" (get-in @result ["results" "tools" 0 "from"])))))))

;;------------------------------------------------------------------------------
;; mcp-hat-maker Init Path Tests (with mocks)
;;------------------------------------------------------------------------------

(deftest mcp-hat-maker-init-test
  (testing "initializes server when none exists"
    (let [fake-bridge {:fake-new true}
          fake-cache {"tools" [{"name" "new_tool"}]}]
      (with-redefs [bridge/init-bridge (fn [_config _opts]
                                         {:bridge fake-bridge :cache fake-cache})]
        (let [hat-fn (mcp-hat-maker "mc" nil)
              [ctx' fragment] (hat-fn {})]
          ;; Server stored in context under "default" name
          (is (= fake-bridge (get-in ctx' [:hats :mcp :servers "default" :bridge])))
          ;; Cache stored
          (is (= fake-cache (get-in ctx' [:hats :mcp :servers "default" :cache])))
          ;; Stop hook registered
          (is (= 1 (count (get-in ctx' [:hats :stop-hooks]))))
          ;; Fragment generated
          (is (= "mc-mcp" (get-in fragment ["states" 0 "id"])))))))

  (testing "initializes multiple servers"
    (let [github-bridge {:server "github"}
          github-cache {"tools" [{"name" "list_issues"}]}
          tools-bridge {:server "tools"}
          tools-cache {"tools" [{"name" "bash"}]}]
      (with-redefs [bridge/init-bridge (fn [config _opts]
                                         (if (= "github" (get config "command"))
                                           {:bridge github-bridge :cache github-cache}
                                           {:bridge tools-bridge :cache tools-cache}))]
        (let [hat-fn (mcp-hat-maker "mc" {:servers {"github" {:config {"command" "github"}}
                                                    "tools" {:config {"command" "tools"}}}})
              [ctx' fragment] (hat-fn {})]
          ;; Both servers initialized
          (is (= github-bridge (get-in ctx' [:hats :mcp :servers "github" :bridge])))
          (is (= tools-bridge (get-in ctx' [:hats :mcp :servers "tools" :bridge])))
          ;; Prompt should mention both servers
          (let [prompt (first (get fragment "prompts"))]
            (is (clojure.string/includes? prompt "list_issues"))
            (is (clojure.string/includes? prompt "bash"))))))))

;;------------------------------------------------------------------------------
;; Config Normalization Tests
;;------------------------------------------------------------------------------

(deftest normalize-mcp-config-test
  (testing "nil config becomes default server"
    (let [result (normalize-mcp-config nil)]
      (is (contains? result :servers))
      (is (contains? (:servers result) "default"))
      (is (= bridge/default-mcp-config (get-in result [:servers "default" :config])))
      (is (= 30000 (get-in result [:servers "default" :timeout-ms])))
      (is (= 30000 (:timeout-ms result)))))

  (testing "empty config becomes default server"
    (let [result (normalize-mcp-config {})]
      (is (contains? (:servers result) "default"))
      (is (= bridge/default-mcp-config (get-in result [:servers "default" :config])))))

  (testing "single :config wraps as default server"
    (let [my-config {"command" "npx" "args" ["my-server"]}
          result (normalize-mcp-config {:config my-config})]
      (is (contains? (:servers result) "default"))
      (is (= my-config (get-in result [:servers "default" :config])))
      (is (= 30000 (get-in result [:servers "default" :timeout-ms])))))

  (testing "custom timeout propagates"
    (let [result (normalize-mcp-config {:timeout-ms 60000})]
      (is (= 60000 (:timeout-ms result)))
      (is (= 60000 (get-in result [:servers "default" :timeout-ms])))))

  (testing ":servers config passes through with defaults applied"
    (let [github-config {"command" "npx" "args" ["github-server"]}
          result (normalize-mcp-config {:servers {"github" {:config github-config}}})]
      (is (contains? (:servers result) "github"))
      (is (= github-config (get-in result [:servers "github" :config])))
      ;; Default timeout applied
      (is (= 30000 (get-in result [:servers "github" :timeout-ms])))))

  (testing "multiple servers preserved"
    (let [github-config {"command" "github"}
          tools-config {"command" "tools"}
          result (normalize-mcp-config {:servers {"github" {:config github-config}
                                                  "tools" {:config tools-config}}})]
      (is (= 2 (count (:servers result))))
      (is (= github-config (get-in result [:servers "github" :config])))
      (is (= tools-config (get-in result [:servers "tools" :config])))))

  (testing "per-server timeout override"
    (let [result (normalize-mcp-config {:timeout-ms 30000
                                        :servers {"fast" {:config {} :timeout-ms 5000}
                                                  "slow" {:config {} :timeout-ms 120000}}})]
      (is (= 5000 (get-in result [:servers "fast" :timeout-ms])))
      (is (= 120000 (get-in result [:servers "slow" :timeout-ms])))))

  (testing "server without config gets default"
    (let [result (normalize-mcp-config {:servers {"myserver" {}}})]
      (is (= bridge/default-mcp-config (get-in result [:servers "myserver" :config]))))))
