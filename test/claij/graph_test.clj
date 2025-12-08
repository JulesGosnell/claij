(ns claij.graph-test
  "Unit tests for claij.graph - FSM to Graphviz DOT conversion."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.string :refer [includes?]]
   [claij.graph :refer [fsm->dot]]))

(deftest graph-test

  (testing "fsm->dot"

    (testing "minimal FSM"
      (let [fsm {"id" "test-fsm"}
            dot (fsm->dot fsm)]
        (is (includes? dot "digraph \"test-fsm\""))
        (is (includes? dot "start [shape=doublecircle"))
        (is (includes? dot "end   [shape=doublecircle"))))

    (testing "FSM with description"
      (let [fsm {"id" "desc-fsm" "description" "A test FSM"}
            dot (fsm->dot fsm)]
        (is (includes? dot "A test FSM"))))

    (testing "FSM with prompts as title fallback"
      (let [fsm {"id" "prompt-fsm" "prompts" ["Line 1" "Line 2"]}
            dot (fsm->dot fsm)]
        (is (includes? dot "Line 1"))
        (is (includes? dot "Line 2"))))

    (testing "FSM with states"
      (let [fsm {"id" "state-fsm"
                 "states" [{"id" "processing" "action" "process"}
                           {"id" "waiting"}]}
            dot (fsm->dot fsm)]
        (is (includes? dot "processing [label=\"processing"))
        (is (includes? dot "(process)"))
        (is (includes? dot "waiting [label=\"waiting"))))

    (testing "FSM with state prompts"
      (let [fsm {"id" "prompted-fsm"
                 "states" [{"id" "reviewer" "prompts" ["Review code"]}]}
            dot (fsm->dot fsm)]
        (is (includes? dot "Review code"))))

    (testing "start and end states are not duplicated"
      (let [fsm {"id" "special-fsm"
                 "states" [{"id" "start"} {"id" "end"} {"id" "middle"}]}
            dot (fsm->dot fsm)]
        ;; start/end already defined as special nodes, shouldn't appear in states section again
        (is (includes? dot "middle [label="))
        ;; count occurrences of start definition
        (is (= 1 (count (re-seq #"start \[shape=doublecircle" dot))))))

    (testing "FSM with transitions"
      (let [fsm {"id" "xition-fsm"
                 "xitions" [{"id" ["start" "processing"]}
                            {"id" ["processing" "end"]}]}
            dot (fsm->dot fsm)]
        (is (includes? dot "start -> processing"))
        (is (includes? dot "processing -> end"))))

    (testing "transition with label"
      (let [fsm {"id" "labeled-fsm"
                 "xitions" [{"id" ["a" "b"] "label" "go next"}]}
            dot (fsm->dot fsm)]
        (is (includes? dot "[label=\"go next\"]"))))

    (testing "transition with description"
      (let [fsm {"id" "desc-xition-fsm"
                 "xitions" [{"id" ["a" "b"] "description" "transition desc"}]}
            dot (fsm->dot fsm)]
        (is (includes? dot "transition desc"))))

    (testing "transition uses 'to' state as fallback label"
      (let [fsm {"id" "fallback-fsm"
                 "xitions" [{"id" ["a" "done"]}]}
            dot (fsm->dot fsm)]
        (is (includes? dot "[label=\"done\"]"))))))
