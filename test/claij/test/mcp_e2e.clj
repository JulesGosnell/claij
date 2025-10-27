(ns claij.test.mcp-e2e
  "End-to-end integration test with real LLM and mock MCP server"
  (:require [claij.dsl.mcp.api :as mcp-api]
            [claij.new.interceptor.mcp-loop :as mcp-loop]
            [claij.test.openrouter-simple :as openrouter]
            [clojure.data.json :as json]))

;; =============================================================================
;; Setup
;; =============================================================================

(defn setup-mock-server
  "Initialize mock MCP server and generate DSL"
  []
  (println "Setting up mock MCP server...")
  (let [bridge-info (mcp-api/initialize-bridge-with-dsl
                     {:command "clojure"
                      :args ["-M" "-m" "claij.mcp.mock-server"]
                      :transport "stdio"}
                     'mock-server)]
    (println "✓ Mock server initialized")
    (println "  Bridge ID:" (:bridge-id bridge-info))
    (println "  Server:" (get-in bridge-info [:server-info :name]))
    (println "  Tools:" (mapv :name (:tools bridge-info)))
    bridge-info))

(defn teardown-mock-server
  "Shutdown mock MCP server"
  [bridge-info]
  (println "Shutting down mock server...")
  (claij.dsl.mcp/shutdown-bridge (:bridge-id bridge-info))
  (println "✓ Mock server shutdown"))

;; =============================================================================
;; LLM Integration
;; =============================================================================

(defn create-llm-fn
  "Create LLM function that calls OpenRouter"
  []
  (let [api-key (or (System/getenv "OPENROUTER_API_KEY")
                    (throw (ex-info "OPENROUTER_API_KEY not set" {})))]
    (fn [prompts]
      (println "\n--- LLM Call ---")
      (println "System prompt length:" (count (:system prompts)))
      (println "User message:" (:user prompts))
      
      (let [response (openrouter/call-openrouter
                      api-key
                      "anthropic/claude-3.5-sonnet"
                      (:system prompts)
                      (:user prompts)
                      {:response_format {:type "json_schema"
                                         :json_schema
                                         {:name "response"
                                          :schema
                                          {:type "object"
                                           :properties
                                           {:answer {:type "string"}
                                            :state {:type "string" :enum ["thinking" "ready"]}
                                            :mcp {:type "array" :items {:type "string"}}}
                                           :required ["answer" "state"]}}}})
            parsed (json/read-str (:content response) :key-fn keyword)]
        
        (println "LLM Response:")
        (println "  State:" (:state parsed))
        (println "  Answer:" (:answer parsed))
        (when (:mcp parsed)
          (println "  MCP calls:" (:mcp parsed)))
        
        parsed))))

;; =============================================================================
;; Test Scenarios
;; =============================================================================

(defn test-simple-echo
  "Test 1: Simple echo - LLM calls echo tool"
  [llm-fn]
  (println "\n" (apply str (repeat 60 "=")))
  (println "TEST 1: Simple Echo")
  (println (apply str (repeat 60 "=")))
  
  (let [result (mcp-loop/run-with-mcp-loop
                llm-fn
                "Use the mock-server/echo tool to echo the word 'SUCCESS'. Then tell me what the tool returned."
                [mcp-loop/mcp-loop-interceptor]
                {}
                {:system-prompt "You are a helpful assistant with access to tools."
                 :max-iterations 5})]
    
    (println "\n--- Final Result ---")
    (println "Answer:" (:answer result))
    (println "State:" (:state result))
    
    ;; Verify
    (assert (= "ready" (:state result)) "Should be in ready state")
    (assert (re-find #"(?i)success" (:answer result)) "Should mention SUCCESS")
    (println "✓ Test 1 PASSED")
    result))

(defn test-addition
  "Test 2: Addition - LLM calls add tool"
  [llm-fn]
  (println "\n" (apply str (repeat 60 "=")))
  (println "TEST 2: Addition")
  (println (apply str (repeat 60 "=")))
  
  (let [result (mcp-loop/run-with-mcp-loop
                llm-fn
                "Use the mock-server/add tool to calculate 15 + 27. Fill in the blank: The answer is ___."
                [mcp-loop/mcp-loop-interceptor]
                {}
                {:system-prompt "You are a helpful assistant with access to tools."
                 :max-iterations 5})]
    
    (println "\n--- Final Result ---")
    (println "Answer:" (:answer result))
    (println "State:" (:state result))
    
    ;; Verify
    (assert (= "ready" (:state result)) "Should be in ready state")
    (assert (re-find #"42" (:answer result)) "Should contain 42")
    (println "✓ Test 2 PASSED")
    result))

(defn test-greeting
  "Test 3: Greeting - LLM calls greet tool"
  [llm-fn]
  (println "\n" (apply str (repeat 60 "=")))
  (println "TEST 3: Greeting")
  (println (apply str (repeat 60 "=")))
  
  (let [result (mcp-loop/run-with-mcp-loop
                llm-fn
                "Use the mock-server/greet tool to greet 'Alice'. What did the tool say?"
                [mcp-loop/mcp-loop-interceptor]
                {}
                {:system-prompt "You are a helpful assistant with access to tools."
                 :max-iterations 5})]
    
    (println "\n--- Final Result ---")
    (println "Answer:" (:answer result))
    (println "State:" (:state result))
    
    ;; Verify
    (assert (= "ready" (:state result)) "Should be in ready state")
    (assert (re-find #"(?i)hello.*alice" (:answer result)) "Should contain greeting")
    (println "✓ Test 3 PASSED")
    result))

(defn test-multiple-calls
  "Test 4: Multiple calls - LLM chains multiple tool calls"
  [llm-fn]
  (println "\n" (apply str (repeat 60 "=")))
  (println "TEST 4: Multiple Tool Calls")
  (println (apply str (repeat 60 "=")))
  
  (let [result (mcp-loop/run-with-mcp-loop
                llm-fn
                (str "Do these in order:\n"
                     "1. Use mock-server/add to add 10 + 5\n"
                     "2. Use mock-server/add to add 20 + 10\n"
                     "Then tell me: What is the first result plus the second result?")
                [mcp-loop/mcp-loop-interceptor]
                {}
                {:system-prompt "You are a helpful assistant with access to tools."
                 :max-iterations 5})]
    
    (println "\n--- Final Result ---")
    (println "Answer:" (:answer result))
    (println "State:" (:state result))
    
    ;; Verify (15 + 30 = 45)
    (assert (= "ready" (:state result)) "Should be in ready state")
    (assert (re-find #"45" (:answer result)) "Should contain 45")
    (println "✓ Test 4 PASSED")
    result))

;; =============================================================================
;; Main Test Runner
;; =============================================================================

(defn run-e2e-tests
  "Run all end-to-end tests"
  []
  (println "\n")
  (println (apply str (repeat 60 "=")))
  (println "MCP END-TO-END INTEGRATION TESTS")
  (println "Real LLM + Real Mock MCP Server")
  (println (apply str (repeat 60 "=")))
  
  (let [bridge-info (setup-mock-server)
        llm-fn (create-llm-fn)]
    
    (try
      ;; Run tests
      (let [results [(test-simple-echo llm-fn)
                     (test-addition llm-fn)
                     (test-greeting llm-fn)
                     (test-multiple-calls llm-fn)]]
        
        (println "\n" (apply str (repeat 60 "=")))
        (println "ALL TESTS PASSED! ✓")
        (println (apply str (repeat 60 "=")))
        (println "\nSummary:")
        (println "  ✓ Simple echo test")
        (println "  ✓ Addition test (fill in blank)")
        (println "  ✓ Greeting test")
        (println "  ✓ Multiple calls test")
        
        {:success true
         :results results})
      
      (catch Exception e
        (println "\n" (apply str (repeat 60 "=")))
        (println "TEST FAILED ✗")
        (println (apply str (repeat 60 "=")))
        (println "Error:" (.getMessage e))
        (.printStackTrace e)
        
        {:success false
         :error (.getMessage e)})
      
      (finally
        (teardown-mock-server bridge-info)))))

(comment
  ;; Run the tests
  ;; Make sure OPENROUTER_API_KEY is set
  (run-e2e-tests))
