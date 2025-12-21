(ns claij.graph-test
  "Unit tests for claij.graph - FSM to Graphviz DOT conversion.
   
   These tests verify that:
   1. DOT format structure is correct (digraph, node shapes, edge arrows)
   2. FSM content (IDs, descriptions, prompts) appears in output"
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.string :refer [includes?]]
   [claij.graph :refer [fsm->dot fsm->dot-with-hats]]))

;; DOT format constants - these are Graphviz standard syntax elements
(def ^:private dot-digraph-prefix "digraph")
(def ^:private dot-special-node-shape "shape=doublecircle")
(def ^:private dot-edge-arrow "->")
(def ^:private dot-label-attr "[label=")

(deftest graph-test

  (testing "fsm->dot"

    (testing "minimal FSM produces valid DOT structure"
      (let [fsm {"id" "test-fsm"}
            dot (fsm->dot fsm)]
        ;; DOT format elements
        (is (includes? dot dot-digraph-prefix)
            "Should use digraph declaration")
        (is (includes? dot "\"test-fsm\"")
            "FSM id should be quoted in digraph name")
        ;; Special start/end nodes (quoted IDs)
        (is (includes? dot (str "\"start\" [" dot-special-node-shape))
            "Start node should have doublecircle shape")
        (is (includes? dot (str "\"end\"   [" dot-special-node-shape))
            "End node should have doublecircle shape")))

    (testing "FSM description appears in output"
      (let [fsm {"id" "desc-fsm" "description" "A test FSM"}
            dot (fsm->dot fsm)]
        (is (includes? dot "A test FSM")
            "Description should appear in DOT output")))

    (testing "FSM prompts appear as title fallback"
      (let [fsm {"id" "prompt-fsm" "prompts" ["Line 1" "Line 2"]}
            dot (fsm->dot fsm)]
        (is (includes? dot "Line 1"))
        (is (includes? dot "Line 2"))))

    (testing "FSM states render with labels"
      (let [fsm {"id" "state-fsm"
                 "states" [{"id" "processing" "action" "process"}
                           {"id" "waiting"}]}
            dot (fsm->dot fsm)]
        (is (includes? dot "\"processing\" [label=\"processing")
            "State should have label with its id")
        (is (includes? dot "(process)")
            "Action should appear in state label")
        (is (includes? dot "\"waiting\" [label=\"waiting")
            "States without action also render")))

    (testing "state description takes precedence over id"
      (let [fsm {"id" "desc-state-fsm"
                 "states" [{"id" "stt" "description" "Speech to Text" "action" "openapi-call"}]}
            dot (fsm->dot fsm)]
        (is (includes? dot "\"stt\" [label=\"Speech to Text")
            "Description should be used as label instead of id")))

    (testing "state prompts appear in labels"
      (let [fsm {"id" "prompted-fsm"
                 "states" [{"id" "reviewer" "prompts" ["Review code"]}]}
            dot (fsm->dot fsm)]
        (is (includes? dot "Review code")
            "State prompts should appear in DOT output")))

    (testing "start and end states are not duplicated"
      (let [fsm {"id" "special-fsm"
                 "states" [{"id" "start"} {"id" "end"} {"id" "middle"}]}
            dot (fsm->dot fsm)]
        (is (includes? dot "\"middle\" [label=")
            "Regular states should render")
        (is (= 1 (count (re-seq #"\"start\" \[shape=doublecircle" dot)))
            "Start should appear exactly once (as special node)")))

    (testing "transitions render as edges"
      (let [fsm {"id" "xition-fsm"
                 "xitions" [{"id" ["start" "processing"]}
                            {"id" ["processing" "end"]}]}
            dot (fsm->dot fsm)]
        (is (includes? dot (str "\"start\" " dot-edge-arrow " \"processing\""))
            "Transition should render as DOT edge")
        (is (includes? dot (str "\"processing\" " dot-edge-arrow " \"end\""))
            "Multiple transitions should all render")))

    (testing "transition label appears as edge attribute"
      (let [fsm {"id" "labeled-fsm"
                 "xitions" [{"id" ["a" "b"] "label" "go next"}]}
            dot (fsm->dot fsm)]
        (is (includes? dot "[label=\"go next\"]")
            "Transition label should be edge attribute")))

    (testing "transition description appears in output"
      (let [fsm {"id" "desc-xition-fsm"
                 "xitions" [{"id" ["a" "b"] "description" "transition desc"}]}
            dot (fsm->dot fsm)]
        (is (includes? dot "transition desc")
            "Transition description should appear")))

    (testing "transition uses 'to' state as fallback label"
      (let [fsm {"id" "fallback-fsm"
                 "xitions" [{"id" ["a" "done"]}]}
            dot (fsm->dot fsm)]
        (is (includes? dot "[label=\"done\"]")
            "Should use destination state as label when no explicit label"))))

  (testing "fsm->dot-with-hats"

    (testing "expands mcp hat to service state with loopback transitions"
      (let [fsm {"id" "hat-fsm"
                 "description" "Test FSM with MCP hat"
                 "states" [{"id" "llm" "action" "llm" "hats" ["mcp"]}]
                 "xitions" [{"id" ["start" "llm"]}
                            {"id" ["llm" "end"]}]}
            dot (fsm->dot-with-hats fsm)]
        ;; Should have original state
        (is (includes? dot "\"llm\" [label=")
            "Original state should appear")
        ;; Should have hat label on state
        (is (includes? dot "[mcp]")
            "Hat name should appear in state label")
        ;; Should have hat-generated MCP service state
        (is (includes? dot "\"llm-mcp\" [label=\"MCP Tools")
            "MCP service state should be generated")
        (is (includes? dot "(mcp-service)")
            "MCP service should have mcp-service action")
        ;; Should have loopback transitions
        (is (includes? dot "[label=\"tool-call\"]")
            "Tool-call transition should be generated")
        (is (includes? dot "[label=\"tool-result\"]")
            "Tool-result transition should be generated")
        ;; Should have cluster for hat states
        (is (includes? dot "subgraph cluster_llm")
            "Hat states should be in a cluster")
        (is (includes? dot "label=\"llm hats\"")
            "Cluster should have label")
        ;; FSM description should appear
        (is (includes? dot "Test FSM with MCP hat")
            "FSM description should appear in title")))

    (testing "expands mcp hat with config (map form)"
      (let [fsm {"id" "config-hat-fsm"
                 "states" [{"id" "chairman"
                            "action" "llm"
                            "hats" [{:mcp {:servers {"github" {:config {}}}}}]}]
                 "xitions" [{"id" ["start" "chairman"]}
                            {"id" ["chairman" "end"]}]}
            dot (fsm->dot-with-hats fsm)]
        ;; Should expand mcp hat even with config
        (is (includes? dot "\"chairman-mcp\"")
            "MCP service state should be generated for chairman")
        (is (includes? dot "subgraph cluster_chairman")
            "Chairman hat cluster should exist")))

    (testing "non-mcp hats show as labels only"
      (let [fsm {"id" "other-hat-fsm"
                 "states" [{"id" "state1" "hats" ["unknown-hat"]}]
                 "xitions" []}
            dot (fsm->dot-with-hats fsm)]
        ;; Should show hat name in label
        (is (includes? dot "[unknown-hat]")
            "Unknown hat should appear as label")
        ;; Should NOT create service state for unknown hats
        (is (not (includes? dot "state1-unknown"))
            "Unknown hats should not generate service states")))

    (testing "state without hats renders normally"
      (let [fsm {"id" "no-hat-fsm"
                 "states" [{"id" "plain" "action" "do-stuff"}]
                 "xitions" []}
            dot (fsm->dot-with-hats fsm)]
        (is (includes? dot "\"plain\" [label=\"plain")
            "State without hats should render normally")
        (is (not (includes? dot "subgraph cluster"))
            "No clusters when no hats expanded")))))
