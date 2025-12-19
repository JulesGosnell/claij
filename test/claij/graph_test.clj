(ns claij.graph-test
  "Unit tests for claij.graph - FSM to Graphviz DOT conversion.
   
   These tests verify that:
   1. DOT format structure is correct (digraph, node shapes, edge arrows)
   2. FSM content (IDs, descriptions, prompts) appears in output"
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.string :refer [includes?]]
   [claij.graph :refer [fsm->dot fsm->dot-with-hats]]
   [claij.hat]))

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

    (testing "expands hat-generated states"
      (let [;; Mock hat-maker that adds a service state
            mock-hat-maker (fn [state-id _config]
                             (fn [context]
                               [context
                                {"states" [{"id" (str state-id "-svc") "action" "service"}]
                                 "xitions" [{"id" [state-id (str state-id "-svc")]}
                                            {"id" [(str state-id "-svc") state-id]}]
                                 "prompts" []}]))
            registry (-> (claij.hat/make-hat-registry)
                         (claij.hat/register-hat "mock" mock-hat-maker))
            fsm {"id" "hat-fsm"
                 "states" [{"id" "mc" "hats" ["mock"]}]
                 "xitions" [{"id" ["start" "mc"]}
                            {"id" ["mc" "end"]}]}
            dot (fsm->dot-with-hats fsm registry)]
        ;; Should have original state (quoted)
        (is (includes? dot "\"mc\" [label=")
            "Original state should appear")
        ;; Should have hat-generated state (quoted)
        (is (includes? dot "\"mc-svc\" [label=")
            "Hat-generated state should appear")
        ;; Should have hat-generated transitions (quoted)
        (is (includes? dot "\"mc\" -> \"mc-svc\"")
            "Hat-generated transition should appear")
        (is (includes? dot "\"mc-svc\" -> \"mc\"")
            "Hat loopback transition should appear")
        ;; Should have cluster box around hat states
        (is (includes? dot "subgraph cluster_mc")
            "Hat states should be in a cluster")
        (is (includes? dot "label=\"mc hat\"")
            "Cluster should be labeled with parent state")))))
