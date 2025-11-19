(ns claij.fsm.mcp-fsm-test
  "MCP Protocol FSM - Models MCP lifecycle as explicit states.
  
  This FSM orchestrates the complete MCP protocol flow:
  - Service initialization
  - Cache population
  - Tool/resource operations
  
  Production code at top, tests at bottom."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.tools.logging :as log]
   [claij.util :refer [def-m2]]
   [claij.fsm :refer [def-fsm start-fsm]]
   [claij.mcp :refer [mcp-schema]]))

;;==============================================================================
;; PRODUCTION CODE
;;==============================================================================

;;------------------------------------------------------------------------------
;; MCP Schema
;;------------------------------------------------------------------------------

;;------------------------------------------------------------------------------
;; MCP FSM Definition
;;------------------------------------------------------------------------------

(declare mcp-fsm)

(def-fsm
  mcp-fsm

  {"schema" mcp-schema
   "id" "mcp"
   "description" "Orchestrates MCP protocol interactions"

   "prompts"
   ["You are coordinating interactions with an MCP (Model Context Protocol) service"]

   "states"
   [{"id" "start"}
    {"id" "end"}]

   "xitions"
   [{"id" ["start" "end"]
     "schema" {"$ref" "#/$defs/start-to-end"}}]})

;;==============================================================================
;; TESTS
;;==============================================================================

(deftest mcp-fsm-test

  (testing "walk the fsm"
    (let [context {}
          [submit stop] (start-fsm context mcp-fsm)]
      (try
        (catch Throwable t
          (log/error "unexpected error" t))
        (finally
          (stop))))))

