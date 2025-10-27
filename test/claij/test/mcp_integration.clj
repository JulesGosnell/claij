(ns claij.test.mcp-integration
  "End-to-end integration test for MCP DSL"
  (:require [claij.dsl.mcp.codegen :as codegen]
            [claij.dsl.mcp.introspect :as introspect]
            [claij.new.interceptor.mcp :as mcp-int]))

;; Mock tools matching the mock server
(def mock-tools
  [{:name "echo"
    :description "Echoes back the input text"
    :inputSchema
    {:type "object"
     :properties {:text {:type "string" :description "Text to echo"}}
     :required ["text"]}}
   
   {:name "add"
    :description "Adds two numbers together"
    :inputSchema
    {:type "object"
     :properties {:a {:type "number" :description "First number"}
                  :b {:type "number" :description "Second number"}}
     :required ["a" "b"]}}
   
   {:name "greet"
    :description "Greets a person by name"
    :inputSchema
    {:type "object"
     :properties {:name {:type "string" :description "Name to greet"}}
     :required ["name"]}}])

(defn test-codegen []
  (println "\n=== Testing Code Generation ===")
  (let [code (codegen/generate-dsl-namespace 'mock-server :bridge-1 mock-tools)]
    (println "Generated code length:" (count code))
    (println "Contains echo?:" (boolean (re-find #"defn echo" code)))
    (println "Contains add?:" (boolean (re-find #"defn add" code)))
    (println "Contains greet?:" (boolean (re-find #"defn greet" code)))
    (println "\nGenerated code:")
    (println code)
    :pass))

(defn test-introspection []
  (println "\n=== Testing Introspection ===")
  ;; Clean up if exists
  (when (find-ns 'test-mcp-ns)
    (remove-ns 'test-mcp-ns))
  
  ;; Create a test namespace (but don't try to execute the functions)
  (let [ns-code (codegen/generate-dsl-namespace 'test-mcp-ns :test-bridge mock-tools)]
    (println "Generated namespace code")
    ;; We can't actually load it without the mcp module working
    ;; But we can test the introspection on existing namespaces
    (println "Testing format-function-signature...")
    (let [fn-desc {:name 'my.ns/my-fn
                   :arglists '([x y])
                   :doc "Test function"}
          sig (introspect/format-function-signature fn-desc)]
      (println "  Signature:" sig)
      (assert (re-find #"my.ns/my-fn x y" sig))
      (assert (re-find #"Test function" sig))))
  :pass)

(defn test-interceptor-schema []
  (println "\n=== Testing MCP Interceptor Schema ===")
  (let [base-schema {:properties {:answer {:type "string"}}}
        [updated-schema _ctx] ((:pre-schema mcp-int/mcp-interceptor) base-schema {})]
    (println "Base schema keys:" (keys (:properties base-schema)))
    (println "Updated schema keys:" (keys (:properties updated-schema)))
    (assert (contains? (:properties updated-schema) :mcp))
    (println "✓ mcp field added to schema"))
  :pass)

(defn test-interceptor-prompt []
  (println "\n=== Testing MCP Interceptor Prompt ===")
  (let [prompts {:system "System" :user "User"}
        [updated-prompts _ctx] ((:pre-prompt mcp-int/mcp-interceptor) prompts {})]
    (println "System prompt length:" (count (:system updated-prompts)))
    (assert (re-find #"MCP TOOL ACCESS" (:system updated-prompts)))
    (assert (re-find #"Available MCP Services" (:system updated-prompts)))
    (println "✓ MCP explanation added to prompt"))
  :pass)

(defn test-mcp-execution []
  (println "\n=== Testing MCP Code Execution ===")
  ;; Test the private execute-mcp-code function
  (let [execute-fn (ns-resolve 'claij.new.interceptor.mcp 'execute-mcp-code)
        result1 (execute-fn "(+ 1 2)")
        result2 (execute-fn "(* 3 4)")
        result3 (execute-fn "(invalid syntax")]
    (println "Result 1 (+ 1 2):" result1)
    (assert (:success result1))
    (assert (= 3 (:result result1)))
    
    (println "Result 2 (* 3 4):" result2)
    (assert (:success result2))
    (assert (= 12 (:result result2)))
    
    (println "Result 3 (invalid):" result3)
    (assert (false? (:success result3)))
    (assert (string? (:error result3)))
    (println "✓ Code execution working"))
  :pass)

(defn run-all-tests []
  (println "===========================================")
  (println "MCP DSL Integration Test Suite")
  (println "===========================================")
  
  (let [results [(test-codegen)
                 (test-introspection)
                 (test-interceptor-schema)
                 (test-interceptor-prompt)
                 (test-mcp-execution)]]
    (println "\n===========================================")
    (if (every? #(= :pass %) results)
      (do
        (println "✓ ALL TESTS PASSED")
        (println "===========================================")
        true)
      (do
        (println "✗ SOME TESTS FAILED")
        (println "===========================================")
        false))))

(comment
  (run-all-tests))
