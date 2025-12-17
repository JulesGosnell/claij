(ns claij.hat.mcp-test
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [claij.hat :as hat]
   [claij.hat.mcp :refer [mcp-hat-maker format-tools-prompt mcp-service-action]]
   [claij.mcp.bridge :as bridge]))

;;------------------------------------------------------------------------------
;; Prompt Generation Tests (no bridge needed)
;;------------------------------------------------------------------------------

(deftest format-tools-prompt-test
  (testing "formats empty tools list"
    (is (= "No MCP tools available." (format-tools-prompt []))))

  (testing "formats single tool"
    (let [tools [{"name" "read_file"
                  "description" "Read a file"
                  "inputSchema" {"type" "object"
                                 "properties" {"path" {"type" "string"}}}}]
          prompt (format-tools-prompt tools)]
      (is (clojure.string/includes? prompt "read_file"))
      (is (clojure.string/includes? prompt "Read a file"))
      (is (clojure.string/includes? prompt "tool_calls"))))

  (testing "formats multiple tools"
    (let [tools [{"name" "tool1" "description" "First"}
                 {"name" "tool2" "description" "Second"}]
          prompt (format-tools-prompt tools)]
      (is (clojure.string/includes? prompt "tool1"))
      (is (clojure.string/includes? prompt "tool2")))))

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
      ;; Action registered
      (is (= mcp-service-action (get-in ctx' [:id->action "mcp-service"])))
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
