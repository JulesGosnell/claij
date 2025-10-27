(ns claij.fsm-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [claij.fsm :refer [make-xitions-schema xition]]))

(def fsm
  {:states
   [{:id "a"}
    {:id "b"}
    {:id "c"}]

   :xitions
   [{:id ["a" "a"]
     :schema {"type" "number"}}
    {:id ["a" "b"]
     :schema {"type" "string"}}
    {:id ["a" "c"]
     :schema {"type" "boolean"}}]})

(def proposal
  {
   "$id" "TODO"
   "id" ["a" "b"]
   "document" "a test xition"})

(deftest test-fsm
  (testing "make-xitions-schema"
    (is
     (=
      {"$schema" "https://json-schema.org/draft/2020-12/schema"
       "$id" "TODO"
       "oneOf"
       [{"properties"
         {"id" {"const" ["a" "a"]}
          "document" {"type" "number"}}}
        {"properties"
         {"id" {"const" ["a" "b"]}
          "document" {"type" "string"}}}
        {"properties"
         {"id" {"const" ["a" "c"]}
          "document" {"type" "boolean"}}}]}
      (make-xitions-schema fsm "a"))))
  (testing "xition"
    (is
     (=
      "b"
      (xition
       fsm
       "a"
       proposal)))))
