(ns claij.mcp.hat-test
  "Integration tests for MCP hat."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [claij.hat :refer [make-hat-registry register-hat don-hats]]
   [claij.mcp.hat :refer [mcp-hat-maker mcp-service-action]]
   [claij.mcp.bridge :as bridge]))

;;------------------------------------------------------------------------------
;; Task 6: Integration Tests
;;------------------------------------------------------------------------------

(deftest mcp-hat-maker-test
  (testing "mcp-hat-maker returns a hat-fn"
    (let [hat-fn (mcp-hat-maker "mc" nil)]
      (is (fn? hat-fn)))))

(deftest mcp-hat-fragment-structure-test
  (testing "fragment has correct structure with mock data"
    ;; Test fragment generation without starting real bridge
    (let [mock-mcp {:bridge nil :cache {"tools" [{"name" "tool1"} {"name" "tool2"}]}}
          context {:hats {:mcp mock-mcp}}
          hat-fn (mcp-hat-maker "mc" nil)
          [ctx' fragment] (hat-fn context)]
      ;; Should reuse existing, not modify context
      (is (= context ctx'))
      ;; Fragment structure
      (is (= 1 (count (get fragment "states"))))
      (is (= "mc-mcp" (get-in fragment ["states" 0 "id"])))
      (is (= "mcp-service" (get-in fragment ["states" 0 "action"])))
      (is (= 2 (count (get fragment "xitions"))))
      ;; Prompts mention tools
      (let [prompts (get fragment "prompts")]
        (is (some #(re-find #"2 MCP tools" %) prompts))
        (is (some #(re-find #"tool1" %) prompts))))))

(deftest mcp-hat-empty-tools-test
  (testing "fragment handles empty tools"
    (let [mock-mcp {:bridge nil :cache {"tools" []}}
          context {:hats {:mcp mock-mcp}}
          hat-fn (mcp-hat-maker "mc" nil)
          [_ fragment] (hat-fn context)]
      (let [prompts (get fragment "prompts")]
        (is (some #(re-find #"No MCP tools" %) prompts))))))

(deftest don-hats-with-mcp-test
  (testing "don-hats integrates mcp hat"
    ;; Use mock data to avoid starting real bridge
    (let [;; Create a test hat-maker that uses mock data
          mock-hat-maker (fn [state-id _config]
                           (fn [context]
                             (let [mock-mcp {:bridge :mock :cache {"tools" []}}]
                               [(assoc-in context [:hats :mcp] mock-mcp)
                                {"states" [{"id" (str state-id "-mcp") "action" "mcp-service"}]
                                 "xitions" []
                                 "prompts" ["Mock MCP"]}])))
          registry (-> (make-hat-registry)
                       (register-hat "mcp" mock-hat-maker))
          fsm {"id" "test"
               "states" [{"id" "mc" "hats" ["mcp"]}]
               "xitions" []}
          [ctx' fsm'] (don-hats {} fsm registry)]
      ;; Context has MCP data
      (is (= :mock (get-in ctx' [:hats :mcp :bridge])))
      ;; FSM has new state
      (is (= 2 (count (get fsm' "states"))))
      (is (some #(= "mc-mcp" (get % "id")) (get fsm' "states"))))))
