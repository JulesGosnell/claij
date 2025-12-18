(ns poc.openapi.integration-test
  "Integration tests for OpenAPI hat.
   
   Tests the full flow:
   1. Start test server with OpenAPI spec
   2. Create hat with schema functions
   3. Execute API operations
   4. Verify schemas flow through properly"
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [poc.openapi.server :as server]
   [claij.hat.openapi :as openapi]))

;;; ============================================================
;;; Test Fixtures
;;; ============================================================

(def test-port 8766)
(def spec-url (str "http://localhost:" test-port "/openapi.json"))
(def base-url (str "http://localhost:" test-port))

(defn with-test-server [f]
  (server/reset-data!)
  (server/start-server test-port)
  (try
    (f)
    (finally
      (server/stop-server))))

(use-fixtures :each with-test-server)

;;; ============================================================
;;; Spec Parsing Tests
;;; ============================================================

(deftest fetch-openapi-spec-test
  (testing "Can fetch and parse OpenAPI spec"
    (let [spec (openapi/fetch-openapi-spec spec-url)]
      (is (= "3.0.0" (get spec "openapi")))
      (is (= "Items API" (get-in spec ["info" "title"])))
      (is (contains? (get spec "paths") "/items"))
      (is (contains? (get spec "paths") "/items/{id}")))))

(deftest extract-operations-test
  (testing "Extracts operations with raw JSON schemas"
    (let [spec (openapi/fetch-openapi-spec spec-url)
          operations (openapi/extract-operations spec)]
      (is (= 3 (count operations)))
      (let [op-ids (set (map :operation-id operations))]
        (is (contains? op-ids "listItems"))
        (is (contains? op-ids "getItemById"))
        (is (contains? op-ids "createItem")))
      ;; Check schemas are extracted AS-IS
      (let [create-op (first (filter #(= "createItem" (:operation-id %)) operations))]
        (is (some? (:request-schema create-op)) "createItem should have request schema")
        (is (= "object" (get (:request-schema create-op) "type")))
        (is (some? (:response-schema create-op)) "createItem should have response schema")))))

(deftest operations->tools-test
  (testing "Converts operations to tools with path/query params"
    (let [spec (openapi/fetch-openapi-spec spec-url)
          operations (openapi/extract-operations spec)
          tools (openapi/operations->tools operations)
          get-item-tool (first (filter #(= "getItemById" (:operation-id %)) tools))]
      (is (= 3 (count tools)))
      (is (= :get (:method get-item-tool)))
      (is (= "/items/{id}" (:path get-item-tool)))
      (is (= ["id"] (:path-params get-item-tool))))))

;;; ============================================================
;;; HTTP Execution Tests
;;; ============================================================

(deftest substitute-path-params-test
  (testing "Path parameter substitution"
    (is (= "/items/123"
           (openapi/substitute-path-params "/items/{id}" {"id" "123"})))
    (is (= "/users/abc/posts/456"
           (openapi/substitute-path-params "/users/{userId}/posts/{postId}"
                                           {"userId" "abc" "postId" "456"})))))

(deftest execute-operation-test
  (testing "Executes GET operation"
    (let [spec (openapi/fetch-openapi-spec spec-url)
          tools (openapi/operations->tools (openapi/extract-operations spec))
          get-item-tool (first (filter #(= "getItemById" (:operation-id %)) tools))
          config {:base-url base-url :timeout-ms 5000}
          result (openapi/execute-operation config get-item-tool
                                            {"params" {"id" "1"}})]
      (is (= 200 (get result "status")))
      (is (= "1" (get-in result ["body" "id"])))
      (is (= "First Item" (get-in result ["body" "name"])))))

  (testing "Executes POST operation"
    (let [spec (openapi/fetch-openapi-spec spec-url)
          tools (openapi/operations->tools (openapi/extract-operations spec))
          create-tool (first (filter #(= "createItem" (:operation-id %)) tools))
          config {:base-url base-url :timeout-ms 5000}
          result (openapi/execute-operation config create-tool
                                            {"params" {"body" {"name" "Test Item"
                                                               "status" "active"}}})]
      (is (= 201 (get result "status")))
      (is (= "Test Item" (get-in result ["body" "name"])))))

  (testing "Handles 404 gracefully"
    (let [spec (openapi/fetch-openapi-spec spec-url)
          tools (openapi/operations->tools (openapi/extract-operations spec))
          get-item-tool (first (filter #(= "getItemById" (:operation-id %)) tools))
          config {:base-url base-url :timeout-ms 5000}
          result (openapi/execute-operation config get-item-tool
                                            {"params" {"id" "nonexistent"}})]
      (is (= 404 (get result "status"))))))

(deftest execute-calls-test
  (testing "Executes multiple calls"
    (let [spec (openapi/fetch-openapi-spec spec-url)
          tools (openapi/operations->tools (openapi/extract-operations spec))
          config {:base-url base-url :timeout-ms 5000}
          results (openapi/execute-calls
                   config tools
                   [{"operation" "getItemById" "params" {"id" "1"}}
                    {"operation" "listItems"}])]
      (is (= 2 (count results)))
      (is (= 200 (get (first results) "status")))
      (is (= 200 (get (second results) "status"))))))

;;; ============================================================
;;; Hat Maker Tests
;;; ============================================================

(deftest openapi-hat-maker-test
  (testing "Hat maker creates valid hat with schema functions"
    (let [config {:spec-url spec-url :base-url base-url}
          hat-fn (openapi/openapi-hat-maker "worker" config)
          [context' additions] (hat-fn {})]
      ;; Check context was updated
      (is (contains? (:hats context') :openapi))
      (is (contains? (get-in context' [:hats :openapi]) "worker"))

      ;; Check schema functions registered
      (is (contains? (:id->schema context') "worker-openapi-request"))
      (is (contains? (:id->schema context') "worker-openapi-response"))

      ;; Check action registered
      (is (contains? (:id->action context') "openapi-service"))

      ;; Check states were added
      (is (= 1 (count (get additions "states"))))
      (is (= "worker-openapi" (get-in additions ["states" 0 "id"])))

      ;; Check xitions reference schema IDs (not inline schemas)
      (is (= 2 (count (get additions "xitions"))))
      (is (= "worker-openapi-request"
             (get-in additions ["xitions" 0 "schema"])))
      (is (= "worker-openapi-response"
             (get-in additions ["xitions" 1 "schema"])))

      ;; Check prompts contain schema definitions
      (is (seq (get additions "prompts")))
      (let [prompt (first (get additions "prompts"))]
        (is (re-find #"Schema Definitions" prompt))
        (is (re-find #"Item" prompt))
        (is (re-find #"listItems" prompt))))))

;;; ============================================================
;;; Schema Function Tests
;;; ============================================================

(deftest schema-functions-test
  (testing "Request schema function builds dynamic schema with JSON schemas"
    (let [config {:spec-url spec-url :base-url base-url}
          hat-fn (openapi/openapi-hat-maker "worker" config)
          [context' _] (hat-fn {})
          schema-fn (get-in context' [:id->schema "worker-openapi-request"])
          xition {"id" ["worker" "worker-openapi"]}
          schema (schema-fn context' xition)]
      ;; Schema should be a map with id and calls
      (is (vector? schema))
      (is (= :map (first schema)))))

  (testing "Response schema function builds result schema"
    (let [config {:spec-url spec-url :base-url base-url}
          hat-fn (openapi/openapi-hat-maker "worker" config)
          [context' _] (hat-fn {})
          schema-fn (get-in context' [:id->schema "worker-openapi-response"])
          xition {"id" ["worker-openapi" "worker"]}
          schema (schema-fn context' xition)]
      (is (vector? schema))
      (is (= :map (first schema))))))

;;; ============================================================
;;; Prompt Tests
;;; ============================================================

(deftest generate-tools-prompt-test
  (testing "Prompt includes schema definitions and operations"
    (let [spec (openapi/fetch-openapi-spec spec-url)
          operations (openapi/extract-operations spec)
          tools (openapi/operations->tools operations)
          prompt (openapi/generate-tools-prompt "worker" "worker-openapi" tools spec)]
      ;; Schema definitions section
      (is (re-find #"Schema Definitions" prompt))
      (is (re-find #"Item" prompt))
      ;; Operations section
      (is (re-find #"OpenAPI Operations" prompt))
      (is (re-find #"listItems" prompt))
      (is (re-find #"getItemById" prompt))
      (is (re-find #"createItem" prompt))
      ;; Request/Response schemas shown
      (is (re-find #"Request:" prompt))
      (is (re-find #"Response:" prompt)))))
