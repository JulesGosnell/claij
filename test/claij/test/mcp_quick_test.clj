(ns claij.test.mcp-quick-test
  "Quick test of MCP components without real LLM"
  (:require [claij.dsl.mcp.codegen :as codegen]
            [claij.dsl.mcp.introspect :as introspect]
            [claij.new.interceptor.mcp-loop :as mcp-loop]))

(defn test-codegen
  "Test code generation"
  []
  (println "\n=== Testing Code Generation ===")
  (let [tools [{:name "test-tool"
                :description "A test tool"
                :inputSchema
                {:type "object"
                 :properties {:arg1 {:type "string" :description "First argument"}}
                 :required ["arg1"]}}]
        code (codegen/generate-dsl-namespace 'test-ns :test-bridge tools)]
    (println "✓ Generated" (count code) "chars of code")
    (assert (re-find #"defn test-tool" code))
    (assert (re-find #"A test tool" code))
    :pass))

(defn test-introspection
  "Test introspection"
  []
  (println "\n=== Testing Introspection ===")
  (let [fn-desc {:name 'my.ns/my-fn
                 :arglists '([x y])
                 :doc "Test function"}
        sig (introspect/format-function-signature fn-desc)]
    (println "✓ Formatted signature:" sig)
    (assert (re-find #"my.ns/my-fn x y" sig))
    (assert (re-find #"Test function" sig))
    :pass))

(defn test-interceptor
  "Test MCP interceptor with mock LLM"
  []
  (println "\n=== Testing MCP Interceptor ===")
  
  ;; Mock LLM that simulates the loop
  (def call-count (atom 0))
  (defn mock-llm [prompts]
    (swap! call-count inc)
    (println "  Mock LLM call" @call-count)
    (if (= @call-count 1)
      ;; First call - request tool
      {:answer "I need to call a tool"
       :state "thinking"
       :mcp ["(+ 1 2)"]}
      ;; Second call - has results
      {:answer "The result is 3"
       :state "ready"}))
  
  (reset! call-count 0)
  (let [result (mcp-loop/run-with-mcp-loop
                mock-llm
                "Test message"
                [mcp-loop/mcp-loop-interceptor]
                {}
                {:system-prompt "Test"})]
    
    (println "✓ Loop completed in" @call-count "iterations")
    (println "  Final state:" (:state result))
    (println "  Final answer:" (:answer result))
    
    (assert (= 2 @call-count) "Should have called LLM twice")
    (assert (= "ready" (:state result)))
    (assert (= "The result is 3" (:answer result)))
    :pass))

(defn run-quick-tests
  "Run all quick tests"
  []
  (println "\n========================================")
  (println "  MCP Quick Component Tests")
  (println "========================================")
  
  (let [results [(test-codegen)
                 (test-introspection)
                 (test-interceptor)]]
    
    (if (every? #(= :pass %) results)
      (do
        (println "\n========================================")
        (println "  ALL QUICK TESTS PASSED")
        (println "========================================")
        (println "\nReady for E2E testing with real LLM!")
        true)
      (do
        (println "\nSOME TESTS FAILED")
        false))))

(comment
  (run-quick-tests))
