(ns claij.llm.llm-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :refer [chan go-loop <! >!! <!! timeout]]
            [claij.bridge :refer [start-mcp-bridge]]))

(deftest test-mcp-agent
  (testing "MCP agent read and echo sequence"
    (let [config {:command "bash" :args [] :transport "stdio"}
          input-chan (chan)
          output-chan (chan)
          outputs (atom [])
          stop (start-mcp-bridge config input-chan output-chan)]
      (go-loop []
        (when-some [msg (<! output-chan)]
          (swap! outputs conj msg)
          (recur)))
      (>!! input-chan "read x")
      (>!! input-chan "x")
      (>!! input-chan "echo $x")
      (<!! (timeout 200))
      (is (= "x" (last @outputs)) "Should receive 'x' after read x and echo")
      (>!! input-chan "read y")
      (>!! input-chan "y")
      (>!! input-chan "echo $y")
      (<!! (timeout 200))
      (is (= "y" (last @outputs)) "Should receive 'y' after read y and echo")
      (stop)))

  (testing "MCP agent invalid config"
    (is (thrown? IllegalArgumentException (start-mcp-bridge {:command "" :args [] :transport "stdio"} (chan) (chan)))
        "Empty command should throw")
    (is (thrown? IllegalArgumentException (start-mcp-bridge {:command "bash" :args "not-a-vector" :transport "stdio"} (chan) (chan)))
        "Non-vector args should throw")
    (is (thrown? IllegalArgumentException (start-mcp-bridge {:command "bash" :args [] :transport nil} (chan) (chan)))
        "Nil transport should throw"))

  (testing "MCP agent empty input"
    (let [config {:command "bash" :args [] :transport "stdio"}
          input-chan (chan)
          output-chan (chan)
          outputs (atom [])
          stop (start-mcp-bridge config input-chan output-chan)]
      (go-loop []
        (when-some [msg (<! output-chan)]
          (swap! outputs conj msg)
          (recur)))
      (>!! input-chan "")
      (>!! input-chan "echo hello")
      (<!! (timeout 200))
      (is (= "hello" (last @outputs)) "Empty input should not crash, echo should work")
      (stop)))

  (testing "MCP agent stop immediately"
    (let [config {:command "bash" :args [] :transport "stdio"}
          input-chan (chan)
          output-chan (chan)
          stop (start-mcp-bridge config input-chan output-chan)]
      (stop)
      (is (nil? (<!! (timeout 200))) "Stop should clean up without errors")))

  (testing "MCP agent multiple output lines"
    (let [config {:command "bash" :args [] :transport "stdio"}
          input-chan (chan)
          output-chan (chan)
          outputs (atom [])
          stop (start-mcp-bridge config input-chan output-chan)]
      (go-loop []
        (when-some [msg (<! output-chan)]
          (swap! outputs conj msg)
          (recur)))
      (>!! input-chan "ls")
      (<!! (timeout 200))
      (is (pos? (count @outputs)) "Should receive at least one line from ls")
      (stop))))
