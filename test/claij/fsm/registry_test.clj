(ns claij.fsm.registry-test
  "Tests for FSM registry and OpenAPI spec generation."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [cheshire.core :as json]
   [m3.validate :as m3]
   [claij.fsm.registry :as registry]))

;; =============================================================================
;; OpenAPI Schema Loading
;; =============================================================================

(def openapi-3-1-schema-url "https://spec.openapis.org/oas/3.1/schema/2022-02-27")

(defn load-openapi-schema
  "Load the OpenAPI 3.1 schema from resources."
  []
  (let [schema-file (io/file "resources/schemas/openapi-3.1.json")]
    (when (.exists schema-file)
      (json/parse-string (slurp schema-file)))))

(def openapi-schema (load-openapi-schema))

(defn validate-openapi
  "Validate a document against the OpenAPI 3.1 schema using m3.
   Returns {:valid? true} or {:valid? false :errors [...]}"
  [doc]
  (if openapi-schema
    (m3/validate {:draft :draft2020-12} openapi-schema {} doc)
    {:valid? true :skipped "OpenAPI schema not available"}))

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
  (testing "Empty registry produces valid OpenAPI 3.1 structure"
    (let [spec (registry/generate-openapi-spec {})]
      (is (= "3.1.0" (get spec "openapi")))
      (is (map? (get spec "info")))
      (is (= "https://json-schema.org/draft/2020-12/schema"
             (get spec "jsonSchemaDialect")))
      (is (= {} (get spec "paths")))

      ;; Validate against OpenAPI 3.1 schema
      (let [result (validate-openapi spec)]
        (is (:valid? result)
            (str "Empty spec should validate against OpenAPI 3.1 schema: "
                 (pr-str (:errors result))))))))

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
        (is (get-in endpoint ["responses" "500"])))

      ;; Validate against OpenAPI 3.1 schema
      (let [result (validate-openapi spec)]
        (is (:valid? result)
            (str "Single FSM spec should validate: " (pr-str (:errors result))))))))

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
      (is (contains? paths "/fsm/with-refs/run"))

      ;; Validate against OpenAPI 3.1 schema
      (let [result (validate-openapi spec)]
        (is (:valid? result)
            (str "Multiple FSM spec should validate: " (pr-str (:errors result))))))))

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

(deftest registry-api-test
  (testing "Registry API functions"
    ;; Save current state
    (let [original-registry @registry/fsm-registry]
      (try
        ;; Clear for isolated testing
        (registry/clear-registry!)
        (is (empty? @registry/fsm-registry) "Registry should be empty after clear")

        (testing "register-fsm! returns entry"
          (let [entry (registry/register-fsm! "test-fsm" minimal-fsm)]
            (is (map? entry))
            (is (contains? entry :definition))
            (is (contains? entry :input-schema))
            (is (contains? entry :output-schema))))

        (testing "get-fsm returns definition"
          (is (= minimal-fsm (registry/get-fsm "test-fsm")))
          (is (nil? (registry/get-fsm "nonexistent"))))

        (testing "get-fsm-entry returns full entry"
          (let [entry (registry/get-fsm-entry "test-fsm")]
            (is (map? entry))
            (is (= minimal-fsm (:definition entry)))))

        (testing "list-fsm-ids returns registered ids"
          (is (= ["test-fsm"] (vec (registry/list-fsm-ids)))))

        (testing "list-fsms returns metadata map"
          (let [fsms (registry/list-fsms)]
            (is (map? fsms))
            (is (contains? fsms "test-fsm"))
            (is (contains? (get fsms "test-fsm") :states))
            (is (contains? (get fsms "test-fsm") :transitions))
            (is (contains? (get fsms "test-fsm") :schemas))))

        (testing "register-all! registers multiple FSMs"
          (registry/register-all! {"fsm-a" minimal-fsm
                                   "fsm-b" fsm-with-refs})
          (is (= 3 (count @registry/fsm-registry)))
          (is (some? (registry/get-fsm "fsm-a")))
          (is (some? (registry/get-fsm "fsm-b"))))

        (testing "unregister-fsm! removes and returns entry"
          (let [removed (registry/unregister-fsm! "fsm-a")]
            (is (map? removed))
            (is (nil? (registry/get-fsm "fsm-a")))
            (is (= 2 (count @registry/fsm-registry)))))

        (testing "unregister-fsm! returns nil for nonexistent"
          (is (nil? (registry/unregister-fsm! "nonexistent"))))

        (testing "get-openapi-spec returns current spec"
          (let [spec (registry/get-openapi-spec)]
            (is (map? spec))
            (is (= "3.1.0" (get spec "openapi")))))

        (finally
          ;; Restore original state
          (reset! registry/fsm-registry original-registry))))))

;; =============================================================================
;; OpenAPI Schema Validation Test
;; =============================================================================

(deftest openapi-schema-available
  (testing "OpenAPI 3.1 schema is available for validation"
    (is (some? openapi-schema) "OpenAPI schema should be loaded from resources")
    (when openapi-schema
      (is (= "https://spec.openapis.org/oas/3.1/schema/2022-02-27"
             (get openapi-schema "$id")))
      (is (= "https://json-schema.org/draft/2020-12/schema"
             (get openapi-schema "$schema"))))))

;; =============================================================================
;; Run tests
;; =============================================================================

(comment
  (clojure.test/run-tests 'claij.fsm.registry-test))
