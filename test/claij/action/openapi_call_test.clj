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
;; Test FSM Helper
;;------------------------------------------------------------------------------

(defn make-test-fsm
  "Create a minimal FSM for testing openapi-call action.
   
   The action derives its output id from the output xition schema,
   so we need to provide an FSM with proper xition structure."
  [state-id next-state]
  {"id" "test-fsm"
   "states" [{"id" state-id "action" "openapi-call"}
             {"id" next-state "action" "end"}]
   "xitions" [{"id" ["start" state-id]
               "schema" {"type" "object"
                         "required" ["id"]
                         "properties" {"id" {"const" ["start" state-id]}}}}
              {"id" [state-id next-state]
               "schema" {"type" "object"
                         "required" ["id" "status"]
                         "properties" {"id" {"const" [state-id next-state]}
                                       "status" {"type" "integer"}
                                       "body" {}
                                       "content-type" {"type" "string"}}}}]})

;;------------------------------------------------------------------------------
;; Content Type Detection Tests
;;------------------------------------------------------------------------------

(deftest test-binary-content-type-detection
  (testing "audio types are binary"
    (is (true? (openapi-call/binary-content-type? "audio/wav")))
    (is (true? (openapi-call/binary-content-type? "audio/mpeg")))
    (is (true? (openapi-call/binary-content-type? "audio/ogg"))))

  (testing "image types are binary"
    (is (true? (openapi-call/binary-content-type? "image/png")))
    (is (true? (openapi-call/binary-content-type? "image/jpeg"))))

  (testing "video types are binary"
    (is (true? (openapi-call/binary-content-type? "video/mp4")))
    (is (true? (openapi-call/binary-content-type? "video/webm"))))

  (testing "octet-stream is binary"
    (is (true? (openapi-call/binary-content-type? "application/octet-stream"))))

  (testing "json is not binary"
    (is (not (openapi-call/binary-content-type? "application/json"))))

  (testing "text is not binary"
    (is (not (openapi-call/binary-content-type? "text/plain"))))

  (testing "nil is not binary"
    (is (nil? (openapi-call/binary-content-type? nil)))))

(deftest test-multipart-content-type-detection
  (testing "multipart/form-data is multipart"
    (is (true? (openapi-call/multipart-content-type? "multipart/form-data"))))

  (testing "multipart/mixed is multipart"
    (is (true? (openapi-call/multipart-content-type? "multipart/mixed"))))

  (testing "application/json is not multipart"
    (is (not (openapi-call/multipart-content-type? "application/json"))))

  (testing "nil is not multipart"
    (is (nil? (openapi-call/multipart-content-type? nil)))))

;;------------------------------------------------------------------------------
;; Action Creation Tests
;;------------------------------------------------------------------------------

(deftest test-action-creation
  (testing "creates action for valid operation"
    (let [fsm (make-test-fsm "test" "next")
          action (openapi-call/openapi-call-action
                  {"spec-url" "http://localhost:8765/openapi.json"
                   "base-url" "http://localhost:8765"
                   "operation" "listItems"}
                  fsm 0 {"id" "test"})]
      (is (fn? action))))

  (testing "throws for unknown operation"
    (let [fsm (make-test-fsm "test" "next")]
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo
           #"Operation not found"
           (openapi-call/openapi-call-action
            {"spec-url" "http://localhost:8765/openapi.json"
             "base-url" "http://localhost:8765"
             "operation" "nonexistent"}
            fsm 0 {"id" "test"}))))))

;;------------------------------------------------------------------------------
;; HTTP Call Tests
;;------------------------------------------------------------------------------

(deftest test-get-request
  (testing "GET request returns JSON response"
    (let [fsm (make-test-fsm "test-state" "next")
          action (openapi-call/openapi-call-action
                  {"spec-url" "http://localhost:8765/openapi.json"
                   "base-url" "http://localhost:8765"
                   "operation" "listItems"}
                  fsm 0 {"id" "test-state"})
          result (promise)
          handler (fn [_ctx event] (deliver result event))]
      (action {} {"id" ["start" "test-state"]} [] handler)
      (let [r (deref result 5000 :timeout)]
        (is (= 200 (get r "status")))
        (is (map? (get r "body")))
        ;; id now comes from output schema, not reversed input
        (is (= ["test-state" "next"] (get r "id")))))))

(deftest test-post-request-with-json-body
  (testing "POST request with JSON body creates item"
    (let [fsm (make-test-fsm "test-state" "next")
          action (openapi-call/openapi-call-action
                  {"spec-url" "http://localhost:8765/openapi.json"
                   "base-url" "http://localhost:8765"
                   "operation" "createItem"}
                  fsm 0 {"id" "test-state"})
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
    (let [fsm (make-test-fsm "create" "next")
          create-action (openapi-call/openapi-call-action
                         {"spec-url" "http://localhost:8765/openapi.json"
                          "base-url" "http://localhost:8765"
                          "operation" "createItem"}
                         fsm 0 {"id" "create"})
          create-result (promise)]
      (create-action {}
                     {"id" ["s" "create"] "body" {"name" "Fetch Me"}}
                     []
                     (fn [_ e] (deliver create-result e)))
      (let [created (deref create-result 5000 :timeout)
            item-id (get-in created ["body" "id"])]

        ;; Now fetch it by ID
        (let [get-fsm (make-test-fsm "get" "done")
              get-action (openapi-call/openapi-call-action
                          {"spec-url" "http://localhost:8765/openapi.json"
                           "base-url" "http://localhost:8765"
                           "operation" "getItemById"}
                          get-fsm 0 {"id" "get"})
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
    (let [fsm (make-test-fsm "test1" "next")]
      (openapi-call/openapi-call-action
       {"spec-url" "http://localhost:8765/openapi.json"
        "base-url" "http://localhost:8765"
        "operation" "listItems"}
       fsm 0 {"id" "test1"}))

    ;; Stop server - second creation should use cache
    (server/stop-server)

    ;; This should succeed using cached spec
    (let [fsm (make-test-fsm "test2" "next")
          action (openapi-call/openapi-call-action
                  {"spec-url" "http://localhost:8765/openapi.json"
                   "base-url" "http://localhost:8765"
                   "operation" "listItems"}
                  fsm 0 {"id" "test2"})]
      (is (fn? action) "Second action created from cache"))))

;;------------------------------------------------------------------------------
;; Registration Tests
;;------------------------------------------------------------------------------

(deftest test-action-registration-map
  (testing "openapi-call-actions map contains action var"
    (is (contains? openapi-call/openapi-call-actions "openapi-call"))
    (is (var? (get openapi-call/openapi-call-actions "openapi-call")))))

(deftest test-build-multipart-body
  (testing "build-multipart-body handles byte arrays"
    (let [params {"audio" (byte-array [1 2 3])}
          result (#'openapi-call/build-multipart-body params)]
      (is (= 1 (count result)))
      (is (= "audio" (:name (first result))))
      (is (= "audio/wav" (:content-type (first result))))))

  (testing "build-multipart-body handles string values"
    (let [params {"text" "hello world"}
          result (#'openapi-call/build-multipart-body params)]
      (is (= 1 (count result)))
      (is (= "text" (:name (first result))))
      (is (= "hello world" (:content (first result))))))

  (testing "build-multipart-body handles mixed params"
    (let [params {"audio" (byte-array [1 2 3])
                  "language" "en"}
          result (#'openapi-call/build-multipart-body params)]
      (is (= 2 (count result))))))
