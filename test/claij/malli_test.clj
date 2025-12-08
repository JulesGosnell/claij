(ns claij.malli-test
  "Tests for claij.malli namespace."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.string :refer [includes?]]
   [malli.core :as m]
   [malli.registry :as mr]
   [claij.malli :refer [valid-schema? validate-schema validate
                        MalliSchema base-registry
                        ->map-form ->vector-form
                        collect-refs count-refs transitive-closure
                        analyze-for-emission inline-refs expand-refs-for-llm emit-for-llm
                        format-for-prompt schema-prompt-note
                        valid-m2? valid-m1? def-m2 def-m1
                        fsm-registry fsm-schema valid-fsm? def-fsm]]))

(deftest malli-test
  (testing "valid-schema?"
    (testing "primitive types are valid"
      (is (valid-schema? :string))
      (is (valid-schema? :int))
      (is (valid-schema? :boolean))
      (is (valid-schema? :keyword))
      (is (valid-schema? :any)))

    (testing "vector-form schemas are valid"
      (is (valid-schema? [:map [:name :string]]))
      (is (valid-schema? [:vector :int]))
      (is (valid-schema? [:or :string :int]))
      (is (valid-schema? [:tuple :string :string]))
      (is (valid-schema? [:enum :a :b :c])))

    (testing "invalid schemas are rejected"
      (is (not (valid-schema? {:not "a schema"})))
      (is (not (valid-schema? :bogus-type)))
      (is (not (valid-schema? [:unknown-form :string])))))

  (testing "validate-schema"
    (testing "returns details for invalid schemas"
      (let [result (validate-schema :bogus-type)]
        (is (not (:valid? result)))
        (is (string? (:error result)))
        (is (map? (:data result))))))

  (testing "validate"
    (testing "validates values against schemas"
      (is (:valid? (validate :string "hello")))
      (is (not (:valid? (validate :string 42))))
      (is (:valid? (validate [:map [:x :int]] {:x 1})))
      (is (not (:valid? (validate [:map [:x :int]] {:x "not int"})))))

    (testing "returns human-readable errors"
      (let [result (validate [:map [:x :int]] {:x "bad"})]
        (is (not (:valid? result)))
        (is (map? (:errors result))))))

  (testing "MalliSchema custom type"
    (let [opts {:registry base-registry}
          schema-field [:map [:schema :malli/schema]]]

      (testing "accepts valid schemas as values"
        (is (m/validate schema-field {:schema :string} opts))
        (is (m/validate schema-field {:schema [:map [:x :int]]} opts))
        (is (m/validate schema-field {:schema [:or :string :nil]} opts)))

      (testing "rejects invalid schemas as values"
        (is (not (m/validate schema-field {:schema :not-a-type} opts)))
        (is (not (m/validate schema-field {:schema [:bad-form :x]} opts)))
        (is (not (m/validate schema-field {:schema {:not "a" :schema true}} opts))))))

  (testing "->map-form"
    (testing "primitives pass through"
      (is (= :string (->map-form :string)))
      (is (= :int (->map-form :int))))

    (testing "simple map schema"
      (is (= {:type :map
              :entries [{:key :name :schema :string}
                        {:key :age :schema :int}]}
             (->map-form [:map [:name :string] [:age :int]]))))

    (testing "map with properties"
      (is (= {:type :map
              :properties {:closed true}
              :entries [{:key :x :schema :int}]}
             (->map-form [:map {:closed true} [:x :int]]))))

    (testing "map entry with props"
      (is (= {:type :map
              :entries [{:key :name :props {:optional true} :schema :string}]}
             (->map-form [:map [:name {:optional true} :string]]))))

    (testing "vector/set/sequential"
      (is (= {:type :vector :children [:string]}
             (->map-form [:vector :string])))
      (is (= {:type :set :children [:int]}
             (->map-form [:set :int]))))

    (testing "union/intersection"
      (is (= {:type :or :children [:string :int]}
             (->map-form [:or :string :int]))))

    (testing "enum and const"
      (is (= {:type :enum :values [:a :b :c]}
             (->map-form [:enum :a :b :c])))
      (is (= {:type := :value 42}
             (->map-form [:= 42]))))

    (testing "nested schemas"
      (is (= {:type :map
              :entries [{:key :data
                         :schema {:type :vector
                                  :children [{:type :or
                                              :children [:string :int]}]}}]}
             (->map-form [:map [:data [:vector [:or :string :int]]]])))))

  (testing "->vector-form (roundtrip)"
    (let [schemas [[:map [:name :string] [:age :int]]
                   [:map {:closed true} [:x :int]]
                   [:vector :string]
                   [:or :string :int :nil]
                   [:enum :a :b :c]
                   [:= 42]
                   [:tuple :string :int]]]
      (doseq [schema schemas]
        (is (= schema (->vector-form (->map-form schema)))
            (str "Roundtrip failed for: " schema)))))

  (testing "transitive-closure-and-emission"
    (let [registry {:address [:map [:street :string] [:city :string]]
                    :person [:map
                             [:name :string]
                             [:home [:ref :address]]
                             [:work [:ref :address]]]
                    :company [:map
                              [:name :string]
                              [:ceo [:ref :person]]
                              [:hq [:ref :address]]]}]

      (testing "collect-refs finds direct refs"
        (is (= #{:address} (collect-refs (:person registry))))
        (is (= #{:person :address} (collect-refs (:company registry)))))

      (testing "count-refs counts usages"
        (is (= {:address 2} (count-refs (:person registry))))
        (is (= {:person 1 :address 1} (count-refs (:company registry)))))

      (testing "transitive-closure finds all deps"
        (is (= #{:company :person :address}
               (transitive-closure :company registry))))

      (testing "analyze-for-emission determines inline vs define"
        (let [analysis (analyze-for-emission :company registry)]
          (is (= #{:company :person :address} (:closure analysis)))
          ;; :address used 3 times (2 in :person, 1 in :company)
          (is (= 3 (get-in analysis [:usages :address])))
          ;; :person used 1 time
          (is (= 1 (get-in analysis [:usages :person])))
          ;; :person should be inlined, :address should be defined
          (is (contains? (:inline analysis) :person))
          (is (contains? (:define analysis) :address))))

      (testing "emit-for-llm produces optimized output"
        (let [emitted (emit-for-llm :company registry)]
          ;; Registry should only have :address (multi-use)
          (is (= #{:address} (set (keys (:registry emitted)))))
          ;; :person should be inlined in the schema
          (let [schema (:schema emitted)
                ceo-entry (some #(when (= :ceo (first %)) %) (rest schema))]
            ;; ceo-entry should have inlined person, not [:ref :person]
            (is (vector? (last ceo-entry)))
            (is (= :map (first (last ceo-entry)))))))

      (testing "format-for-prompt produces valid output"
        (let [formatted (format-for-prompt (emit-for-llm :company registry))]
          (is (string? formatted))
          (is (includes? formatted "Registry:"))
          (is (includes? formatted "Schema:"))
          (is (includes? formatted ":address"))))))

  (testing "schema-prompt-note"
    (is (string? schema-prompt-note))
    (is (includes? schema-prompt-note ":malli/schema")))

  (testing "valid-m2?"
    (testing "returns true for valid schemas"
      (is (valid-m2? :string))
      (is (valid-m2? [:map [:x :int]])))

    (testing "returns false for invalid schemas"
      (is (not (valid-m2? :not-a-type)))
      (is (not (valid-m2? [:bad-form])))))

  (testing "valid-m1?"
    (testing "returns true for valid documents"
      (is (valid-m1? :string "hello"))
      (is (valid-m1? [:map [:x :int]] {:x 42})))

    (testing "returns false for invalid documents"
      (is (not (valid-m1? :string 42)))
      (is (not (valid-m1? [:map [:x :int]] {:x "not-int"})))))

  (testing "def-m2 macro"
    (testing "defines valid schema"
      (def-m2 test-schema-1 [:map [:name :string]])
      (is (= [:map [:name :string]] test-schema-1))))

  (testing "def-m1 macro"
    (testing "defines valid document"
      (def-m1 test-doc-1 [:map [:name :string]] {:name "Jules"})
      (is (= {:name "Jules"} test-doc-1)))))

(deftest fsm-schema-test
  (testing "FSM schema validation"
    (let [minimal-fsm {"id" "test-fsm"
                       "states" [{"id" "start"}
                                 {"id" "end"}]
                       "xitions" [{"id" ["start" "end"]
                                   "schema" :any}]}]

      (testing "accepts minimal valid FSM"
        (is (valid-fsm? minimal-fsm)))

      (testing "accepts FSM with all optional fields"
        (is (valid-fsm? {"id" "full-fsm"
                         "description" "A fully specified FSM"
                         "prompts" ["System prompt 1" "System prompt 2"]
                         "states" [{"id" "start"
                                    "description" "Entry point"
                                    "action" "llm"
                                    "prompts" ["State prompt"]}
                                   {"id" "end"
                                    "action" "end"}]
                         "xitions" [{"id" ["start" "end"]
                                     "label" "finish"
                                     "description" "Exit transition"
                                     "prompts" ["Transition prompt"]
                                     "schema" [:map ["result" :string]]
                                     "omit" false}]})))

      (testing "rejects FSM missing required fields"
        (is (not (valid-fsm? {"states" [] "xitions" []})))
        (is (not (valid-fsm? {"id" "test" "xitions" []})))
        (is (not (valid-fsm? {"id" "test" "states" []}))))

      (testing "rejects FSM with invalid state"
        (is (not (valid-fsm? {"id" "test"
                              "states" [{"wrong-key" "value"}]
                              "xitions" []}))))

      (testing "rejects FSM with invalid xition"
        (is (not (valid-fsm? {"id" "test"
                              "states" []
                              "xitions" [{"id" "not-a-tuple"}]})))
        (is (not (valid-fsm? {"id" "test"
                              "states" []
                              "xitions" [{"id" ["a" "b"]}]}))))

      (testing "accepts Malli schemas in xition schema field"
        (is (valid-fsm? {"id" "test"
                         "states" [{"id" "a"} {"id" "b"}]
                         "xitions" [{"id" ["a" "b"]
                                     "schema" [:map
                                               ["name" :string]
                                               ["age" :int]]}]})))

      (testing "accepts string keys for dynamic schema lookup"
        (is (valid-fsm? {"id" "test"
                         "states" [{"id" "a"} {"id" "b"}]
                         "xitions" [{"id" ["a" "b"]
                                     "schema" "dynamic-schema-key"}]})))))

  (testing "def-fsm macro"
    (def-fsm test-fsm-1
      {"id" "macro-test"
       "states" [{"id" "start"} {"id" "end"}]
       "xitions" [{"id" ["start" "end"] "schema" :any}]})
    (is (= "macro-test" (get test-fsm-1 "id")))
    (is (= 2 (count (get test-fsm-1 "states"))))
    (is (= 1 (count (get test-fsm-1 "xitions"))))))

(deftest inline-refs-test
  (testing "inline-refs"
    (let [registry {:address [:map [:street :string] [:city :string]]
                    :person [:map [:name :string] [:addr [:ref :address]]]}]

      (testing "inlines refs in inline-set"
        (let [result (inline-refs [:map [:home [:ref :address]]]
                                  registry
                                  #{:address})]
          (is (= [:map [:home [:map [:street :string] [:city :string]]]]
                 result))))

      (testing "leaves refs not in inline-set"
        (let [result (inline-refs [:map [:home [:ref :address]]]
                                  registry
                                  #{})]
          (is (= [:map [:home [:ref :address]]]
                 result))))

      (testing "handles nested refs"
        (let [result (inline-refs [:ref :person]
                                  registry
                                  #{:person :address})]
          (is (= [:map [:name :string] [:addr [:map [:street :string] [:city :string]]]]
                 result))))

      (testing "handles primitives unchanged"
        (is (= :string (inline-refs :string {} #{})))
        (is (= :int (inline-refs :int {} #{})))))))

(deftest expand-refs-for-llm-test
  (testing "expand-refs-for-llm"
    (let [registry {:simple [:map [:x :int]]
                    :nested [:map [:inner [:ref :simple]]]
                    :deep [:map [:outer [:ref :nested]]]}]

      (testing "expands single ref"
        (let [result (expand-refs-for-llm [:ref :simple] registry)]
          (is (= [:map [:x :int]] result))))

      (testing "expands nested refs recursively"
        (let [result (expand-refs-for-llm [:ref :nested] registry)]
          (is (= [:map [:inner [:map [:x :int]]]] result))))

      (testing "expands deeply nested refs"
        (let [result (expand-refs-for-llm [:ref :deep] registry)]
          (is (= [:map [:outer [:map [:inner [:map [:x :int]]]]]] result))))

      (testing "handles schema without refs"
        (let [result (expand-refs-for-llm [:map [:name :string]] registry)]
          (is (= [:map [:name :string]] result))))

      (testing "handles primitives"
        (is (= :string (expand-refs-for-llm :string registry)))
        (is (= :int (expand-refs-for-llm :int registry))))

      (testing "leaves unresolved refs unchanged"
        (let [result (expand-refs-for-llm [:ref :unknown] registry)]
          (is (= [:ref :unknown] result))))

      (testing "works with Malli composite registry"
        (let [malli-reg (mr/composite-registry
                         (m/default-schemas)
                         {:my-type [:map [:value :string]]})]
          (let [result (expand-refs-for-llm [:ref :my-type] malli-reg)]
            (is (= [:map [:value :string]] result))))))))

(deftest json-schema-type-test
  (testing "JsonSchema custom Malli type"
    (let [opts {:registry base-registry}
          ;; A simple JSON Schema for objects with a required "name" field
          simple-json-schema {"type" "object"
                              "properties" {"name" {"type" "string"}
                                            "age" {"type" "integer"}}
                              "required" ["name"]}
          ;; Malli schema using our custom :json-schema type
          malli-wrapper [:json-schema {:schema simple-json-schema}]]

      (testing "accepts valid JSON Schema values"
        (is (m/validate malli-wrapper {"name" "Alice"} opts))
        (is (m/validate malli-wrapper {"name" "Bob" "age" 30} opts)))

      (testing "rejects invalid JSON Schema values"
        ;; Missing required "name" field
        (is (not (m/validate malli-wrapper {} opts)))
        (is (not (m/validate malli-wrapper {"age" 25} opts)))
        ;; Wrong type for age
        (is (not (m/validate malli-wrapper {"name" "Charlie" "age" "thirty"} opts))))

      (testing "works embedded in larger Malli schema"
        (let [composite-schema [:map {:closed true}
                                ["tool" [:= "bash"]]
                                ["arguments" [:json-schema {:schema simple-json-schema}]]]]
          (is (m/validate composite-schema
                          {"tool" "bash" "arguments" {"name" "test"}}
                          opts))
          (is (not (m/validate composite-schema
                               {"tool" "bash" "arguments" {}}
                               opts)))))

      (testing "throws when :schema property is missing"
        (is (thrown? clojure.lang.ExceptionInfo
                     (m/schema [:json-schema {}] opts)))))))
