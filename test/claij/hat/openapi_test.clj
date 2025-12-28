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
  (testing "tools->request-schema generates valid JSON Schema"
    (let [tools [{:operation-id "test" :request-schema {"type" "object"}}]
          schema (openapi/tools->request-schema tools)]
      (is (map? schema))
      (is (= "object" (get schema "type"))))))

(deftest test-tools->response-schema
  (testing "tools->response-schema generates valid JSON Schema"
    (let [tools [{:operation-id "test"}]
          schema (openapi/tools->response-schema tools)]
      (is (map? schema))
      (is (= "object" (get schema "type"))))))

(deftest test-resolve-ref
  (testing "resolve-ref resolves $ref paths"
    (let [spec {"components" {"schemas" {"User" {"type" "object"
                                                 "properties" {"name" {"type" "string"}}}}}}]
      (is (= {"type" "object" "properties" {"name" {"type" "string"}}}
             (openapi/resolve-ref spec "#/components/schemas/User")))))

  (testing "resolve-ref returns nil for nil ref"
    (is (nil? (openapi/resolve-ref {} nil))))

  (testing "resolve-ref returns nil for missing path"
    (is (nil? (openapi/resolve-ref {} "#/components/schemas/Missing")))))

(deftest test-get-request-schema
  (testing "get-request-schema extracts request body schema"
    (let [spec {}
          operation {"requestBody" {"content" {"application/json"
                                               {"schema" {"type" "object"}}}}}]
      (is (= {"type" "object"} (openapi/get-request-schema spec operation)))))

  (testing "get-request-schema returns nil when no request body"
    (is (nil? (openapi/get-request-schema {} {})))))

(deftest test-get-response-schema
  (testing "get-response-schema extracts 200 response schema"
    (let [spec {}
          operation {"responses" {"200" {"content" {"application/json"
                                                    {"schema" {"type" "object"}}}}}}]
      (is (= {"type" "object"} (openapi/get-response-schema spec operation)))))

  (testing "get-response-schema extracts 201 response schema"
    (let [spec {}
          operation {"responses" {"201" {"content" {"application/json"
                                                    {"schema" {"type" "string"}}}}}}]
      (is (= {"type" "string"} (openapi/get-response-schema spec operation)))))

  (testing "get-response-schema returns nil when no responses"
    (is (nil? (openapi/get-response-schema {} {})))))

(deftest test-tool->call-schema
  (testing "tool->call-schema creates valid schema"
    (let [tool {:operation-id "createItem"
                :path-params ["id"]
                :query-params ["filter"]
                :request-schema {"type" "object"}}
          schema (openapi/tool->call-schema tool)]
      (is (= "object" (get schema "type")))
      (is (= ["operation"] (get schema "required")))
      (is (= "createItem" (get-in schema ["properties" "operation" "const"])))))

  (testing "tool->call-schema handles empty params"
    (let [tool {:operation-id "listItems"
                :path-params []
                :query-params []}
          schema (openapi/tool->call-schema tool)]
      (is (= "listItems" (get-in schema ["properties" "operation" "const"]))))))

(deftest test-format-tool-for-prompt
  (testing "format-tool-for-prompt creates readable output"
    (let [tool {:operation-id "getUser"
                :method :get
                :path "/users/{id}"
                :summary "Get a user by ID"}
          output (openapi/format-tool-for-prompt tool)]
      (is (clojure.string/includes? output "getUser"))
      (is (clojure.string/includes? output "GET"))
      (is (clojure.string/includes? output "/users/{id}"))
      (is (clojure.string/includes? output "Get a user by ID"))))

  (testing "format-tool-for-prompt handles missing summary"
    (let [tool {:operation-id "deleteUser"
                :method :delete
                :path "/users/{id}"}
          output (openapi/format-tool-for-prompt tool)]
      (is (clojure.string/includes? output "deleteUser"))
      (is (clojure.string/includes? output "DELETE")))))

(deftest test-generate-tools-prompt
  (testing "generate-tools-prompt creates full prompt"
    (let [tools [{:operation-id "listItems"
                  :method :get
                  :path "/items"
                  :summary "List all items"}]
          spec {}
          prompt (openapi/generate-tools-prompt "worker" "service" tools spec)]
      (is (clojure.string/includes? prompt "OpenAPI Operations"))
      (is (clojure.string/includes? prompt "listItems"))
      (is (clojure.string/includes? prompt "worker"))
      (is (clojure.string/includes? prompt "service"))))

  (testing "generate-tools-prompt handles empty tools"
    (let [prompt (openapi/generate-tools-prompt "worker" "service" [] {})]
      (is (= "No OpenAPI tools available." prompt)))))

(deftest test-register-openapi-hat
  (testing "register-openapi-hat adds to registry"
    (let [registry {}
          result (openapi/register-openapi-hat registry)]
      (is (contains? result "openapi")))))

(deftest test-execute-operation
  (testing "execute-operation makes GET request"
    (let [config {:base-url "http://api.example.com"}
          tool {:operation-id "getItem"
                :method :get
                :path "/items/{id}"
                :path-params ["id"]
                :query-params []
                :has-body false}
          call {"operation" "getItem" "params" {"id" "123"}}
          mock-response (atom nil)]
      (with-redefs [clj-http.client/get (fn [url _opts]
                                          (reset! mock-response url)
                                          {:status 200
                                           :body {"result" "ok"}})]
        (let [result (openapi/execute-operation config tool call)]
          (is (= "http://api.example.com/items/123" @mock-response))
          (is (= 200 (get result "status")))))))

  (testing "execute-operation makes POST request with body"
    (let [config {:base-url "http://api.example.com"}
          tool {:operation-id "createItem"
                :method :post
                :path "/items"
                :path-params []
                :query-params []
                :has-body true}
          call {"operation" "createItem" "params" {"body" {"name" "test"}}}
          captured (atom nil)]
      (with-redefs [clj-http.client/post (fn [url opts]
                                           (reset! captured {:url url :opts opts})
                                           {:status 201 :body {"id" "new-123"}})]
        (let [result (openapi/execute-operation config tool call)]
          (is (= "http://api.example.com/items" (:url @captured)))
          (is (= 201 (get result "status")))))))

  (testing "execute-operation handles query params"
    (let [config {:base-url "http://api.example.com"}
          tool {:operation-id "listItems"
                :method :get
                :path "/items"
                :path-params []
                :query-params ["limit" "offset"]
                :has-body false}
          call {"operation" "listItems" "params" {"limit" 10 "offset" 0}}
          captured (atom nil)]
      (with-redefs [clj-http.client/get (fn [url opts]
                                          (reset! captured opts)
                                          {:status 200 :body []})]
        (openapi/execute-operation config tool call)
        (is (= {"limit" 10 "offset" 0} (:query-params @captured))))))

  (testing "execute-operation handles bearer auth"
    (let [config {:base-url "http://api.example.com"
                  :auth {:type :bearer :token "secret-token"}}
          tool {:operation-id "getItem" :method :get :path "/items" :path-params [] :query-params []}
          call {"operation" "getItem" "params" {}}
          captured (atom nil)]
      (with-redefs [clj-http.client/get (fn [_url opts]
                                          (reset! captured opts)
                                          {:status 200 :body {}})]
        (openapi/execute-operation config tool call)
        (is (= "Bearer secret-token" (get-in @captured [:headers "Authorization"]))))))

  (testing "execute-operation handles api-key auth"
    (let [config {:base-url "http://api.example.com"
                  :auth {:type :api-key :header "X-API-Key" :value "my-key"}}
          tool {:operation-id "getItem" :method :get :path "/items" :path-params [] :query-params []}
          call {"operation" "getItem" "params" {}}
          captured (atom nil)]
      (with-redefs [clj-http.client/get (fn [_url opts]
                                          (reset! captured opts)
                                          {:status 200 :body {}})]
        (openapi/execute-operation config tool call)
        (is (= "my-key" (get-in @captured [:headers "X-API-Key"]))))))

  (testing "execute-operation handles PUT method"
    (with-redefs [clj-http.client/put (fn [_url _opts] {:status 200 :body {}})]
      (let [result (openapi/execute-operation
                    {:base-url "http://api.example.com"}
                    {:operation-id "updateItem" :method :put :path "/items" :path-params [] :query-params [] :has-body true}
                    {"operation" "updateItem" "params" {"body" {}}})]
        (is (= 200 (get result "status"))))))

  (testing "execute-operation handles DELETE method"
    (with-redefs [clj-http.client/delete (fn [_url _opts] {:status 204 :body nil})]
      (let [result (openapi/execute-operation
                    {:base-url "http://api.example.com"}
                    {:operation-id "deleteItem" :method :delete :path "/items/{id}" :path-params ["id"] :query-params []}
                    {"operation" "deleteItem" "params" {"id" "123"}})]
        (is (= 204 (get result "status"))))))

  (testing "execute-operation handles PATCH method"
    (with-redefs [clj-http.client/patch (fn [_url _opts] {:status 200 :body {}})]
      (let [result (openapi/execute-operation
                    {:base-url "http://api.example.com"}
                    {:operation-id "patchItem" :method :patch :path "/items/{id}" :path-params ["id"] :query-params [] :has-body true}
                    {"operation" "patchItem" "params" {"id" "123" "body" {}}})]
        (is (= 200 (get result "status"))))))

  (testing "execute-operation returns error on exception"
    (with-redefs [clj-http.client/get (fn [_url _opts] (throw (Exception. "Connection refused")))]
      (let [result (openapi/execute-operation
                    {:base-url "http://api.example.com"}
                    {:operation-id "getItem" :method :get :path "/items" :path-params [] :query-params []}
                    {"operation" "getItem" "params" {}})]
        (is (= "Connection refused" (get result "error")))))))

(deftest test-execute-calls
  (testing "execute-calls processes multiple operations"
    (let [config {:base-url "http://api.example.com"}
          tools [{:operation-id "getItem" :method :get :path "/items/{id}" :path-params ["id"] :query-params []}
                 {:operation-id "listItems" :method :get :path "/items" :path-params [] :query-params []}]
          calls [{"operation" "getItem" "params" {"id" "1"}}
                 {"operation" "listItems" "params" {}}]]
      (with-redefs [clj-http.client/get (fn [url _opts]
                                          {:status 200
                                           :body (if (clojure.string/includes? url "/1")
                                                   {"item" "found"}
                                                   [])})]
        (let [results (openapi/execute-calls config tools calls)]
          (is (= 2 (count results)))
          (is (= 200 (get (first results) "status")))
          (is (= 200 (get (second results) "status")))))))

  (testing "execute-calls handles unknown operation"
    (let [config {:base-url "http://api.example.com"}
          tools [{:operation-id "knownOp" :method :get :path "/known" :path-params [] :query-params []}]
          calls [{"operation" "unknownOp" "params" {}}]]
      (let [results (openapi/execute-calls config tools calls)]
        (is (= 1 (count results)))
        (is (clojure.string/includes? (get (first results) "error") "Unknown operation"))))))
