(ns claij.hat.openapi-test
  "Tests for OpenAPI hat functionality."
  (:require [clojure.test :refer [deftest is testing]]
            [claij.hat.openapi :as openapi]))

(deftest test-extract-operations
  (testing "extract-operations finds all operations in spec"
    (let [spec {"paths" {"/items" {"get" {"operationId" "listItems"}
                                   "post" {"operationId" "createItem"}}
                         "/items/{id}" {"get" {"operationId" "getItem"}
                                        "delete" {"operationId" "deleteItem"}}}}
          ops (openapi/extract-operations spec)]
      (is (= 4 (count ops)))
      (is (some #(= "listItems" (:operation-id %)) ops))
      (is (some #(= "createItem" (:operation-id %)) ops))
      (is (some #(= "getItem" (:operation-id %)) ops))
      (is (some #(= "deleteItem" (:operation-id %)) ops)))))

(deftest test-extract-operations-with-paths
  (testing "extract-operations includes path and method"
    (let [spec {"paths" {"/items" {"get" {"operationId" "listItems"}}
                         "/items/{id}" {"get" {"operationId" "getItem"}}}}
          ops (openapi/extract-operations spec)]
      (is (some #(and (= "listItems" (:operation-id %))
                      (= "/items" (:path %))
                      (= :get (:method %)))
                ops))
      (is (some #(and (= "getItem" (:operation-id %))
                      (= "/items/{id}" (:path %)))
                ops)))))

(deftest test-operation->tool
  (testing "operation->tool creates tool structure"
    (let [operation {:operation-id "getItem"
                     :method :get
                     :path "/items/{id}"
                     :summary "Get an item"
                     :parameters [{"name" "id"
                                   "in" "path"
                                   "required" true
                                   "schema" {"type" "string"}}]}
          tool (openapi/operation->tool operation)]
      (is (= "getItem" (:operation-id tool)))
      (is (= :get (:method tool)))
      (is (= "/items/{id}" (:path tool)))
      (is (= ["id"] (:path-params tool))))))

(deftest test-operations->tools
  (testing "operations->tools converts list of operations"
    (let [operations [{:operation-id "listItems" :method :get :path "/items"}
                      {:operation-id "createItem" :method :post :path "/items"}]
          tools (openapi/operations->tools operations)]
      (is (= 2 (count tools)))
      (is (map? (first tools))))))

(deftest test-substitute-path-params
  (testing "substitute-path-params replaces placeholders"
    (is (= "/items/123" (openapi/substitute-path-params "/items/{id}" {"id" "123"})))
    (is (= "/users/abc/posts/456"
           (openapi/substitute-path-params "/users/{userId}/posts/{postId}"
                                           {"userId" "abc" "postId" "456"})))))

(deftest test-openapi-hat-maker
  (testing "openapi-hat-maker returns a function"
    (let [maker (openapi/openapi-hat-maker "test-state" {:spec-url "http://example.com/openapi.json"})]
      (is (fn? maker)))))

(deftest test-resolve-schema
  (testing "resolve-schema returns schema directly when no ref"
    (let [spec {}
          schema {"type" "string"}]
      (is (= schema (openapi/resolve-schema spec schema)))))

  (testing "resolve-schema handles nil schema"
    (let [spec {}]
      (is (nil? (openapi/resolve-schema spec nil))))))

(deftest test-tools->request-schema
  (testing "tools->request-schema generates valid schema"
    (let [tools [{:operation-id "test" :request-schema {"type" "object"}}]
          schema (openapi/tools->request-schema tools)]
      (is (vector? schema)) ;; Malli schemas are vectors
      (is (= :map (first schema))))))

(deftest test-tools->response-schema
  (testing "tools->response-schema generates valid schema"
    (let [tools [{:operation-id "test"}]
          schema (openapi/tools->response-schema tools)]
      (is (vector? schema)) ;; Malli schemas are vectors
      (is (= :map (first schema))))))
