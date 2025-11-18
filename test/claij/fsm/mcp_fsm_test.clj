(ns claij.fsm.mcp-fsm-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [claij.fsm :refer [start-fsm]]
   [claij.fsm.mcp-fsm :refer [mcp-fsm]]))

(deftest mcp-fsm-test
  (testing "mcp-fsm definition"
    (is (map? mcp-fsm) "mcp-fsm should be a map")
    (is (contains? mcp-fsm "schema") "mcp-fsm should have a schema")
    (is (contains? mcp-fsm "states") "mcp-fsm should have states")
    (is (contains? mcp-fsm "xitions") "mcp-fsm should have transitions")))
