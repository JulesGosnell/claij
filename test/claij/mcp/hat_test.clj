(ns claij.mcp.hat-test
  "Tests for MCP Hat functionality."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clojure.tools.logging :as log]
   [claij.hat :as hat]
   [claij.mcp.hat :refer [mcp-hat-maker mcp-service-action]]
   [claij.mcp.bridge :as bridge]))

(defn quiet-logging [f]
  (if (System/getenv "DEBUG")
    (f)
    (binding [log/*logger-factory* clojure.tools.logging.impl/disabled-logger-factory]
      (f))))

(use-fixtures :once quiet-logging)

;;------------------------------------------------------------------------------
;; Unit Tests
;;------------------------------------------------------------------------------

(deftest mcp-hat-maker-contract-test
  (testing "mcp-hat-maker returns a hat-fn"
    (let [hat-fn (hat/make-hat mcp-hat-maker "mc" nil)]
      (is (fn? hat-fn) "Should return a function"))))

(deftest generate-fragment-structure-test
  (testing "fragment has correct structure with mock cache"
    ;; We can't call the hat-fn directly without starting bridge,
    ;; but we can test the fragment generation via a mock
    (let [mock-cache {"tools" [{"name" "read_file" "description" "Read a file"}
                               {"name" "write_file" "description" "Write a file"}]}
          ;; Use internal fn via with-redefs to test fragment generation
          fragment (#'claij.mcp.hat/generate-fragment "mc" "mc-mcp" mock-cache)]

      ;; Check states
      (is (= 1 (count (get fragment "states"))))
      (is (= "mc-mcp" (get-in fragment ["states" 0 "id"])))
      (is (= "mcp-service" (get-in fragment ["states" 0 "action"])))

      ;; Check xitions (loopback)
      (is (= 2 (count (get fragment "xitions"))))
      (is (= ["mc" "mc-mcp"] (get-in fragment ["xitions" 0 "id"])))
      (is (= ["mc-mcp" "mc"] (get-in fragment ["xitions" 1 "id"])))

      ;; Check prompts
      (is (seq (get fragment "prompts")))
      (is (some #(re-find #"2 MCP tools" %) (get fragment "prompts"))))))

(deftest tool-prompts-test
  (testing "prompts format tools correctly"
    (let [tools [{"name" "foo" "description" "Does foo"}
                 {"name" "bar" "description" nil}]
          prompts (#'claij.mcp.hat/generate-tool-prompts tools)]
      (is (= 2 (count prompts)))
      (is (re-find #"2 MCP tools" (first prompts)))
      (is (re-find #"foo: Does foo" (second prompts)))
      (is (re-find #"bar: No description" (second prompts)))))

  (testing "empty tools"
    (let [prompts (#'claij.mcp.hat/generate-tool-prompts [])]
      (is (= ["No MCP tools available."] prompts)))))

;;------------------------------------------------------------------------------
;; Integration Tests (require real MCP server)
;;------------------------------------------------------------------------------

(deftest ^:integration ^:long-running mcp-hat-integration-test
  (testing "MCP hat initializes bridge and populates cache"
    (let [hat-fn (hat/make-hat mcp-hat-maker "mc" nil)
          [ctx fragment] (hat-fn {})]
      (try
        ;; Bridge should be stored in context
        (is (some? (get-in ctx [:hats :mcp :bridge])))
        (is (some? (get-in ctx [:hats :mcp :cache])))

        ;; Stop hook should be registered
        (is (seq (get-in ctx [:hats :stop-hooks])))

        ;; Fragment should have tools from real server
        (let [tools (get-in ctx [:hats :mcp :cache "tools"])]
          (is (seq tools) "Should have tools from MCP server"))

        ;; Prompts should reference actual tool count
        (is (seq (get fragment "prompts")))

        (finally
          ;; Clean up bridge
          (hat/run-stop-hooks ctx))))))

(deftest ^:integration ^:long-running mcp-hat-reuse-test
  (testing "Second state with MCP hat reuses existing bridge"
    (let [hat-fn-1 (hat/make-hat mcp-hat-maker "mc" nil)
          hat-fn-2 (hat/make-hat mcp-hat-maker "worker" nil)
          [ctx1 _] (hat-fn-1 {})
          bridge1 (get-in ctx1 [:hats :mcp :bridge])
          [ctx2 _] (hat-fn-2 ctx1) ;; Pass ctx1 to second hat
          bridge2 (get-in ctx2 [:hats :mcp :bridge])]
      (try
        ;; Should be same bridge
        (is (= bridge1 bridge2) "Should reuse same bridge")

        ;; Should only have one stop hook
        (is (= 1 (count (get-in ctx2 [:hats :stop-hooks]))))

        (finally
          (hat/run-stop-hooks ctx2))))))
