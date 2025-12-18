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
  (testing "formats empty tools list"
    (is (= "No MCP tools available." (format-tools-prompt "mc" "mc-mcp" []))))

  (testing "formats single tool"
    (let [tools [{"name" "read_file"
                  "description" "Read a file"
                  "inputSchema" {"type" "object"
                                 "properties" {"path" {"type" "string"}}}}]
          prompt (format-tools-prompt "mc" "mc-mcp" tools)]
      (is (clojure.string/includes? prompt "read_file"))
      (is (clojure.string/includes? prompt "Read a file"))
      ;; Check for correct routing format
      (is (clojure.string/includes? prompt "[\"mc\", \"mc-mcp\"]"))
      (is (clojure.string/includes? prompt "jsonrpc"))))

  (testing "formats multiple tools"
    (let [tools [{"name" "tool1" "description" "First"}
                 {"name" "tool2" "description" "Second"}]
          prompt (format-tools-prompt "llm" "llm-mcp" tools)]
      (is (clojure.string/includes? prompt "tool1"))
      (is (clojure.string/includes? prompt "tool2"))
      ;; Check state IDs are embedded
      (is (clojure.string/includes? prompt "llm"))
      (is (clojure.string/includes? prompt "llm-mcp")))))

;;------------------------------------------------------------------------------
;; Hat Maker Contract Tests (no actual bridge)
;;------------------------------------------------------------------------------

(deftest mcp-hat-maker-contract-test
  (testing "mcp-hat-maker returns a function"
    (let [hat-fn (mcp-hat-maker "mc" nil)]
      (is (fn? hat-fn))))

  (testing "hat-fn with existing bridge reuses it"
    (let [fake-bridge {:fake true}
          fake-cache {"tools" [{"name" "test_tool" "description" "Test"}]}
          context {:hats {:mcp {:bridge fake-bridge
                                :cache fake-cache}}}
          hat-fn (mcp-hat-maker "mc" nil)
          [ctx' fragment] (hat-fn context)]
      ;; Context unchanged (bridge reused)
      (is (= fake-bridge (get-in ctx' [:hats :mcp :bridge])))
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
  (testing "generates request schema from cache"
    (let [context {:hats {:mcp {:cache {"tools" [{"name" "test_tool"
                                                  "inputSchema" {"type" "object"}}]}}}}
          xition {"id" ["mc" "mc-mcp"]}
          schema (hat-mcp-request-schema-fn context xition)]
      (is (vector? schema))
      (is (= :map (first schema))))))

(deftest hat-mcp-response-schema-fn-test
  (testing "generates response schema from cache"
    (let [context {:hats {:mcp {:cache {"tools" [{"name" "test_tool"}]}}}}
          xition {"id" ["mc-mcp" "mc"]}
          schema (hat-mcp-response-schema-fn context xition)]
      (is (vector? schema))
      (is (= :map (first schema))))))

;;------------------------------------------------------------------------------
;; mcp-service-action Tests
;;------------------------------------------------------------------------------

(deftest mcp-service-action-test
  (testing "returns error when no bridge"
    (let [action-fn (mcp-service-action {} nil nil {"id" "svc"})
          result (atom nil)
          handler (fn [_ctx event] (reset! result event))
          context {}
          event {"id" ["mc" "svc"] "message" {"test" true}}]
      (action-fn context event [] handler)
      (is (= "No MCP bridge" (get-in @result ["message" "error"])))))

  (testing "routes single request to bridge"
    (let [;; Mock bridge that returns fixed response
          mock-bridge {:mock true}
          action-fn (mcp-service-action {} nil nil {"id" "svc"})
          result (atom nil)
          handler (fn [_ctx event] (reset! result event))
          context {:hats {:mcp {:bridge mock-bridge}}}
          event {"id" ["mc" "svc"] "message" {"jsonrpc" "2.0" "method" "test"}}]
      ;; Mock the bridge functions
      (with-redefs [bridge/send-and-await (fn [_b requests _timeout]
                                            (mapv (fn [_] {"result" "ok"}) requests))
                    bridge/drain-notifications (fn [_b] nil)]
        (action-fn context event [] handler)
        ;; Should return to caller with result
        (is (= ["svc" "mc"] (get @result "id")))
        (is (= {"result" "ok"} (get @result "message"))))))

  (testing "routes batch requests"
    (let [mock-bridge {:mock true}
          action-fn (mcp-service-action {} nil nil {"id" "svc"})
          result (atom nil)
          handler (fn [_ctx event] (reset! result event))
          context {:hats {:mcp {:bridge mock-bridge}}}
          event {"id" ["mc" "svc"] "message" [{"id" 1} {"id" 2}]}]
      (with-redefs [bridge/send-and-await (fn [_b requests _timeout]
                                            (mapv (fn [r] {"result" (get r "id")}) requests))
                    bridge/drain-notifications (fn [_b] nil)]
        (action-fn context event [] handler)
        ;; Should return vector of responses
        (is (vector? (get @result "message")))
        (is (= 2 (count (get @result "message"))))))))

;;------------------------------------------------------------------------------
;; mcp-hat-maker Init Path Tests (with mocks)
;;------------------------------------------------------------------------------

(deftest mcp-hat-maker-init-test
  (testing "initializes bridge when none exists"
    (let [fake-bridge {:fake-new true}
          fake-cache {"tools" [{"name" "new_tool"}]}]
      (with-redefs [bridge/init-bridge (fn [_config _opts]
                                         {:bridge fake-bridge :cache fake-cache})]
        (let [hat-fn (mcp-hat-maker "mc" nil)
              [ctx' fragment] (hat-fn {})]
          ;; Bridge stored in context
          (is (= fake-bridge (get-in ctx' [:hats :mcp :bridge])))
          ;; Cache stored
          (is (= fake-cache (get-in ctx' [:hats :mcp :cache])))
          ;; Stop hook registered
          (is (= 1 (count (get-in ctx' [:hats :stop-hooks]))))
          ;; Fragment generated
          (is (= "mc-mcp" (get-in fragment ["states" 0 "id"]))))))))

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
