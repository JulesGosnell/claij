(ns claij.fsm.code-review-fsm-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.tools.logging :as log]
   [malli.core :as m]
   [malli.error :as me]
   [claij.util :refer [index-by ->key]]
   [claij.llm.open-router :refer [open-router-async]]
   [claij.fsm :as fsm :refer [start-fsm make-prompts llm-action]]
   [claij.fsm.code-review-fsm :refer [code-review-registry
                                      code-review-fsm]]))

;;------------------------------------------------------------------------------
;; Code Review Schema Tests

(deftest code-review-schema-test
  (testing "Malli registry validates code-review events"
    (testing "entry event validation"
      (let [entry {"id" ["start" "mc"]
                   "document" "Some code to review"
                   "llms" [{"provider" "openai" "model" "gpt-4o"}]
                   "concerns" ["Performance" "Readability"]}]
        (is (m/validate [:ref "entry"] entry {:registry code-review-registry}))))

    (testing "request event validation"
      (let [request {"id" ["mc" "reviewer"]
                     "code" {"language" {"name" "clojure"} "text" "(+ 1 2)"}
                     "notes" "Please review"
                     "concerns" ["Simplicity"]
                     "llm" {"provider" "anthropic" "model" "claude-sonnet-4"}}]
        (is (m/validate [:ref "request"] request {:registry code-review-registry}))))

    (testing "response event validation"
      (let [response {"id" ["reviewer" "mc"]
                      "code" {"language" {"name" "clojure"} "text" "(+ 1 2)"}
                      "comments" ["Looks good!"]}]
        (is (m/validate [:ref "response"] response {:registry code-review-registry}))))

    (testing "summary event validation"
      (let [summary {"id" ["mc" "end"]
                     "code" {"language" {"name" "clojure"} "text" "(+ 1 2)"}
                     "notes" "Review complete"}]
        (is (m/validate [:ref "summary"] summary {:registry code-review-registry}))))

    (testing "invalid events are rejected"
      (is (not (m/validate [:ref "entry"] {"wrong" "data"} {:registry code-review-registry})))
      (is (not (m/validate [:ref "request"] {"id" ["wrong" "transition"]} {:registry code-review-registry}))))))

;;------------------------------------------------------------------------------
;; Code Review FSM Mock Tests

(deftest code-review-fsm-mock-test
  (testing "code-review FSM with mock LLM actions"
    (let [text
          "Please review this fibonacci code: (defn fib [n] (if (<= n 1) n (+ (fib (- n 1)) (fib (- n 2)))))"

          llms
          [{"provider" "openai" "model" "gpt-4o"}]

          ;; Sample concerns for this review
          concerns
          ["Simplicity: Can this be simpler?"
           "Performance: Avoid reflection, consider algorithmic efficiency"
           "Functional style: Use pure functions and immutable data"]

          ;; These are the data payloads that will be in the trail
          entry-data
          {"id" ["start" "mc"]
           "document" text
           "llms" llms
           "concerns" concerns}

          request1-data
          {"id" ["mc" "reviewer"]
           "code" {"language" {"name" "clojure"}
                   "text" "(defn fib [n]\n  (if (<= n 1)\n    n\n    (+ (fib (- n 1)) (fib (- n 2)))))"}
           "notes" "Here's a recursive fibonacci. Please review for improvements."
           "concerns" ["Performance: Avoid reflection, consider algorithmic efficiency"
                       "Functional style: Use pure functions and immutable data"]
           "llm" {"provider" "openai" "model" "gpt-4o"}}

          response1-data
          {"id" ["reviewer" "mc"]
           "code" {"language" {"name" "clojure"}
                   "text" "(def fib\n  (memoize\n    (fn [n]\n      (if (<= n 1)\n        n\n        (+ (fib (- n 1)) (fib (- n 2)))))))"}
           "comments" ["Consider using memoization to avoid redundant calculations"
                       "The algorithm is correct but inefficient for large n"]
           "notes" "Added memoization to improve performance."}

          request2-data
          {"id" ["mc" "reviewer"]
           "code" {"language" {"name" "clojure"}
                   "text" "(def fib\n  (memoize\n    (fn [n]\n      (if (<= n 1)\n        n\n        (+ (fib (- n 1)) (fib (- n 2)))))))"}
           "notes" "Incorporated memoization. Please review again."
           "concerns" ["Simplicity: Can this be simpler?"]
           "llm" {"provider" "openai" "model" "gpt-4o"}}

          response2-data
          {"id" ["reviewer" "mc"]
           "code" {"language" {"name" "clojure"}
                   "text" "(def fib\n  (memoize\n    (fn [n]\n      (if (<= n 1)\n        n\n        (+ (fib (- n 1)) (fib (- n 2)))))))"}
           "comments" []
           "notes" "Looks good! The memoization solves the performance issue."}

          summary-data
          {"id" ["mc" "end"]
           "code" {"language" {"name" "clojure"}
                   "text" "(def fib\n  (memoize\n    (fn [n]\n      (if (<= n 1)\n        n\n        (+ (fib (- n 1)) (fib (- n 2)))))))"}
           "notes" "Code review complete. Added memoization for performance."}

          ;; Map from input data to output data
          event-map
          {entry-data request1-data
           request1-data response1-data
           response1-data request2-data
           request2-data response2-data
           response2-data summary-data}

          llm-action (fn [context _fsm _ix _state event _trail handler]
                       (handler context (event-map event)))

          end-action (fn [context _fsm _ix _state _event trail _handler]
                       ;; Deliver [context trail] to completion promise
                       (when-let [p (:fsm/completion-promise context)]
                         (deliver p [context trail])))

          code-review-actions {"llm" llm-action "end" end-action}

          context {:id->action code-review-actions}

          [submit await stop-fsm] (start-fsm context code-review-fsm)]

      (try
        (submit entry-data)

        (let [result (await 5000)]
          (is (not= result :timeout) "FSM should complete within timeout")
          (when (not= result :timeout)
            (let [[final-context trail] result
                  final-event (fsm/last-event trail)]
              (is (= summary-data final-event) "FSM should complete with summary"))))

        (catch Throwable t
          (is false (str "event submission failed: " (.getMessage t))))

        (finally
          (stop-fsm))))))

;;------------------------------------------------------------------------------
;; Code Review FSM Integration Tests
;; 
;; These tests call REAL LLMs and verify the FSM works end-to-end.
;; They are slow and cost money - run with: clj -X:test :includes [:integration]

(deftest ^:integration code-review-fsm-integration-test
  (testing "code-review FSM with real LLM - simple fibonacci"
    (let [;; Simple fibonacci code to review
          code "(defn fib [n] (if (<= n 1) n (+ (fib (- n 1)) (fib (- n 2)))))"

          ;; Use just one LLM for faster test
          llms [{"provider" "anthropic" "model" "claude-sonnet-4"}]

          ;; Minimal concerns for focused review
          concerns ["Performance: Consider algorithmic efficiency"
                    "Simplicity: Can this be simpler?"]

          ;; End action that delivers [context trail] to completion promise
          end-action (fn [context _fsm _ix _state event trail _handler]
                       (when-let [p (:fsm/completion-promise context)]
                         (deliver p [context (conj trail event)])))

          ;; Use real llm-action from fsm namespace
          code-review-actions {"llm" llm-action "end" end-action}

          context {:id->action code-review-actions}

          entry-msg {"id" ["start" "mc"]
                     "document" (str "Please review this Clojure code: " code)
                     "llms" llms
                     "concerns" concerns}

          ;; Run the FSM
          result (fsm/run-sync code-review-fsm context entry-msg (* 3 60 1000))] ; 3 min timeout

      (is (not= result :timeout) "FSM should complete within timeout")

      (when (not= result :timeout)
        (let [[final-context trail] result
              final-event (fsm/last-event trail)]

          (testing "FSM reached end state"
            (is (map? final-event) "Final event should be a map")
            (is (= ["mc" "end"] (get final-event "id"))
                "FSM should end with mc→end transition"))

          (testing "Final event is valid summary"
            (is (m/validate [:ref "summary"] final-event {:registry code-review-registry})
                (str "Final event should be valid summary schema. Got: "
                     (pr-str final-event)
                     "\nErrors: "
                     (pr-str (me/humanize (m/explain [:ref "summary"] final-event
                                                     {:registry code-review-registry}))))))

          (testing "Trail contains expected transitions"
            ;; Trail should have at least: entry → request → response → summary
            (is (>= (count trail) 4) "Trail should have at least 4 events")
            (let [ids (map #(get % "id") trail)]
              (is (some #{["start" "mc"]} ids) "Should have entry transition")
              (is (some #{["mc" "reviewer"]} ids) "Should have request transition")
              (is (some #{["reviewer" "mc"]} ids) "Should have response transition")
              (is (some #{["mc" "end"]} ids) "Should have summary transition")))

          (testing "Summary has required fields"
            (is (contains? final-event "code") "Summary should have code")
            (is (contains? final-event "notes") "Summary should have notes")
            (let [code-data (get final-event "code")]
              (is (contains? code-data "language") "Code should have language")
              (is (contains? code-data "text") "Code should have text")))

          (log/info "Code review completed successfully")
          (log/info "Final notes:" (get final-event "notes")))))))
