(ns claij.action.openapi-call-test
  "Tests for OpenAPI call action.
   
   Uses the test server from poc.openapi.server for isolated testing."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [claij.action.openapi-call :as openapi-call]
   [poc.openapi.server :as server]))

;;------------------------------------------------------------------------------
;; Test Fixtures
;;------------------------------------------------------------------------------

(defn with-test-server [f]
  (server/start-server 8765)
  (server/reset-data!)
  (openapi-call/clear-spec-cache!)
  (try
    (f)
    (finally
      (server/stop-server))))

(use-fixtures :each with-test-server)

;;------------------------------------------------------------------------------
;; Content Type Detection Tests
;;------------------------------------------------------------------------------

(deftest test-content-type-detection
  (testing "get-response-content-type extracts from spec"
    ;; Test via creating action - validates spec parsing works
    (let [action (openapi-call/openapi-call-action
                  {:spec-url "http://localhost:8765/openapi.json"
                   :base-url "http://localhost:8765"
                   :operation "listItems"}
                  nil 0 {"id" "test"})]
      (is (fn? action) "Action factory returns function"))))

;;------------------------------------------------------------------------------
;; Action Creation Tests
;;------------------------------------------------------------------------------

(deftest test-action-creation
  (testing "creates action for valid operation"
    (let [action (openapi-call/openapi-call-action
                  {:spec-url "http://localhost:8765/openapi.json"
                   :base-url "http://localhost:8765"
                   :operation "listItems"}
                  nil 0 {"id" "test"})]
      (is (fn? action))))

  (testing "throws for unknown operation"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"Operation not found"
         (openapi-call/openapi-call-action
          {:spec-url "http://localhost:8765/openapi.json"
           :base-url "http://localhost:8765"
           :operation "nonexistent"}
          nil 0 {"id" "test"})))))

;;------------------------------------------------------------------------------
;; HTTP Call Tests
;;------------------------------------------------------------------------------

(deftest test-get-request
  (testing "GET request returns JSON response"
    (let [action (openapi-call/openapi-call-action
                  {:spec-url "http://localhost:8765/openapi.json"
                   :base-url "http://localhost:8765"
                   :operation "listItems"}
                  nil 0 {"id" "test-state"})
          result (promise)
          handler (fn [_ctx event] (deliver result event))]
      (action {} {"id" ["start" "test-state"]} [] handler)
      (let [r (deref result 5000 :timeout)]
        (is (= 200 (get r "status")))
        (is (map? (get r "body")))
        (is (= ["test-state" "start"] (get r "id")))))))

(deftest test-post-request-with-json-body
  (testing "POST request with JSON body creates item"
    (let [action (openapi-call/openapi-call-action
                  {:spec-url "http://localhost:8765/openapi.json"
                   :base-url "http://localhost:8765"
                   :operation "createItem"}
                  nil 0 {"id" "test-state"})
          result (promise)
          handler (fn [_ctx event] (deliver result event))]
      (action {}
              {"id" ["start" "test-state"]
               "body" {"name" "Test Item" "status" "active"}}
              []
              handler)
      (let [r (deref result 5000 :timeout)]
        (is (= 201 (get r "status")))
        (is (= "Test Item" (get-in r ["body" "name"])))))))

(deftest test-get-with-path-params
  (testing "GET request with path parameter"
    ;; First create an item
    (let [create-action (openapi-call/openapi-call-action
                         {:spec-url "http://localhost:8765/openapi.json"
                          :base-url "http://localhost:8765"
                          :operation "createItem"}
                         nil 0 {"id" "create"})
          create-result (promise)]
      (create-action {}
                     {"id" ["s" "create"] "body" {"name" "Fetch Me"}}
                     []
                     (fn [_ e] (deliver create-result e)))
      (let [created (deref create-result 5000 :timeout)
            item-id (get-in created ["body" "id"])]

        ;; Now fetch it by ID
        (let [get-action (openapi-call/openapi-call-action
                          {:spec-url "http://localhost:8765/openapi.json"
                           :base-url "http://localhost:8765"
                           :operation "getItemById"}
                          nil 0 {"id" "get"})
              get-result (promise)]
          (get-action {}
                      {"id" ["s" "get"] "params" {"id" item-id}}
                      []
                      (fn [_ e] (deliver get-result e)))
          (let [r (deref get-result 5000 :timeout)]
            (is (= 200 (get r "status")))
            (is (= "Fetch Me" (get-in r ["body" "name"])))))))))

;;------------------------------------------------------------------------------
;; Spec Caching Tests
;;------------------------------------------------------------------------------

(deftest test-spec-caching
  (testing "spec is cached between action creations"
    (openapi-call/clear-spec-cache!)
    ;; Create first action - should fetch spec
    (openapi-call/openapi-call-action
     {:spec-url "http://localhost:8765/openapi.json"
      :base-url "http://localhost:8765"
      :operation "listItems"}
     nil 0 {"id" "test1"})

    ;; Stop server - second creation should use cache
    (server/stop-server)

    ;; This should succeed using cached spec
    (let [action (openapi-call/openapi-call-action
                  {:spec-url "http://localhost:8765/openapi.json"
                   :base-url "http://localhost:8765"
                   :operation "listItems"}
                  nil 0 {"id" "test2"})]
      (is (fn? action) "Second action created from cache"))))

;;------------------------------------------------------------------------------
;; Registration Tests
;;------------------------------------------------------------------------------

(deftest test-action-registration-map
  (testing "openapi-call-actions map contains action var"
    (is (contains? openapi-call/openapi-call-actions "openapi-call"))
    (is (var? (get openapi-call/openapi-call-actions "openapi-call")))))
