(ns claij.fsm.state-schema-test
  "Tests for state-schema function - verifies [:or ...] captures all output transitions.
   
   Note: state-schema now emits Malli format, not JSON Schema."
  (:require
   [clojure.test :refer [deftest testing is]]
   [claij.fsm :refer [state-schema start-fsm]]))

;;==============================================================================
;; Test FSMs with multiple output transitions
;;==============================================================================

(def static-schema-fsm
  "FSM with static/literal Malli schemas on transitions."
  {"schema" {"$$id" "static-test-fsm" "type" "object"}
   "states"
   [{"id" "choice" "action" "choice-action"}
    {"id" "option-a" "action" "a-action"}
    {"id" "option-b" "action" "b-action"}
    {"id" "option-c" "action" "c-action"}]
   "xitions"
   [{"id" ["choice" "option-a"]
     "schema" [:map {:closed true}
               ["id" [:= ["choice" "option-a"]]]
               ["value" :string]]}
    {"id" ["choice" "option-b"]
     "schema" [:map {:closed true}
               ["id" [:= ["choice" "option-b"]]]
               ["count" :int]]}
    {"id" ["choice" "option-c"]
     "schema" [:map {:closed true}
               ["id" [:= ["choice" "option-c"]]]
               ["flag" :boolean]]}]})

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

;; Dynamic schema resolver functions - now return Malli
(defn resolve-dynamic-x [_context _xition]
  [:map {:closed true}
   ["id" [:= ["router" "handler-x"]]]
   ["x-data" [:vector :any]]])

(defn resolve-dynamic-y [_context _xition]
  [:map {:closed true}
   ["id" [:= ["router" "handler-y"]]]
   ["y-data" :double]])

(def dynamic-context
  {:id->schema {"dynamic-schema-x" resolve-dynamic-x
                "dynamic-schema-y" resolve-dynamic-y}})

;;==============================================================================
;; TESTS
;;==============================================================================

(deftest state-schema-test
  (testing "static schemas produce :or with all output transitions"
    (let [choice-state {"id" "choice" "action" "choice-action"}
          output-xitions (get static-schema-fsm "xitions")
          result (state-schema {} static-schema-fsm choice-state output-xitions)]

      (is (= :or (first result)) "Result should be [:or ...]")
      (is (= 3 (dec (count result))) ":or should have 3 alternatives")

      ;; Verify each schema is included by checking the id const
      (let [schemas (rest result)
            ;; Extract the id constraint from each [:map ... ["id" [:= [...]]] ...]
            extract-id (fn [schema]
                         (->> schema
                              (filter vector?)
                              (filter #(= "id" (first %)))
                              first
                              second ;; [:= [...]]
                              second)) ;; [...]
            ids (map extract-id schemas)]
        (is (some #(= ["choice" "option-a"] %) ids) "Should include option-a schema")
        (is (some #(= ["choice" "option-b"] %) ids) "Should include option-b schema")
        (is (some #(= ["choice" "option-c"] %) ids) "Should include option-c schema"))))

  (testing "dynamic schemas produce :or with resolved schemas"
    (let [router-state {"id" "router" "action" "router-action"}
          output-xitions (get dynamic-schema-fsm "xitions")
          result (state-schema dynamic-context dynamic-schema-fsm router-state output-xitions)]

      (is (= :or (first result)) "Result should be [:or ...]")
      (is (= 2 (dec (count result))) ":or should have 2 alternatives")

      ;; Verify schemas are resolved (not string references)
      (let [schemas (rest result)]
        (is (every? vector? schemas) "All schemas should be resolved vectors, not strings")

        ;; Verify each resolved schema has correct structure
        (let [extract-id (fn [schema]
                           (->> schema
                                (filter vector?)
                                (filter #(= "id" (first %)))
                                first
                                second
                                second))
              ids (map extract-id schemas)]
          (is (some #(= ["router" "handler-x"] %) ids) "Should include handler-x schema")
          (is (some #(= ["router" "handler-y"] %) ids) "Should include handler-y schema"))

        ;; Verify dynamic content is present
        (let [has-field? (fn [schema field-name]
                           (some #(and (vector? %) (= field-name (first %))) schema))
              x-schema (first (filter #(has-field? % "x-data") schemas))
              y-schema (first (filter #(has-field? % "y-data") schemas))]
          (is (some? x-schema) "x schema should have x-data field")
          (is (some? y-schema) "y schema should have y-data field")))))

  (testing "empty output transitions produce empty :or"
    (let [result (state-schema {} static-schema-fsm {"id" "terminal"} [])]
      (is (= [:or] result) "Empty transitions should produce [:or]"))))
