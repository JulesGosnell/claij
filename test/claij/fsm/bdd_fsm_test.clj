(ns claij.fsm.bdd-fsm-test
  "Tests for Bath Driven Development FSM."
  (:require
   [clojure.test :refer [deftest testing is]]
   [claij.fsm.bdd-fsm :refer [bdd-fsm bdd-schemas bdd-actions
                              make-bdd-context start-bdd run-bdd
                              default-stt-url default-tts-url
                              github-mcp-config clojure-tools-config]]
   [claij.schema :as schema]
   [claij.model :as model]))

;;; ============================================================
;;; Test Helpers
;;; ============================================================

(defn valid-fsm?
  "Basic FSM structure validation for tests."
  [fsm]
  (and (map? fsm)
       (string? (get fsm "id"))
       (vector? (get fsm "states"))
       (vector? (get fsm "xitions"))))

;;; ============================================================
;;; FSM Structure Tests
;;; ============================================================

(deftest bdd-fsm-structure-test
  (testing "FSM has correct id"
    (is (= "bdd" (get bdd-fsm "id"))))

  (testing "FSM has required states"
    (let [state-ids (set (map #(get % "id") (get bdd-fsm "states")))]
      (is (contains? state-ids "stt"))
      (is (contains? state-ids "llm"))
      (is (contains? state-ids "tts"))
      (is (contains? state-ids "end"))))

  (testing "FSM has required transitions"
    (let [xition-ids (set (map #(get % "id") (get bdd-fsm "xitions")))]
      (is (contains? xition-ids ["start" "stt"]))
      (is (contains? xition-ids ["stt" "llm"]))
      (is (contains? xition-ids ["llm" "tts"]))
      (is (contains? xition-ids ["tts" "end"]))))

  (testing "FSM is valid according to fsm-schema"
    (is (valid-fsm? bdd-fsm))))

;;; ============================================================
;;; State Configuration Tests
;;; ============================================================

(deftest stt-state-test
  (testing "STT state uses openapi-call action"
    (let [stt-state (first (filter #(= "stt" (get % "id")) (get bdd-fsm "states")))]
      (is (= "openapi-call" (get stt-state "action")))
      (is (contains? (get stt-state "config") :spec-url))
      (is (contains? (get stt-state "config") :operation))
      (is (= "transcribe" (get-in stt-state ["config" :operation]))))))

(deftest llm-state-test
  (testing "LLM state uses llm action with MCP hat"
    (let [llm-state (first (filter #(= "llm" (get % "id")) (get bdd-fsm "states")))]
      (is (= "llm" (get llm-state "action")))
      (is (vector? (get llm-state "hats")))
      (is (= 1 (count (get llm-state "hats"))))
      ;; Check hat structure
      (let [hat (first (get llm-state "hats"))]
        (is (map? hat))
        (is (contains? hat "mcp"))
        (is (contains? (get hat "mcp") :servers))
        (is (contains? (get-in hat ["mcp" :servers]) "github"))
        (is (contains? (get-in hat ["mcp" :servers]) "clojure")))))

  (testing "LLM state has prompts"
    (let [llm-state (first (filter #(= "llm" (get % "id")) (get bdd-fsm "states")))]
      (is (vector? (get llm-state "prompts")))
      (is (pos? (count (get llm-state "prompts")))))))

(deftest tts-state-test
  (testing "TTS state uses openapi-call action"
    (let [tts-state (first (filter #(= "tts" (get % "id")) (get bdd-fsm "states")))]
      (is (= "openapi-call" (get tts-state "action")))
      (is (contains? (get tts-state "config") :spec-url))
      (is (contains? (get tts-state "config") :operation))
      (is (= "synthesize" (get-in tts-state ["config" :operation]))))))

;;; ============================================================
;;; Schema Tests
;;; ============================================================

(deftest bdd-schemas-test
  (testing "All required schemas are defined"
    (is (contains? bdd-schemas "entry"))
    (is (contains? bdd-schemas "stt-to-llm"))
    (is (contains? bdd-schemas "llm-to-tts"))
    (is (contains? bdd-schemas "exit")))

  (testing "Schemas are JSON Schema format"
    (is (= "object" (get-in bdd-schemas ["entry" "type"])))
    (is (= "object" (get-in bdd-schemas ["llm-to-tts" "type"]))))

  (testing "Entry schema validates correctly"
    (let [entry-schema (get bdd-schemas "entry")
          valid-entry {"id" ["start" "stt"]
                       "audio" (byte-array [1 2 3])}]
      (is (:valid? (schema/validate entry-schema valid-entry)))))

  (testing "LLM-to-TTS schema validates correctly"
    (let [llm-to-tts-schema (get bdd-schemas "llm-to-tts")
          valid-event {"id" ["llm" "tts"]
                       "text" "Hello, this is a test response."}]
      (is (:valid? (schema/validate llm-to-tts-schema valid-event))))))

;;; ============================================================
;;; Configuration Tests
;;; ============================================================

(deftest config-defaults-test
  (testing "Default URLs are set"
    (is (string? default-stt-url))
    (is (string? default-tts-url))
    (is (clojure.string/includes? default-stt-url "prognathodon"))
    (is (clojure.string/includes? default-tts-url "prognathodon")))

  (testing "MCP configs are maps"
    (is (map? github-mcp-config))
    (is (map? clojure-tools-config))
    (is (contains? github-mcp-config "command"))
    (is (contains? clojure-tools-config "command"))))

;;; ============================================================
;;; Context Tests
;;; ============================================================

(deftest make-bdd-context-test
  (testing "Creates context with default values"
    (let [ctx (make-bdd-context {})]
      (is (map? ctx))
      (is (contains? ctx :id->action))
      (is (contains? ctx :llm/service))
      (is (contains? ctx :llm/model))
      (is (contains? ctx :hats))
      (is (= "openrouter" (:llm/service ctx)))
      (is (= (model/openrouter-model :xai) (:llm/model ctx)))))

  (testing "Creates context with custom values"
    (let [ctx (make-bdd-context {:service "anthropic"
                                 :model (model/direct-model :anthropic)})]
      (is (= "anthropic" (:llm/service ctx)))
      (is (= (model/direct-model :anthropic) (:llm/model ctx)))))

  (testing "Context includes hat registry"
    (let [ctx (make-bdd-context {})]
      (is (some? (get-in ctx [:hats :registry])))))

  (testing "Context includes required actions"
    (let [ctx (make-bdd-context {})]
      (is (contains? (:id->action ctx) "llm"))
      (is (contains? (:id->action ctx) "openapi-call"))
      (is (contains? (:id->action ctx) "end")))))

;;; ============================================================
;;; Actions Tests
;;; ============================================================

(deftest bdd-actions-test
  (testing "BDD actions include openapi-call"
    (is (contains? bdd-actions "openapi-call")))

  (testing "BDD actions include default actions"
    (is (contains? bdd-actions "llm"))
    (is (contains? bdd-actions "end"))))
