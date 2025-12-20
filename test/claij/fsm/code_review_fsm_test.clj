(ns claij.fsm.code-review-fsm-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.tools.logging :as log]
   [malli.core :as m]
   [malli.error :as me]
   [claij.action :refer [def-action]]
   [claij.fsm :as fsm :refer [start-fsm]]
   [claij.actions :as actions]
   [claij.fsm.code-review-fsm :refer [code-review-registry
                                      code-review-fsm
                                      example-code-review-concerns]]))

;;==============================================================================
;; Mock Actions for Unit Testing
;;==============================================================================
;; These use def-action to avoid deprecation warnings.
;; They read :test/event-map from context to determine responses.
;;==============================================================================

(def-action mock-llm-action
  "Mock LLM action that looks up response in :test/event-map context."
  :any
  [_config _fsm _ix _state]
  (fn [context event _trail handler]
    (let [event-map (:test/event-map context)
          response (get event-map event)]
      (handler context response))))

(def-action mock-end-action
  "Mock end action that delivers completion via promise."
  :any
  [_config _fsm _ix _state]
  (fn [context _event trail _handler]
    (when-let [p (:fsm/completion-promise context)]
      (deliver p [(dissoc context :fsm/completion-promise) trail]))))

;;==============================================================================
;; Tests
;;==============================================================================

(deftest code-review-fsm-test
  (testing "code-review-schemas"
    (testing "entry validates"
      (let [entry {"id" ["start" "mc"]
                   "document" "test code"
                   "llms" [{"service" "openrouter" "model" "openai/gpt-4o"}]
                   "concerns" ["Simplicity"]}]
        (is (m/validate [:ref "entry"] entry {:registry code-review-registry}))))

    (testing "request validates"
      (let [request {"id" ["mc" "reviewer"]
                     "code" {"language" {"name" "clojure"} "text" "(+ 1 2)"}
                     "notes" "Please review"
                     "concerns" ["Simplicity"]
                     "llm" {"service" "openrouter" "model" "openai/gpt-4o"}}]
        (is (m/validate [:ref "request"] request {:registry code-review-registry}))))

    (testing "response validates"
      (let [response {"id" ["reviewer" "mc"]
                      "code" {"language" {"name" "clojure"} "text" "(+ 1 2)"}
                      "comments" ["Looks good"]
                      "notes" "Approved"}]
        (is (m/validate [:ref "response"] response {:registry code-review-registry}))))

    (testing "summary validates"
      (let [summary {"id" ["mc" "end"]
                     "code" {"language" {"name" "clojure"} "text" "(+ 1 2)"}
                     "notes" "Review complete"}]
        (is (m/validate [:ref "summary"] summary {:registry code-review-registry})))))

  (testing "example-code-review-concerns"
    (testing "is a vector of strings"
      (is (vector? example-code-review-concerns))
      (is (every? string? example-code-review-concerns)))

    (testing "contains expected concerns"
      (is (some #(re-find #"Simplicity" %) example-code-review-concerns))
      (is (some #(re-find #"Naming" %) example-code-review-concerns))
      (is (some #(re-find #"YAGNI" %) example-code-review-concerns))))

  (testing "code-review-fsm structure"
    (testing "has required keys"
      (is (contains? code-review-fsm "id"))
      (is (contains? code-review-fsm "states"))
      (is (contains? code-review-fsm "xitions")))

    (testing "has expected states"
      (let [state-ids (set (map #(get % "id") (get code-review-fsm "states")))]
        (is (contains? state-ids "mc"))
        (is (contains? state-ids "reviewer"))
        (is (contains? state-ids "end"))))

    (testing "has expected transitions"
      (let [xition-ids (set (map #(get % "id") (get code-review-fsm "xitions")))]
        (is (contains? xition-ids ["start" "mc"]))
        (is (contains? xition-ids ["mc" "reviewer"]))
        (is (contains? xition-ids ["reviewer" "mc"]))
        (is (contains? xition-ids ["mc" "end"]))))))

(deftest code-review-fsm-mock-test
  (testing "code-review FSM with mock LLM actions"
    (let [text
          "Please review this fibonacci code: (defn fib [n] (if (<= n 1) n (+ (fib (- n 1)) (fib (- n 2)))))"

          llms
          [{"service" "openrouter" "model" "openai/gpt-4o"}]

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
           "llm" {"service" "openrouter" "model" "openai/gpt-4o"}}

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
           "llm" {"service" "openrouter" "model" "openai/gpt-4o"}}

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

          ;; Use def-action vars to avoid deprecation warnings
          code-review-actions {"llm" #'mock-llm-action "end" #'mock-end-action}

          ;; Pass event-map through context for mock actions to use
          context {:id->action code-review-actions
                   :test/event-map event-map}

          {:keys [submit await stop]} (start-fsm context code-review-fsm)]

      (try
        (submit entry-data)

        (let [result (await 5000)]
          (is (not= result :timeout) "FSM should complete within timeout")
          (when (not= result :timeout)
            (let [[_context trail] result
                  final-event (fsm/last-event trail)]
              (is (= summary-data final-event) "FSM should complete with summary"))))

        (catch Throwable t
          (is false (str "event submission failed: " (.getMessage t))))

        (finally
          (stop))))))

(deftest ^:long-running code-review-fsm-integration-test
  (testing "code-review FSM with real LLM - simple fibonacci"
    (let [;; Simple fibonacci code to review
          code "(defn fib [n] (if (<= n 1) n (+ (fib (- n 1)) (fib (- n 2)))))"

          ;; Use just one LLM for faster test
          llms [{"service" "anthropic" "model" "claude-sonnet-4-20250514"}]

          ;; Minimal concerns for focused review
          concerns ["Performance: Consider algorithmic efficiency"
                    "Simplicity: Can this be simpler?"]

          ;; Use vars to preserve action metadata for curried dispatch
          code-review-actions {"llm" #'fsm/llm-action "end" #'actions/end-action}

          context {:id->action code-review-actions}

          entry-msg {"id" ["start" "mc"]
                     "document" (str "Please review this Clojure code: " code)
                     "llms" llms
                     "concerns" concerns}

          ;; Run the FSM
          result (fsm/run-sync code-review-fsm context entry-msg (* 3 60 1000))] ; 3 min timeout

      (is (not= result :timeout) "FSM should complete within timeout")

      (when (not= result :timeout)
        (let [[_context trail] result
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
            ;; Trail entries are {:from :to :event} - extract id from :event
            (let [ids (map #(get-in % [:event "id"]) trail)]
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
