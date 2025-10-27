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
     :roles ["lead"]
     :schema {"type" "number"}}
    {:id ["a" "b"]
     :roles ["lead" "dev"]
     :schema {"type" "string"}}
    {:id ["a" "c"]
     :roles ["tools"]
     :schema {"type" "boolean"}}]})

(deftest test-fsm
  (testing "make-xitions-schema"
    (is
     (=
      {"$schema" "https://json-schema.org/draft/2020-12/schema"
       "$id" "TODO"
       "oneOf"
       [{"properties"
         {"id" {"const" ["a" "a"]}
          "roles" {"type" "array" "items" {"type" "string"}}
          "document" {"type" "number"}}}
        {"properties"
         {"id" {"const" ["a" "b"]}
          "roles" {"type" "array" "items" {"type" "string"}}
          "document" {"type" "string"}}}
        {"properties"
         {"id" {"const" ["a" "c"]}
          "roles" {"type" "array" "items" {"type" "string"}}
          "document" {"type" "boolean"}}}]}
      (make-xitions-schema fsm "a"))))
  (testing "xition: "
    (testing "matches"
      (is
       (=
        "b"
        (xition
         fsm
         "a"
         {"$id" "TODO"
          "id" ["a" "b"]
          "roles" ["dev" "docs"]
          "document" "a test xition"}))))
    (testing "doesn't match"
      (is
       (false?
        (xition
         fsm
         "a"
         {"$id" "TODO"
          "id" ["a" "b"]
          "roles" ["dev" "docs"]
          "document" 0}))))
    (testing "out of role"
      (is
       (nil?
        (xition
         fsm
         "a"
         {"$id" "TODO"
          "id" ["a" "b"]
          "roles" ["docs"]
          "document" "a test xition"}))))))
