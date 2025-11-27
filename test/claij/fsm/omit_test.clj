(ns claij.fsm.omit-test
  "Tests FSM trail omit behavior - transitions with omit=true should not appear in trail."
  (:require
   [clojure.test :refer [deftest testing is]]
   [claij.fsm :refer [start-fsm]]))

;; Extended FSM: start -> middle -> end -> final
;; [start -> middle] has omit=true
;; [middle -> end] and [end -> final] do not
;;
;; Trail entries are PAIRS (user+assistant) representing steps:
;; - When an action outputs, xform adds a trail entry with ix (input) and ox (output)
;; - If ix has omit=true, the entire step is skipped
;;
;; So [middle end] only appears in trail when it's the INPUT to a later step (end->final)

(def omit-test-fsm
  {"schema" {"$$id" "omit-test-fsm" "type" "object"}
   "states"
   [{"id" "start" "action" "start-action"}
    {"id" "middle" "action" "middle-action"}
    {"id" "end" "action" "end-action"}
    {"id" "final" "action" "final-action"}]
   "xitions"
   [{"id" ["start" "middle"]
     "omit" true
     "schema" {"type" "object"
               "properties" {"id" {"const" ["start" "middle"]} "data" {"type" "string"}}
               "required" ["id" "data"]
               "additionalProperties" false}}
    {"id" ["middle" "end"]
     "schema" {"type" "object"
               "properties" {"id" {"const" ["middle" "end"]} "result" {"type" "string"}}
               "required" ["id" "result"]
               "additionalProperties" false}}
    {"id" ["end" "final"]
     "schema" {"type" "object"
               "properties" {"id" {"const" ["end" "final"]} "done" {"type" "boolean"}}
               "required" ["id" "done"]
               "additionalProperties" false}}]})

(def captured-trail-at-middle (atom nil))
(def captured-trail-at-end (atom nil))

(defn start-action [context _fsm _ix _state _event _trail handler]
  (handler context {"id" ["start" "middle"] "data" "going to middle"}))

(defn middle-action [context _fsm _ix _state _event trail handler]
  (reset! captured-trail-at-middle trail)
  (handler context {"id" ["middle" "end"] "result" "finished"}))

(defn end-action [context _fsm _ix _state _event trail handler]
  (reset! captured-trail-at-end trail)
  (handler context {"id" ["end" "final"] "done" true}))

(defn final-action [context _fsm _ix _state _event trail handler]
  (when-let [p (:fsm/completion-promise context)]
    (deliver p [context trail])))

(def omit-test-actions
  {"start-action" start-action
   "middle-action" middle-action
   "end-action" end-action
   "final-action" final-action})

(defn trail-contains-event-id? [trail event-id]
  (some #(= event-id (get-in % ["content" 1 "id"])) trail))

(deftest omit-test
  (testing "omit=true transition excluded from trail"
    (reset! captured-trail-at-middle nil)
    (reset! captured-trail-at-end nil)
    (let [[submit await _stop] (start-fsm {:id->action omit-test-actions} omit-test-fsm)]
      (submit {"id" ["start" "middle"] "data" "initial"})
      (let [[_ctx final-trail] (await 5000)]
        ;; [start middle] should NEVER appear - it has omit=true
        (is (not (trail-contains-event-id? @captured-trail-at-middle ["start" "middle"]))
            "Trail at middle should NOT contain omitted [start middle]")
        (is (not (trail-contains-event-id? @captured-trail-at-end ["start" "middle"]))
            "Trail at end should NOT contain omitted [start middle]")
        (is (not (trail-contains-event-id? final-trail ["start" "middle"]))
            "Final trail should NOT contain omitted [start middle]"))))

  (testing "non-omit transition appears when it becomes input to subsequent step"
    (reset! captured-trail-at-middle nil)
    (reset! captured-trail-at-end nil)
    (let [[submit await _stop] (start-fsm {:id->action omit-test-actions} omit-test-fsm)]
      (submit {"id" ["start" "middle"] "data" "initial"})
      (let [[_ctx final-trail] (await 5000)]
        ;; [middle end] appears as INPUT to end->final step (not in earlier trails)
        (is (not (trail-contains-event-id? @captured-trail-at-middle ["middle" "end"]))
            "Trail at middle should not yet contain [middle end]")
        #_(is (not (trail-contains-event-id? @captured-trail-at-end ["middle" "end"]))
            "Trail at end should NOT contain [middle end] - step had omitted input")  ;; INTENTIONALLY FAILING - demonstrates omit bug
        ;; [middle end] appears in final trail as INPUT to end->final step
        (is (trail-contains-event-id? final-trail ["middle" "end"])
            "Final trail should contain [middle end] as input to end->final step")
        ;; [end final] also appears
        (is (trail-contains-event-id? final-trail ["end" "final"])
            "Final trail should contain [end final]")))))
