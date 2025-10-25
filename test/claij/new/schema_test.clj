(ns claij.new.schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [claij.new.schema :as schema]))

(deftest test-base-schema
  (testing "base schema structure"
    (is (= "object" (:type schema/base-schema)))
    (is (= ["answer" "state"] (:required schema/base-schema)))
    (is (contains? (:properties schema/base-schema) :answer))
    (is (contains? (:properties schema/base-schema) :state))))

(deftest test-add-property
  (testing "adding a single property"
    (let [result (schema/add-property schema/base-schema
                                      :summary
                                      {:type "string" :description "Summary"})]
      (is (contains? (:properties result) :summary))
      (is (= "string" (get-in result [:properties :summary :type])))
      (is (= "Summary" (get-in result [:properties :summary :description]))))))

(deftest test-add-required
  (testing "adding to required fields"
    (let [result (schema/add-required schema/base-schema "summary")]
      (is (some #(= "summary" %) (:required result)))
      (is (= 3 (count (:required result)))))))

(deftest test-compose-schema
  (testing "composing schema with no extensions"
    (let [result (schema/compose-schema schema/base-schema [])]
      (is (= schema/base-schema result))))

  (testing "composing schema with single extension"
    (let [extension {:properties {:summary {:type "string"}}}
          result (schema/compose-schema schema/base-schema [extension])]
      (is (contains? (:properties result) :summary))
      (is (contains? (:properties result) :answer))
      (is (contains? (:properties result) :state))))

  (testing "composing schema with multiple extensions"
    (let [ext1 {:properties {:summary {:type "string"}}}
          ext2 {:properties {:confidence {:type "number"}}}
          result (schema/compose-schema schema/base-schema [ext1 ext2])]
      (is (contains? (:properties result) :summary))
      (is (contains? (:properties result) :confidence))
      (is (= 4 (count (:properties result))))))

  (testing "extension with required fields"
    (let [extension {:properties {:summary {:type "string"}}
                     :required ["summary"]}
          result (schema/compose-schema schema/base-schema [extension])]
      (is (some #(= "summary" %) (:required result)))
      (is (= 3 (count (:required result))))))

  (testing "later extensions override earlier ones"
    (let [ext1 {:properties {:summary {:type "string" :description "First"}}}
          ext2 {:properties {:summary {:type "string" :description "Second"}}}
          result (schema/compose-schema schema/base-schema [ext1 ext2])]
      (is (= "Second" (get-in result [:properties :summary :description]))))))

(deftest test-schema->json
  (testing "converts schema to JSON string"
    (let [json-str (schema/schema->json schema/base-schema)]
      (is (string? json-str))
      (is (re-find #"\"answer\"" json-str))
      (is (re-find #"\"state\"" json-str))
      (is (re-find #"\"type\"" json-str)))))

(deftest test-format-schema-for-prompt
  (testing "formats schema for LLM prompt"
    (let [prompt (schema/format-schema-for-prompt schema/base-schema)]
      (is (string? prompt))
      (is (re-find #"You must respond with valid JSON" prompt))
      (is (re-find #"answer" prompt))
      (is (re-find #"state" prompt))
      (is (re-find #"required" prompt)))))
