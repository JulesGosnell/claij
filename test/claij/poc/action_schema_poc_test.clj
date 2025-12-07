(ns claij.poc.action-schema-poc-test
  "POC: Actions carry their own schemas as metadata.
   
   The pattern:
   - Each action fn has metadata with :action (discriminator) and :schema (Malli schema)
   - FSM states reference actions by the discriminator value
   - def-fsm can validate state params against the action's schema
   - Actions remain an open set - just define a fn with the right metadata"
  (:require
   [clojure.test :refer [deftest testing is]]
   [malli.core :as m]))

;;==============================================================================
;; Example Actions with Schema Metadata
;;==============================================================================

(defn llm-action
  "An action that calls an LLM."
  {:action "llm"
   :schema [:map {:closed true}
            ["action" [:= "llm"]]
            ["prompts" {:optional true} [:vector :string]]
            ["provider" {:optional true} :string]
            ["model" {:optional true} :string]]}
  [context fsm state event trail]
  ;; Implementation would go here
  :llm-action-called)

(defn end-action
  "An action that ends the FSM."
  {:action "end"
   :schema [:map {:closed true}
            ["action" [:= "end"]]]}
  [context fsm state event trail]
  :end-action-called)

(defn fsm-action
  "An action that embeds another FSM."
  {:action "fsm"
   :schema [:map {:closed true}
            ["action" [:= "fsm"]]
            ["fsm-id" :string]
            ["input-mapping" {:optional true} [:map-of :string :string]]]}
  [context fsm state event trail]
  :fsm-action-called)

;;==============================================================================
;; Action Registry (built from metadata)
;;==============================================================================

(def action-registry
  "Registry mapping action discriminator -> action fn.
   In practice, this could be built by scanning namespaces."
  (->> [#'llm-action #'end-action #'fsm-action]
       (map (fn [v] [(-> v meta :action) v]))
       (into {})))

(defn lookup-action [discriminator]
  (get action-registry discriminator))

(defn action-schema [discriminator]
  (-> (lookup-action discriminator) meta :schema))

;;==============================================================================
;; Validation
;;==============================================================================

(defn validate-state-params
  "Validate a state's parameters against its action's schema.
   Returns nil if valid, error map if invalid."
  [state]
  (let [action-type (get state "action")
        schema (action-schema action-type)]
    (when schema
      (when-not (m/validate schema state)
        {:error :invalid-action-params
         :action action-type
         :state state
         :explanation (m/explain schema state)}))))

;;==============================================================================
;; Tests
;;==============================================================================

(deftest action-metadata-test
  (testing "Actions carry :action discriminator in metadata"
    (is (= "llm" (-> #'llm-action meta :action)))
    (is (= "end" (-> #'end-action meta :action)))
    (is (= "fsm" (-> #'fsm-action meta :action))))
  
  (testing "Actions carry :schema in metadata"
    (is (vector? (-> #'llm-action meta :schema)))
    (is (vector? (-> #'end-action meta :schema)))
    (is (vector? (-> #'fsm-action meta :schema)))))

(deftest action-registry-test
  (testing "Can lookup action by discriminator"
    (is (= #'llm-action (lookup-action "llm")))
    (is (= #'end-action (lookup-action "end")))
    (is (= #'fsm-action (lookup-action "fsm")))
    (is (nil? (lookup-action "unknown")))))

(deftest action-schema-lookup-test
  (testing "Can retrieve schema for action"
    (is (= [:map {:closed true}
            ["action" [:= "llm"]]
            ["prompts" {:optional true} [:vector :string]]
            ["provider" {:optional true} :string]
            ["model" {:optional true} :string]]
           (action-schema "llm")))
    (is (= [:map {:closed true}
            ["action" [:= "end"]]]
           (action-schema "end")))))

(deftest validate-state-params-test
  (testing "Valid llm state passes validation"
    (is (nil? (validate-state-params
               {"action" "llm"
                "prompts" ["You are a helpful assistant."]}))))
  
  (testing "Valid llm state with all optional params"
    (is (nil? (validate-state-params
               {"action" "llm"
                "prompts" ["System prompt"]
                "provider" "anthropic"
                "model" "claude-sonnet-4-20250514"}))))
  
  (testing "Valid end state (no params needed)"
    (is (nil? (validate-state-params
               {"action" "end"}))))
  
  (testing "Valid fsm state"
    (is (nil? (validate-state-params
               {"action" "fsm"
                "fsm-id" "code-review-fsm"}))))
  
  (testing "Invalid: extra params on closed map"
    (let [result (validate-state-params
                  {"action" "end"
                   "unexpected" "param"})]
      (is (some? result))
      (is (= :invalid-action-params (:error result)))))
  
  (testing "Invalid: wrong type for prompts"
    (let [result (validate-state-params
                  {"action" "llm"
                   "prompts" "should be a vector"})]
      (is (some? result))
      (is (= :invalid-action-params (:error result)))))
  
  (testing "Invalid: missing required param"
    (let [result (validate-state-params
                  {"action" "fsm"
                   ;; missing fsm-id which is required
                   })]
      (is (some? result))
      (is (= :invalid-action-params (:error result))))))

(deftest unknown-action-test
  (testing "Unknown action returns nil schema (no validation)"
    (is (nil? (action-schema "unknown")))
    ;; For now, unknown actions pass validation (no schema to check against)
    ;; In production, we might want to fail on unknown actions
    (is (nil? (validate-state-params
               {"action" "unknown"
                "whatever" "params"})))))
