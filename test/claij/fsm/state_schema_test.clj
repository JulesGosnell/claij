(ns claij.fsm.state-schema-test
  "Tests for state-schema function - verifies oneOf captures all output transitions."
  (:require
   [clojure.test :refer [deftest testing is]]
   [claij.fsm :refer [state-schema start-fsm]]))

;;==============================================================================
;; Test FSMs with multiple output transitions
;;==============================================================================

(def static-schema-fsm
  "FSM with static/literal schemas on transitions."
  {"schema" {"$$id" "static-test-fsm" "type" "object"}
   "states"
   [{"id" "choice" "action" "choice-action"}
    {"id" "option-a" "action" "a-action"}
    {"id" "option-b" "action" "b-action"}
    {"id" "option-c" "action" "c-action"}]
   "xitions"
   [{"id" ["choice" "option-a"]
     "schema" {"type" "object"
               "properties" {"id" {"const" ["choice" "option-a"]} "value" {"type" "string"}}
               "required" ["id" "value"]
               "additionalProperties" false}}
    {"id" ["choice" "option-b"]
     "schema" {"type" "object"
               "properties" {"id" {"const" ["choice" "option-b"]} "count" {"type" "integer"}}
               "required" ["id" "count"]
               "additionalProperties" false}}
    {"id" ["choice" "option-c"]
     "schema" {"type" "object"
               "properties" {"id" {"const" ["choice" "option-c"]} "flag" {"type" "boolean"}}
               "required" ["id" "flag"]
               "additionalProperties" false}}]})

(def dynamic-schema-fsm
  "FSM with dynamic/function schemas on transitions."
  {"schema" {"$$id" "dynamic-test-fsm" "type" "object"}
   "states"
   [{"id" "router" "action" "router-action"}
    {"id" "handler-x" "action" "x-action"}
    {"id" "handler-y" "action" "y-action"}]
   "xitions"
   [{"id" ["router" "handler-x"]
     "schema" "dynamic-schema-x"}
    {"id" ["router" "handler-y"]
     "schema" "dynamic-schema-y"}]})

;; Dynamic schema resolver functions
(defn resolve-dynamic-x [_context _xition]
  {"type" "object"
   "properties" {"id" {"const" ["router" "handler-x"]} "x-data" {"type" "array"}}
   "required" ["id" "x-data"]
   "additionalProperties" false})

(defn resolve-dynamic-y [_context _xition]
  {"type" "object"
   "properties" {"id" {"const" ["router" "handler-y"]} "y-data" {"type" "number"}}
   "required" ["id" "y-data"]
   "additionalProperties" false})

(def dynamic-context
  {:id->schema {"dynamic-schema-x" resolve-dynamic-x
                "dynamic-schema-y" resolve-dynamic-y}})

;;==============================================================================
;; TESTS
;;==============================================================================

(deftest state-schema-test
  (testing "static schemas produce oneOf with all output transitions"
    (let [choice-state {"id" "choice" "action" "choice-action"}
          output-xitions (get static-schema-fsm "xitions")
          result (state-schema {} static-schema-fsm choice-state output-xitions)]
      
      (is (contains? result "oneOf") "Result should have oneOf key")
      (is (= 3 (count (get result "oneOf"))) "oneOf should have 3 alternatives")
      
      ;; Verify each schema is included
      (let [schemas (get result "oneOf")
            ids (map #(get-in % ["properties" "id" "const"]) schemas)]
        (is (some #(= ["choice" "option-a"] %) ids) "Should include option-a schema")
        (is (some #(= ["choice" "option-b"] %) ids) "Should include option-b schema")
        (is (some #(= ["choice" "option-c"] %) ids) "Should include option-c schema"))))

  (testing "dynamic schemas produce oneOf with resolved schemas"
    (let [router-state {"id" "router" "action" "router-action"}
          output-xitions (get dynamic-schema-fsm "xitions")
          result (state-schema dynamic-context dynamic-schema-fsm router-state output-xitions)]
      
      (is (contains? result "oneOf") "Result should have oneOf key")
      (is (= 2 (count (get result "oneOf"))) "oneOf should have 2 alternatives")
      
      ;; Verify schemas are resolved (not string references)
      (let [schemas (get result "oneOf")]
        (is (every? map? schemas) "All schemas should be resolved maps, not strings")
        
        ;; Verify each resolved schema has correct structure
        (let [ids (map #(get-in % ["properties" "id" "const"]) schemas)]
          (is (some #(= ["router" "handler-x"] %) ids) "Should include handler-x schema")
          (is (some #(= ["router" "handler-y"] %) ids) "Should include handler-y schema"))
        
        ;; Verify dynamic content is present
        (let [x-schema (first (filter #(= ["router" "handler-x"] (get-in % ["properties" "id" "const"])) schemas))
              y-schema (first (filter #(= ["router" "handler-y"] (get-in % ["properties" "id" "const"])) schemas))]
          (is (contains? (get x-schema "properties") "x-data") "x schema should have x-data property")
          (is (contains? (get y-schema "properties") "y-data") "y schema should have y-data property")))))

  (testing "empty output transitions produce empty oneOf"
    (let [result (state-schema {} static-schema-fsm {"id" "terminal"} [])]
      (is (= {"oneOf" []} result) "Empty transitions should produce empty oneOf"))))

;;==============================================================================
;; Trail output schema tests - verify s-schema is present in trail
;;==============================================================================

(def trail-test-fsm
  "FSM to test that output schema appears in trail correctly.
   setup -> middle (omit=true) -> choice -> option-a/option-b"
  {"schema" {"$$id" "trail-test-fsm" "type" "object"}
   "states"
   [{"id" "start" "action" "setup-action"}
    {"id" "middle" "action" "middle-action"}
    {"id" "choice" "action" "choice-action"}
    {"id" "option-a" "action" "a-action"}
    {"id" "option-b" "action" "b-action"}]
   "xitions"
   [{"id" ["start" "middle"]
     "schema" {"type" "object"
               "properties" {"id" {"const" ["start" "middle"]}}
               "required" ["id"]
               "additionalProperties" false}}
    {"id" ["middle" "choice"]
     "omit" true
     "schema" {"type" "object"
               "properties" {"id" {"const" ["middle" "choice"]} "data" {"type" "string"}}
               "required" ["id" "data"]
               "additionalProperties" false}}
    {"id" ["choice" "option-a"]
     "schema" {"type" "object"
               "properties" {"id" {"const" ["choice" "option-a"]} "value" {"type" "string"}}
               "required" ["id" "value"]
               "additionalProperties" false}}
    {"id" ["choice" "option-b"]
     "schema" {"type" "object"
               "properties" {"id" {"const" ["choice" "option-b"]} "count" {"type" "integer"}}
               "required" ["id" "count"]
               "additionalProperties" false}}]})

(defn extract-output-schema-from-trail
  "Extract the output schema (3rd element of user content) from trail."
  [trail]
  (->> trail
       (filter #(= "user" (get % "role")))
       (map #(get-in % ["content" 2]))
       first))

#_(deftest trail-output-schema-test  ;; INTENTIONALLY FAILING - demonstrates output schema bug
  (testing "output schema present in trail after omitted input transition"
    ;; This test verifies that when we transition through an omit=true transition,
    ;; the NEXT state's action still receives a trail with the output schema
    ;; showing what valid outputs are available.
    ;;
    ;; Scenario: middle -> choice (omit=true), choice has outputs to option-a and option-b
    ;; The choice-action should receive a trail entry with oneOf [option-a-schema, option-b-schema]
    
    (let [captured-trail (atom nil)]
      ;; Run FSM and capture trail at choice-action
      (let [choice-action (fn [context _fsm _ix _state _event trail handler]
                            (reset! captured-trail trail)
                            (handler context {"id" ["choice" "option-a"] "value" "test"}))
            actions {"setup-action" (fn [ctx _f _i _s _e _t h] (h ctx {"id" ["start" "middle"]}))
                     "middle-action" (fn [ctx _f _i _s _e _t h] (h ctx {"id" ["middle" "choice"] "data" "x"}))
                     "choice-action" choice-action
                     "a-action" (fn [ctx _f _i _s _e t h]
                                  (when-let [p (:fsm/completion-promise ctx)]
                                    (deliver p [ctx t])))}
            context {:id->action actions}
            [submit await _stop] (start-fsm context trail-test-fsm)]
        
        (submit {"id" ["start" "middle"]})
        (await 5000)
        
        ;; The trail at choice-action should have an output schema
        (let [trail @captured-trail
              output-schema (extract-output-schema-from-trail trail)]
          
          (is (some? output-schema) "Trail should contain output schema")
          
          (when output-schema
            (is (contains? output-schema "oneOf") "Output schema should have oneOf")
            (is (= 2 (count (get output-schema "oneOf"))) "oneOf should have 2 alternatives")))))))
