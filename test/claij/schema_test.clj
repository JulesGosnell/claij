(ns claij.schema-test
  "Tests for claij.schema utilities."
  (:require
   [clojure.test :refer [deftest testing is]]
   [claij.schema :as schema]))

(deftest validate-test
  (testing "validate returns valid for matching data"
    (let [result (schema/validate {"type" "string"} "hello")]
      (is (:valid? result))))

  (testing "validate returns invalid with errors for non-matching data"
    (let [result (schema/validate {"type" "integer"} "not-an-int")]
      (is (not (:valid? result)))
      (is (vector? (:errors result)))
      (is (pos? (count (:errors result))))))

  (testing "validate with $defs resolves references"
    (let [schema {"$ref" "#/$defs/name"}
          defs {"name" {"type" "string" "minLength" 1}}
          result (schema/validate schema "valid" defs)]
      (is (:valid? result)))
    (let [schema {"$ref" "#/$defs/name"}
          defs {"name" {"type" "string" "minLength" 1}}
          result (schema/validate schema "" defs)]
      (is (not (:valid? result))))))

(deftest valid?-test
  (testing "valid? returns boolean"
    (is (true? (schema/valid? {"type" "string"} "hello")))
    (is (false? (schema/valid? {"type" "integer"} "nope"))))

  (testing "valid? with defs"
    (is (true? (schema/valid? {"$ref" "#/$defs/num"} 42 {"num" {"type" "integer"}})))))

(deftest schema-builder-test
  (testing "string-schema"
    (is (= {"type" "string"} (schema/string-schema)))
    (is (= {"type" "string" "minLength" 1} (schema/string-schema {"minLength" 1}))))

  (testing "integer-schema"
    (is (= {"type" "integer"} (schema/integer-schema)))
    (is (= {"type" "integer" "minimum" 0} (schema/integer-schema {"minimum" 0}))))

  (testing "number-schema"
    (is (= {"type" "number"} (schema/number-schema)))
    (is (= {"type" "number" "maximum" 100} (schema/number-schema {"maximum" 100}))))

  (testing "boolean-schema"
    (is (= {"type" "boolean"} (schema/boolean-schema))))

  (testing "array-schema"
    (is (= {"type" "array" "items" {"type" "string"}}
           (schema/array-schema {"type" "string"})))
    (is (= {"type" "array" "items" {"type" "integer"} "minItems" 1}
           (schema/array-schema {"type" "integer"} {"minItems" 1}))))

  (testing "object-schema"
    (is (= {"type" "object" "properties" {"name" {"type" "string"}}}
           (schema/object-schema {"name" {"type" "string"}})))
    (is (= {"type" "object"
            "properties" {"id" {"type" "integer"}}
            "required" ["id"]
            "additionalProperties" false
            "description" "A thing"}
           (schema/object-schema {"id" {"type" "integer"}}
                                 {:required ["id"]
                                  :additional false
                                  :description "A thing"}))))

  (testing "enum-schema"
    (is (= {"enum" ["a" "b" "c"]} (schema/enum-schema "a" "b" "c"))))

  (testing "const-schema"
    (is (= {"const" 42} (schema/const-schema 42))))

  (testing "ref-schema"
    (is (= {"$ref" "#/$defs/user"} (schema/ref-schema "user"))))

  (testing "any-of-schema"
    (is (= {"anyOf" [{"type" "string"} {"type" "integer"}]}
           (schema/any-of-schema {"type" "string"} {"type" "integer"}))))

  (testing "one-of-schema"
    (is (= {"oneOf" [{"const" "a"} {"const" "b"}]}
           (schema/one-of-schema {"const" "a"} {"const" "b"}))))

  (testing "all-of-schema"
    (is (= {"allOf" [{"type" "object"} {"required" ["id"]}]}
           (schema/all-of-schema {"type" "object"} {"required" ["id"]})))))

(deftest build-schema-registry-test
  (testing "empty inputs"
    (is (= {} (schema/build-schema-registry {} {}))))

  (testing "from FSM schemas"
    (let [fsm {"schemas" {"user" {"type" "object"}}}
          result (schema/build-schema-registry fsm {})]
      (is (= {"user" {"type" "object"}} result))))

  (testing "from context defs"
    (let [context {:schema/defs {"role" {"type" "string"}}}
          result (schema/build-schema-registry {} context)]
      (is (= {"role" {"type" "string"}} result))))

  (testing "merges FSM and context (context wins)"
    (let [fsm {"schemas" {"x" {"type" "string"}}}
          context {:schema/defs {"x" {"type" "integer"} "y" {"type" "boolean"}}}
          result (schema/build-schema-registry fsm context)]
      (is (= {"x" {"type" "integer"} "y" {"type" "boolean"}} result)))))

(deftest validate-with-registry-test
  (testing "validates using registry for refs"
    (let [registry {"name" {"type" "string" "minLength" 1}}
          schema {"$ref" "#/$defs/name"}]
      (is (:valid? (schema/validate-with-registry registry schema "hello")))
      (is (not (:valid? (schema/validate-with-registry registry schema "")))))))

(deftest expand-refs-test
  (testing "expands simple $ref"
    (let [registry {"user" {"type" "object" "properties" {"name" {"type" "string"}}}}
          schema {"$ref" "#/$defs/user"}
          result (schema/expand-refs schema registry)]
      (is (= {"type" "object" "properties" {"name" {"type" "string"}}} result))))

  (testing "expands nested $refs"
    (let [registry {"user" {"type" "object" "properties" {"role" {"$ref" "#/$defs/role"}}}
                    "role" {"type" "string" "enum" ["admin" "user"]}}
          schema {"$ref" "#/$defs/user"}
          result (schema/expand-refs schema registry)]
      (is (= {"type" "object"
              "properties" {"role" {"type" "string" "enum" ["admin" "user"]}}}
             result))))

  (testing "expands refs in arrays"
    (let [registry {"item" {"type" "string"}}
          schema {"type" "array" "items" {"$ref" "#/$defs/item"}}
          result (schema/expand-refs schema registry)]
      (is (= {"type" "array" "items" {"type" "string"}} result))))

  (testing "leaves unresolved refs as-is"
    (let [schema {"$ref" "#/$defs/unknown"}
          result (schema/expand-refs schema {})]
      (is (= {"$ref" "#/$defs/unknown"} result))))

  (testing "handles non-ref schemas"
    (is (= {"type" "string"} (schema/expand-refs {"type" "string"} {})))
    (is (= "hello" (schema/expand-refs "hello" {})))
    (is (= 42 (schema/expand-refs 42 {})))))

(deftest valid-fsm?-test
  (testing "valid FSM returns true"
    (let [fsm {"id" "test"
               "states" [{"id" "a"} {"id" "b"}]
               "xitions" [{"id" ["start" "a"]} {"id" ["a" "b"]}]}]
      (is (schema/valid-fsm? fsm))))

  (testing "invalid FSM returns false"
    (is (not (schema/valid-fsm? {})))
    (is (not (schema/valid-fsm? {"id" "test"}))) ; missing states/xitions
    (is (not (schema/valid-fsm? {"states" [] "xitions" []}))))) ; missing id
