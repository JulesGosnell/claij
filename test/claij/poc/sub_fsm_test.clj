(ns claij.poc.sub-fsm-test
  "PoC test: Sub-FSM composition via fsm-action.
   
   Validates that:
   1. Parent FSM can invoke child FSM via injected loader
   2. Async completion handler chains correctly
   3. Trail records child execution
   4. Context flows through unchanged
   
   Story #54 Phase 1"
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.tools.logging :as log]
   [claij.schema :refer [def-fsm]]
   [claij.action :refer [def-action]]
   [claij.fsm :refer [run-sync last-event]]
   [claij.actions :refer [default-actions with-actions make-fsm-action*]]))

;;; ============================================================
;;; Antonym Action and Sub-FSM
;;; ============================================================

(def antonyms
  {"fast" "slow"
   "hot" "cold"
   "big" "small"
   "light" "dark"
   "up" "down"
   "yes" "no"
   "good" "bad"
   "happy" "sad"})

(def-action antonym-action
  "Maps a word to its antonym."
  {:config {}
   :input {"type" "object" "required" ["word"] "properties" {"word" {"type" "string"}}}
   :output {"type" "object" "required" ["word" "antonym"] "properties" {"word" {"type" "string"} "antonym" {"type" "string"}}}}
  [_config _fsm _ix _state]
  (fn [context {word "word"} _trail handler]
    (let [antonym (get antonyms word "unknown")]
      (log/info "antonym-action:" word "->" antonym)
      (handler context
               {"id" ["processing" "end"]
                "word" word
                "antonym" antonym}))))

;; Simple sub-FSM that maps words to antonyms.
;; start -> processing -> end
(def-fsm antonym-fsm
  {"id" "antonym"
   "description" "Maps words to their antonyms"
   "states" [{"id" "processing"
              "action" "antonym"}
             {"id" "end"
              "action" "end"}]
   "xitions" [{"id" ["start" "processing"]
               "schema" {"type" "object"
                         "required" ["id" "word"]
                         "properties" {"id" {"const" ["start" "processing"]}
                                       "word" {"type" "string"}}}}
              {"id" ["processing" "end"]
               "schema" {"type" "object"
                         "required" ["id" "word" "antonym"]
                         "properties" {"id" {"const" ["processing" "end"]}
                                       "word" {"type" "string"}
                                       "antonym" {"type" "string"}}}}]})

;;; ============================================================
;;; Super-FSM that invokes sub-FSM
;;; ============================================================

(def-action wrapper-action
  "Wraps input for sub-FSM invocation."
  {:config {}
   :input {"type" "object" "required" ["input-word"] "properties" {"input-word" {"type" "string"}}}
   :output {"type" "object" "required" ["word"] "properties" {"word" {"type" "string"}}}}
  [_config _fsm _ix _state]
  (fn [context {input-word "input-word"} _trail handler]
    (log/info "wrapper-action: preparing" input-word "for sub-fsm")
    (handler context
             {"id" ["wrapping" "calling"]
              "word" input-word})))

(def-action unwrapper-action
  "Unwraps result from sub-FSM."
  {:config {}
   :input {"type" "object" "required" ["result"] "properties" {"result" {"type" "object"}}}
   :output {"type" "object" "required" ["original" "opposite"] "properties" {"original" {"type" "string"} "opposite" {"type" "string"}}}}
  [_config _fsm _ix _state]
  (fn [context {result "result"} _trail handler]
    (let [{word "word" antonym "antonym"} result]
      (log/info "unwrapper-action: got" word "->" antonym)
      (handler context
               {"id" ["unwrapping" "end"]
                "original" word
                "opposite" antonym}))))

;; Parent FSM that invokes antonym sub-FSM.
;; start -> wrapping -> calling(sub-fsm) -> unwrapping -> end
(def-fsm super-fsm
  {"id" "super"
   "description" "Parent FSM that invokes antonym sub-FSM"
   "states" [{"id" "wrapping"
              "action" "wrapper"}
             {"id" "calling"
              "action" "fsm"
              "config" {"fsm-id" "antonym"
                        "success-to" "unwrapping"
                        "trail-mode" "summary"}}
             {"id" "unwrapping"
              "action" "unwrapper"}
             {"id" "end"
              "action" "end"}]
   "xitions" [{"id" ["start" "wrapping"]
               "schema" {"type" "object"
                         "required" ["id" "input-word"]
                         "properties" {"id" {"const" ["start" "wrapping"]}
                                       "input-word" {"type" "string"}}}}
              {"id" ["wrapping" "calling"]
               "schema" {"type" "object"
                         "required" ["id" "word"]
                         "properties" {"id" {"const" ["wrapping" "calling"]}
                                       "word" {"type" "string"}}}}
              {"id" ["calling" "unwrapping"]
               "schema" {"type" "object"
                         "required" ["id" "result"]
                         "properties" {"id" {"const" ["calling" "unwrapping"]}
                                       "result" {"type" "object"}
                                       "child-trail" {}}}}
              {"id" ["unwrapping" "end"]
               "schema" {"type" "object"
                         "required" ["id" "original" "opposite"]
                         "properties" {"id" {"const" ["unwrapping" "end"]}
                                       "original" {"type" "string"}
                                       "opposite" {"type" "string"}}}}]})

;;; ============================================================
;;; Stub FSM Loader for Testing
;;; ============================================================

(defn stub-fsm-loader
  "Create a stub loader that returns a fixed FSM for any id.
   Usage: (stub-fsm-loader antonym-fsm) returns a loader fn"
  [fsm]
  (fn [_context _fsm-id _version]
    fsm))

;;; ============================================================
;;; Test Helpers
;;; ============================================================

(defn make-test-context
  "Create a test context with stub loader and test actions."
  [fsm-loader]
  (let [;; Create fsm-action with stub loader
        test-fsm-action (make-fsm-action* fsm-loader)
        ;; Custom actions for this test
        test-actions (merge default-actions
                            {"antonym" #'antonym-action
                             "wrapper" #'wrapper-action
                             "unwrapper" #'unwrapper-action
                             "fsm" test-fsm-action})]
    {:id->action test-actions}))

;;; ============================================================
;;; Tests
;;; ============================================================

(deftest test-antonym-fsm-standalone
  (testing "Antonym sub-FSM works in isolation"
    (let [context {:id->action (merge default-actions
                                      {"antonym" #'antonym-action})}
          [_ctx trail] (run-sync antonym-fsm context {"word" "fast"} 5000)
          result (last-event trail)]

      (is (= "fast" (get result "word")))
      (is (= "slow" (get result "antonym"))))))

(deftest test-antonym-action-directly
  (testing "Antonym action works with various words"
    (doseq [[word expected] antonyms]
      (let [result (atom nil)
            handler (fn [_ctx event] (reset! result event))
            action-fn (antonym-action {} nil nil nil)]
        (action-fn {} {"word" word} [] handler)
        (is (= expected (get @result "antonym"))
            (str word " should map to " expected))))))

(deftest test-sub-fsm-composition
  (testing "Super-FSM invokes sub-FSM and gets result"
    (let [;; Create context with stub loader that returns antonym-fsm
          context (make-test-context (stub-fsm-loader antonym-fsm))
          [_ctx trail] (run-sync super-fsm context {"input-word" "fast"} 10000)
          result (last-event trail)]

      (is (= "fast" (get result "original")))
      (is (= "slow" (get result "opposite")))

      ;; Check trail includes child execution info
      (testing "trail records child FSM execution"
        (let [calling-entry (first (filter #(= "calling" (:from %)) trail))]
          (is (some? calling-entry) "Should have entry from calling state")
          (when calling-entry
            (is (some? (get-in calling-entry [:event "child-trail"]))
                "Should include child-trail summary")))))))

(deftest test-stub-loader-isolation
  (testing "Stub loader returns correct FSM regardless of id"
    (let [loader (stub-fsm-loader antonym-fsm)]
      (is (= antonym-fsm (loader {} "any-id" nil)))
      (is (= antonym-fsm (loader {} "different-id" 42))))))
