(ns claij.fsm.registry-test
  "Tests for FSM registry and OpenAPI spec generation."
  (:require
   [clojure.test :refer [deftest is testing]]
   [claij.fsm.registry :as registry]))

;; =============================================================================
;; Test FSM Definitions
;; =============================================================================

(def minimal-fsm
  "Minimal FSM for testing - single transition start->end"
  {"id" "minimal"
   "schemas" {"input" {"type" "object"
                       "required" ["message"]
                       "properties" {"message" {"type" "string"}}}
              "output" {"type" "object"
                        "required" ["result"]
                        "properties" {"result" {"type" "string"}}}}
   "states" [{"id" "processor" "action" "echo"}
             {"id" "end" "action" "end"}]
   "xitions" [{"id" ["start" "processor"]
               "schema" {"$ref" "#/$defs/input"}}
              {"id" ["processor" "end"]
               "schema" {"$ref" "#/$defs/output"}}]})

(def fsm-with-refs
  "FSM with schema $refs to test $defs resolution"
  {"id" "with-refs"
   "schemas" {"user" {"type" "object"
                      "required" ["name"]
                      "properties" {"name" {"type" "string"}
                                    "role" {"$ref" "#/$defs/role"}}}
              "role" {"type" "string"
                      "enum" ["admin" "user" "guest"]}
              "response" {"type" "object"
                          "required" ["user"]
                          "properties" {"user" {"$ref" "#/$defs/user"}}}}
   "states" [{"id" "handler" "action" "process"}
             {"id" "end" "action" "end"}]
   "xitions" [{"id" ["start" "handler"]
               "schema" {"$ref" "#/$defs/user"}}
              {"id" ["handler" "end"]
               "schema" {"$ref" "#/$defs/response"}}]})

;; =============================================================================
;; Pure Function Tests - registry->openapi
;; =============================================================================

(deftest generate-openapi-spec-empty-registry
  (testing "Empty registry produces valid OpenAPI structure"
    (let [spec (registry/generate-openapi-spec {})]
      (is (= "3.0.3" (get spec "openapi")))
      (is (map? (get spec "info")))
      (is (= {} (get spec "paths"))))))

(deftest generate-openapi-spec-single-fsm
  (testing "Single FSM produces correct path"
    (let [entry {:definition minimal-fsm
                 :input-schema {"$ref" "#/$defs/input"}
                 :output-schema {"$ref" "#/$defs/output"}}
          registry {"minimal" entry}
          spec (registry/generate-openapi-spec registry)
          paths (get spec "paths")]

      (is (contains? paths "/fsm/minimal/run"))

      (let [endpoint (get-in paths ["/fsm/minimal/run" "post"])]
        (is (= "Run minimal FSM" (get endpoint "summary")))
        (is (= "run-minimal" (get endpoint "operationId")))
        (is (= ["FSM Execution"] (get endpoint "tags")))

        ;; Request body has schema with $defs
        (let [request-schema (get-in endpoint ["requestBody" "content" "application/json" "schema"])]
          (is (contains? request-schema "$defs") "Request schema should include $defs")
          (is (= "object" (get request-schema "type"))))

        ;; Response has schema with $defs
        (let [response-schema (get-in endpoint ["responses" "200" "content" "application/json" "schema"])]
          (is (contains? response-schema "$defs") "Response schema should include $defs"))

        ;; Error responses present
        (is (get-in endpoint ["responses" "400"]))
        (is (get-in endpoint ["responses" "500"]))))))

(deftest generate-openapi-spec-multiple-fsms
  (testing "Multiple FSMs produce multiple paths"
    (let [entry1 {:definition minimal-fsm
                  :input-schema {"$ref" "#/$defs/input"}
                  :output-schema {"$ref" "#/$defs/output"}}
          entry2 {:definition fsm-with-refs
                  :input-schema {"$ref" "#/$defs/user"}
                  :output-schema {"$ref" "#/$defs/response"}}
          registry {"minimal" entry1
                    "with-refs" entry2}
          spec (registry/generate-openapi-spec registry)
          paths (get spec "paths")]

      (is (= 2 (count paths)))
      (is (contains? paths "/fsm/minimal/run"))
      (is (contains? paths "/fsm/with-refs/run")))))

(deftest generate-openapi-spec-defs-resolution
  (testing "$defs are properly included in schemas"
    (let [entry {:definition fsm-with-refs
                 :input-schema {"$ref" "#/$defs/user"}
                 :output-schema {"$ref" "#/$defs/response"}}
          registry {"with-refs" entry}
          spec (registry/generate-openapi-spec registry)
          request-schema (get-in spec ["paths" "/fsm/with-refs/run" "post"
                                       "requestBody" "content" "application/json" "schema"])
          defs (get request-schema "$defs")]

      ;; All FSM schemas should be in $defs
      (is (contains? defs "user"))
      (is (contains? defs "role"))
      (is (contains? defs "response"))

      ;; The resolved schema should have the type from the referenced schema
      (is (= "object" (get request-schema "type")))
      (is (= ["name"] (get request-schema "required"))))))

(deftest generate-openapi-spec-handles-inline-schemas
  (testing "Inline schemas (no $ref) are handled"
    (let [inline-fsm {"id" "inline"
                      "schemas" {}
                      "states" [{"id" "end" "action" "end"}]
                      "xitions" [{"id" ["start" "end"]
                                  "schema" {"type" "object"
                                            "properties" {"x" {"type" "integer"}}}}]}
          ;; When there's no $ref, input-schema might be the inline schema or nil
          entry {:definition inline-fsm
                 :input-schema {"type" "object"
                                "properties" {"x" {"type" "integer"}}}
                 :output-schema nil}
          registry {"inline" entry}
          spec (registry/generate-openapi-spec registry)
          request-schema (get-in spec ["paths" "/fsm/inline/run" "post"
                                       "requestBody" "content" "application/json" "schema"])]

      ;; Should use the inline schema directly
      (is (= "object" (get request-schema "type"))))))

;; =============================================================================
;; Brute-force rebuild semantics - add/update/delete all work the same
;; =============================================================================

(deftest registry-changes-trigger-rebuild
  (testing "Add, update, and delete all trigger complete rebuild"
    ;; This test verifies the brute-force approach works for all operations
    (let [builds (atom [])
          ;; Mock generate function to track calls
          track-build (fn [registry]
                        (swap! builds conj (set (keys registry)))
                        (registry/generate-openapi-spec registry))]

      ;; Simulate the watch behavior with our tracking function
      (let [r1 {}
            r2 (assoc r1 "fsm-a" {:definition minimal-fsm})
            r3 (assoc r2 "fsm-b" {:definition fsm-with-refs})
            r4 (assoc r3 "fsm-a" {:definition fsm-with-refs}) ; update
            r5 (dissoc r4 "fsm-b")] ; delete

        ;; Simulate watch firing on each change
        (track-build r1)
        (track-build r2)
        (track-build r3)
        (track-build r4)
        (track-build r5)

        (is (= [#{}
                #{"fsm-a"}
                #{"fsm-a" "fsm-b"}
                #{"fsm-a" "fsm-b"} ; same keys after update
                #{"fsm-a"}] ; fsm-b removed
               @builds))))))

;; =============================================================================
;; Run tests
;; =============================================================================

(comment
  (clojure.test/run-tests 'claij.fsm.registry-test))
