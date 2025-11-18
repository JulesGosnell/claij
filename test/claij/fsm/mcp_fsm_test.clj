(ns claij.fsm.mcp-fsm-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [claij.fsm :refer [start-fsm]]
   [claij.fsm.mcp-fsm :refer [mcp-fsm]]))

(deftest mcp-fsm-test
  (testing "mcp-fsm"

    (let [[submit stop-fsm] (start-fsm {} mcp-fsm)]
      (is (fn? submit))
      (is (fn? stop-fsm))

      (submit "let's make an mcp request...")
      )))
