(ns claij.mcp.bridge-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.core.async :refer [chan go-loop <! >!! <!! timeout]]
   [claij.mcp.bridge :refer [start-process-bridge json-string? start-mcp-bridge]]))

(deftest bridge-test

  (testing "start-process-bridge:"
    (testing "Process read and echo sequence"
      (let [input-chan (chan)
            output-chan (timeout 200)
            stop (start-process-bridge "bash" [] input-chan output-chan)]
        (>!! input-chan "read x")
        (>!! input-chan "x")
        (>!! input-chan "echo $x")
        (is (= "x" (<!! output-chan)) "Should receive 'x' after read x and echo")
        (>!! input-chan "read y")
        (>!! input-chan "y")
        (>!! input-chan "echo $y")
        (is (= "y" (<!! output-chan)) "Should receive 'y' after read y and echo")
        (stop)))

    (testing "MCP agent empty input"
      (let [input-chan (chan)
            output-chan (timeout 200)
            stop (start-process-bridge "bash" [] input-chan output-chan)]
        (>!! input-chan "")
        (>!! input-chan "echo hello")
        (is (= "hello" (<!! output-chan)) "Empty input should not crash, echo should work")
        (stop)))

    (testing "Process stop immediately"
      (let [input-chan (chan)
            output-chan (chan)
            stop (start-process-bridge "bash" [] input-chan output-chan)]
        (stop)
        (is (nil? (<!! (timeout 200))) "Stop should clean up without errors")))

    (testing "Process multiple output lines"
      (let [input-chan (chan)
            output-chan (chan)
            outputs (atom [])
            stop (start-process-bridge "bash" [] input-chan output-chan)]
        (go-loop []
          (when-some [msg (<! output-chan)]
            (swap! outputs conj msg)
            (recur)))
        (>!! input-chan "ls")
        (<!! (timeout 200))
        (is (pos? (count @outputs)) "Should receive at least one line from ls")
        (stop))))

  (testing "json-string?"
    (testing "valid JSON objects"
      (is (json-string? "{}") "empty object")
      (is (json-string? "{\"key\": \"value\"}") "simple object")
      (is (json-string? "  {\"key\": 123}") "leading whitespace")
      (is (json-string? "\t{\"nested\": {}}") "tab whitespace")
      (is (json-string? "\n{\"array\": [1,2,3]}") "newline whitespace"))

    (testing "invalid JSON (not objects)"
      (is (not (json-string? "foo")) "plain string")
      (is (not (json-string? "")) "empty string")
      (is (not (json-string? "[]")) "array")
      (is (not (json-string? "123")) "number")
      (is (not (json-string? "null")) "null")
      (is (not (json-string? "\"string\"")) "quoted string")))

  (testing "start-mcp-bridge"
    (testing "valid stdio config"
      ;; Note: Uses string keys to match code expectations
      (let [input-chan (chan)
            output-chan (chan)
            stop (start-mcp-bridge {"command" "echo" "args" ["hello"] "transport" "stdio"}
                                   input-chan output-chan)]
        (is (fn? stop) "Should return a stop function")
        (stop)))

    (testing "invalid config - empty command"
      (is (thrown? IllegalArgumentException
                   (start-mcp-bridge {"command" "" "args" [] "transport" "stdio"} (chan) (chan)))
          "Empty command should throw"))

    (testing "invalid config - nil command"
      (is (thrown? IllegalArgumentException
                   (start-mcp-bridge {"command" nil "args" [] "transport" "stdio"} (chan) (chan)))
          "Nil command should throw"))

    (testing "invalid config - non-vector args"
      (is (thrown? IllegalArgumentException
                   (start-mcp-bridge {"command" "bash" "args" "not-a-vector" "transport" "stdio"} (chan) (chan)))
          "Non-vector args should throw"))

    (testing "nil args is allowed"
      (let [input-chan (chan)
            output-chan (chan)
            stop (start-mcp-bridge {"command" "echo" "args" nil "transport" "stdio"}
                                   input-chan output-chan)]
        (is (fn? stop) "nil args should be allowed")
        (stop)))

    (testing "unsupported transport throws"
      (is (thrown? IllegalArgumentException
                   (start-mcp-bridge {"command" "bash" "args" [] "transport" "http"} (chan) (chan)))
          "Unsupported transport should throw"))))
