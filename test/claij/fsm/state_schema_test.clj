(ns claij.fsm.state-schema-test
  "Tests for state-schema function - verifies [:or ...] captures all output transitions.
   
   Note: state-schema now emits Malli format, not JSON Schema."
  (:require
   [clojure.test :refer [deftest testing is]]
   [claij.fsm :refer [state-schema start-fsm fsm-schemas]]))

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

(def input-output-schema-fsm
  "FSM for testing input-schema and output-schema extraction.
   Has multiple entry points (from start) and multiple exits (to end)."
  {"id" "io-schema-test"
   "states"
   [{"id" "processor" "action" "process-action"}
    {"id" "validator" "action" "validate-action"}
    {"id" "end" "action" "end"}]
   "xitions"
   ;; Two entry transitions from "start"
   [{"id" ["start" "processor"]
     "schema" [:map {:closed true}
               ["id" [:= ["start" "processor"]]]
               ["data" :string]]}
    {"id" ["start" "validator"]
     "schema" [:map {:closed true}
               ["id" [:= ["start" "validator"]]]
               ["payload" :int]]}
    ;; Internal transitions
    {"id" ["processor" "validator"]
     "schema" [:map {:closed true}
               ["id" [:= ["processor" "validator"]]]
               ["processed" :boolean]]}
    ;; Two exit transitions to "end"
    {"id" ["processor" "end"]
     "schema" [:map {:closed true}
               ["id" [:= ["processor" "end"]]]
               ["result" :string]]}
    {"id" ["validator" "end"]
     "schema" [:map {:closed true}
               ["id" [:= ["validator" "end"]]]
               ["valid" :boolean]
               ["errors" {:optional true} [:vector :string]]]}]})

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

(deftest start-fsm-schema-test
  (testing "start-fsm returns map with :input-schema and :output-schema"
    (let [;; Simple pass-through actions for testing
          pass-action (fn [_config _fsm _ix _state]
                        (fn [context event _trail handler]
                          (handler context event)))
          end-action (fn [_config _fsm _ix _state]
                       (fn [context _event trail _handler]
                         (when-let [p (:fsm/completion-promise context)]
                           (deliver p [context trail]))))
          context {:id->action {"process-action" pass-action
                                "validate-action" pass-action
                                "end" end-action}}
          result (start-fsm context input-output-schema-fsm)]

      (testing "returns a map"
        (is (map? result) "start-fsm should return a map"))

      (testing "map contains expected keys"
        (is (contains? result :submit) "should have :submit")
        (is (contains? result :await) "should have :await")
        (is (contains? result :stop) "should have :stop")
        (is (contains? result :input-schema) "should have :input-schema")
        (is (contains? result :output-schema) "should have :output-schema"))

      (testing ":input-schema is :or of all transitions FROM start"
        (let [input-schema (:input-schema result)]
          (is (= :or (first input-schema)) "input-schema should be [:or ...]")
          ;; Two transitions from "start": to "processor" and to "validator"
          (is (= 2 (dec (count input-schema))) "should have 2 entry transitions")

          ;; Extract transition ids from schemas
          (let [extract-id (fn [schema]
                             (->> schema
                                  (filter vector?)
                                  (filter #(= "id" (first %)))
                                  first
                                  second
                                  second))
                ids (set (map extract-id (rest input-schema)))]
            (is (contains? ids ["start" "processor"]) "should include start->processor")
            (is (contains? ids ["start" "validator"]) "should include start->validator"))))

      (testing ":output-schema is :or of all transitions TO end"
        (let [output-schema (:output-schema result)]
          (is (= :or (first output-schema)) "output-schema should be [:or ...]")
          ;; Two transitions to "end": from "processor" and from "validator"
          (is (= 2 (dec (count output-schema))) "should have 2 exit transitions")

          ;; Extract transition ids from schemas
          (let [extract-id (fn [schema]
                             (->> schema
                                  (filter vector?)
                                  (filter #(= "id" (first %)))
                                  first
                                  second
                                  second))
                ids (set (map extract-id (rest output-schema)))]
            (is (contains? ids ["processor" "end"]) "should include processor->end")
            (is (contains? ids ["validator" "end"]) "should include validator->end"))))

      ;; Clean up
      ((:stop result))))

  (testing "single entry/exit FSM has singleton :or schemas"
    (let [simple-fsm {"id" "simple"
                      "states" [{"id" "work" "action" "work-action"}
                                {"id" "end" "action" "end"}]
                      "xitions" [{"id" ["start" "work"]
                                  "schema" [:map ["id" [:= ["start" "work"]]]
                                            ["input" :string]]}
                                 {"id" ["work" "end"]
                                  "schema" [:map ["id" [:= ["work" "end"]]]
                                            ["output" :int]]}]}
          pass-action (fn [_config _fsm _ix _state]
                        (fn [context event _trail handler]
                          (handler context event)))
          end-action (fn [_config _fsm _ix _state]
                       (fn [context _event trail _handler]
                         (when-let [p (:fsm/completion-promise context)]
                           (deliver p [context trail]))))
          context {:id->action {"work-action" pass-action "end" end-action}}
          result (start-fsm context simple-fsm)]

      (testing "input-schema has single entry"
        (let [input-schema (:input-schema result)]
          (is (= :or (first input-schema)))
          (is (= 1 (dec (count input-schema))) "should have 1 entry transition")))

      (testing "output-schema has single exit"
        (let [output-schema (:output-schema result)]
          (is (= :or (first output-schema)))
          (is (= 1 (dec (count output-schema))) "should have 1 exit transition")))

      ((:stop result))))

  (testing "schemas contain actual schema content, not just structure"
    (let [simple-fsm {"id" "content-test"
                      "states" [{"id" "proc" "action" "proc-action"}
                                {"id" "end" "action" "end"}]
                      "xitions" [{"id" ["start" "proc"]
                                  "schema" [:map {:closed true}
                                            ["id" [:= ["start" "proc"]]]
                                            ["name" :string]
                                            ["age" :int]]}
                                 {"id" ["proc" "end"]
                                  "schema" [:map {:closed true}
                                            ["id" [:= ["proc" "end"]]]
                                            ["success" :boolean]
                                            ["message" {:optional true} :string]]}]}
          pass-action (fn [_config _fsm _ix _state]
                        (fn [context event _trail handler]
                          (handler context event)))
          end-action (fn [_config _fsm _ix _state]
                       (fn [context _event trail _handler]
                         (when-let [p (:fsm/completion-promise context)]
                           (deliver p [context trail]))))
          context {:id->action {"proc-action" pass-action "end" end-action}}
          result (start-fsm context simple-fsm)]

      (testing "input-schema contains field definitions"
        (let [input-schema (:input-schema result)
              entry-schema (second input-schema)] ;; First (and only) :or branch
          ;; Should have :map with fields
          (is (= :map (first entry-schema)))
          ;; Should have "name" and "age" fields
          (let [field-names (->> entry-schema
                                 (filter vector?)
                                 (map first)
                                 set)]
            (is (contains? field-names "name") "should have name field")
            (is (contains? field-names "age") "should have age field"))))

      (testing "output-schema contains field definitions"
        (let [output-schema (:output-schema result)
              exit-schema (second output-schema)]
          (is (= :map (first exit-schema)))
          (let [field-names (->> exit-schema
                                 (filter vector?)
                                 (map first)
                                 set)]
            (is (contains? field-names "success") "should have success field")
            (is (contains? field-names "message") "should have message field"))))

      ((:stop result)))))

(deftest fsm-schemas-test
  (testing "fsm-schemas extracts schemas without starting FSM"
    (let [result (fsm-schemas {} input-output-schema-fsm)]

      (testing "returns a map with expected keys"
        (is (map? result))
        (is (contains? result :input-schema))
        (is (contains? result :output-schema)))

      (testing "does NOT contain runtime keys"
        (is (not (contains? result :submit)) "should not have :submit")
        (is (not (contains? result :await)) "should not have :await")
        (is (not (contains? result :stop)) "should not have :stop"))))

  (testing "fsm-schemas produces same schemas as start-fsm"
    (let [pass-action (fn [_config _fsm _ix _state]
                        (fn [context event _trail handler]
                          (handler context event)))
          end-action (fn [_config _fsm _ix _state]
                       (fn [context _event trail _handler]
                         (when-let [p (:fsm/completion-promise context)]
                           (deliver p [context trail]))))
          context {:id->action {"process-action" pass-action
                                "validate-action" pass-action
                                "end" end-action}}
          ;; Get schemas both ways
          design-time-schemas (fsm-schemas context input-output-schema-fsm)
          runtime-result (start-fsm context input-output-schema-fsm)]

      (testing "input-schemas match"
        (is (= (:input-schema design-time-schemas)
               (:input-schema runtime-result))))

      (testing "output-schemas match"
        (is (= (:output-schema design-time-schemas)
               (:output-schema runtime-result))))

      ;; Clean up
      ((:stop runtime-result))))

  (testing "fsm-schemas with single-arity call (no context)"
    (let [simple-fsm {"id" "simple"
                      "states" [{"id" "work" "action" "work-action"}
                                {"id" "end" "action" "end"}]
                      "xitions" [{"id" ["start" "work"]
                                  "schema" [:map ["id" [:= ["start" "work"]]]
                                            ["input" :string]]}
                                 {"id" ["work" "end"]
                                  "schema" [:map ["id" [:= ["work" "end"]]]
                                            ["output" :int]]}]}
          result (fsm-schemas simple-fsm)]

      (testing "works without explicit context"
        (is (= :or (first (:input-schema result))))
        (is (= :or (first (:output-schema result)))))))

  (testing "fsm-schemas with dynamic schema resolution"
    (let [dynamic-fsm {"id" "dynamic"
                       "states" [{"id" "handler" "action" "handle"}
                                 {"id" "end" "action" "end"}]
                       "xitions" [{"id" ["start" "handler"]
                                   "schema" "entry-schema"}
                                  {"id" ["handler" "end"]
                                   "schema" "exit-schema"}]}
          entry-schema-fn (fn [_ctx _xition]
                            [:map ["id" [:= ["start" "handler"]]]
                             ["request" :string]])
          exit-schema-fn (fn [_ctx _xition]
                           [:map ["id" [:= ["handler" "end"]]]
                            ["response" :int]])
          context {:id->schema {"entry-schema" entry-schema-fn
                                "exit-schema" exit-schema-fn}}
          result (fsm-schemas context dynamic-fsm)]

      (testing "resolves dynamic schema references"
        (let [input-schema (:input-schema result)
              output-schema (:output-schema result)]
          ;; Input should be resolved, not a string
          (is (vector? (second input-schema)) "input should be resolved schema, not string")
          (is (= :map (first (second input-schema))))

          ;; Output should be resolved, not a string
          (is (vector? (second output-schema)) "output should be resolved schema, not string")
          (is (= :map (first (second output-schema)))))))))
