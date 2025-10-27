(ns claij.dsl.mcp-test
  "Tests for the MCP DSL"
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [claij.dsl.mcp :as mcp]))

;; =============================================================================
;; Test Fixtures
;; =============================================================================

(defn cleanup-bridges
  "Fixture to cleanup all bridges after each test"
  [f]
  (try
    (f)
    (finally
      ;; Shutdown all bridges
      (doseq [{:keys [bridge-id]} (mcp/list-bridges)]
        (mcp/shutdown-bridge bridge-id)))))

(use-fixtures :each cleanup-bridges)

;; =============================================================================
;; Tests
;; =============================================================================

(deftest ^:integration test-initialize-bridge
  (testing "Initialize a mock MCP server bridge"
    (let [bridge-info (mcp/initialize-bridge
                       {:command "clojure"
                        :args ["-M" "-m" "claij.mcp.mock-server"]
                        :transport "stdio"})]
      
      ;; Check bridge info structure
      (is (keyword? (:bridge-id bridge-info)))
      (is (map? (:server-info bridge-info)))
      (is (= "mock-mcp-server" (get-in bridge-info [:server-info :name])))
      (is (= "1.0.0" (get-in bridge-info [:server-info :version])))
      
      ;; Check tools
      (is (vector? (:tools bridge-info)))
      (is (= 3 (count (:tools bridge-info))))
      
      ;; Verify each tool has required fields
      (doseq [tool (:tools bridge-info)]
        (is (string? (:name tool)))
        (is (string? (:description tool)))
        (is (map? (:inputSchema tool)))
        (is (map? (:outputSchema tool))))
      
      ;; Verify specific tools exist
      (let [tool-names (set (map :name (:tools bridge-info)))]
        (is (contains? tool-names "echo"))
        (is (contains? tool-names "add"))
        (is (contains? tool-names "greet"))))))

(deftest ^:integration test-initialize-with-custom-id
  (testing "Initialize bridge with custom ID"
    (let [bridge-info (mcp/initialize-bridge
                       {:command "clojure"
                        :args ["-M" "-m" "claij.mcp.mock-server"]
                        :transport "stdio"}
                       {:bridge-id :my-custom-bridge})]
      
      (is (= :my-custom-bridge (:bridge-id bridge-info))))))

(deftest ^:integration test-list-bridges
  (testing "List all registered bridges"
    ;; Initially empty
    (is (empty? (mcp/list-bridges)))
    
    ;; Initialize one bridge
    (let [bridge1 (mcp/initialize-bridge
                   {:command "clojure"
                    :args ["-M" "-m" "claij.mcp.mock-server"]
                    :transport "stdio"})]
      
      (let [bridges (mcp/list-bridges)]
        (is (= 1 (count bridges)))
        (is (= (:bridge-id bridge1) (:bridge-id (first bridges))))
        (is (= 3 (count (:tools (first bridges)))))
        
        ;; Tools should have minimal info (name and description only)
        (let [tool (first (:tools (first bridges)))]
          (is (contains? tool :name))
          (is (contains? tool :description))
          (is (not (contains? tool :inputSchema)))
          (is (not (contains? tool :outputSchema))))))))

(deftest ^:integration test-call-echo
  (testing "Call echo tool"
    (let [bridge-info (mcp/initialize-bridge
                       {:command "clojure"
                        :args ["-M" "-m" "claij.mcp.mock-server"]
                        :transport "stdio"})
          result (mcp/call (:bridge-id bridge-info) "echo" {:text "Test message"})]
      
      (is (= "Test message" (:result result))))))

(deftest ^:integration test-call-add
  (testing "Call add tool"
    (let [bridge-info (mcp/initialize-bridge
                       {:command "clojure"
                        :args ["-M" "-m" "claij.mcp.mock-server"]
                        :transport "stdio"})]
      
      (is (= 8 (:result (mcp/call (:bridge-id bridge-info) "add" {:a 5 :b 3}))))
      (is (= 100 (:result (mcp/call (:bridge-id bridge-info) "add" {:a 42 :b 58}))))
      (is (= 6.2 (:result (mcp/call (:bridge-id bridge-info) "add" {:a 2.5 :b 3.7})))))))

(deftest ^:integration test-call-greet
  (testing "Call greet tool"
    (let [bridge-info (mcp/initialize-bridge
                       {:command "clojure"
                        :args ["-M" "-m" "claij.mcp.mock-server"]
                        :transport "stdio"})
          result (mcp/call (:bridge-id bridge-info) "greet" {:name "Alice"})]
      
      (is (= "Hello, Alice!" (:result result))))))

(deftest ^:integration test-call-multiple-times
  (testing "Multiple calls to the same bridge"
    (let [bridge-info (mcp/initialize-bridge
                       {:command "clojure"
                        :args ["-M" "-m" "claij.mcp.mock-server"]
                        :transport "stdio"})
          bridge-id (:bridge-id bridge-info)]
      
      ;; Make several calls
      (is (= "one" (:result (mcp/call bridge-id "echo" {:text "one"}))))
      (is (= 10 (:result (mcp/call bridge-id "add" {:a 7 :b 3}))))
      (is (= "Hello, Bob!" (:result (mcp/call bridge-id "greet" {:name "Bob"}))))
      (is (= "two" (:result (mcp/call bridge-id "echo" {:text "two"}))))
      (is (= 99 (:result (mcp/call bridge-id "add" {:a 50 :b 49})))))))

(deftest ^:integration test-call-unknown-tool
  (testing "Calling unknown tool throws error"
    (let [bridge-info (mcp/initialize-bridge
                       {:command "clojure"
                        :args ["-M" "-m" "claij.mcp.mock-server"]
                        :transport "stdio"})]
      
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"MCP tool call failed"
           (mcp/call (:bridge-id bridge-info) "nonexistent" {}))))))

(deftest ^:integration test-call-nonexistent-bridge
  (testing "Calling tool on nonexistent bridge throws error"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Bridge not found"
         (mcp/call :nonexistent-bridge "echo" {:text "test"})))))

(deftest ^:integration test-shutdown-bridge
  (testing "Shutdown bridge removes it from registry"
    (let [bridge-info (mcp/initialize-bridge
                       {:command "clojure"
                        :args ["-M" "-m" "claij.mcp.mock-server"]
                        :transport "stdio"})
          bridge-id (:bridge-id bridge-info)]
      
      ;; Bridge should be registered
      (is (= 1 (count (mcp/list-bridges))))
      
      ;; Shutdown
      (mcp/shutdown-bridge bridge-id)
      
      ;; Bridge should be gone
      (is (empty? (mcp/list-bridges)))
      
      ;; Calling tool should fail
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Bridge not found"
           (mcp/call bridge-id "echo" {:text "test"}))))))

(deftest ^:integration test-multiple-bridges
  (testing "Can initialize and use multiple bridges simultaneously"
    (let [bridge1 (mcp/initialize-bridge
                   {:command "clojure"
                    :args ["-M" "-m" "claij.mcp.mock-server"]
                    :transport "stdio"}
                   {:bridge-id :bridge-1})
          bridge2 (mcp/initialize-bridge
                   {:command "clojure"
                    :args ["-M" "-m" "claij.mcp.mock-server"]
                    :transport "stdio"}
                   {:bridge-id :bridge-2})]
      
      ;; Both bridges should be registered
      (is (= 2 (count (mcp/list-bridges))))
      
      ;; Can call tools on both
      (is (= "hello" (:result (mcp/call :bridge-1 "echo" {:text "hello"}))))
      (is (= "world" (:result (mcp/call :bridge-2 "echo" {:text "world"}))))
      
      ;; Shutdown one
      (mcp/shutdown-bridge :bridge-1)
      (is (= 1 (count (mcp/list-bridges))))
      
      ;; First bridge should fail
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Bridge not found"
           (mcp/call :bridge-1 "echo" {:text "test"})))
      
      ;; Second bridge should still work
      (is (= "still works" (:result (mcp/call :bridge-2 "echo" {:text "still works"})))))))
