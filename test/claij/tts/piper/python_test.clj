(ns claij.tts.piper.python-test
  "Unit tests for Piper backend (no Python required for basic tests)."
  (:require [clojure.test :refer [deftest is testing]]
            [claij.tts.piper.python :as piper]))

(deftest test-create-backend-requires-voice-path
  (testing "create-backend throws without voice-path"
    (is (thrown-with-msg? Exception #"voice-path is required"
                          (piper/create-backend {}))
        "Should throw exception when voice-path is missing")))

(deftest test-create-backend-with-voice-path
  (testing "create-backend accepts voice-path"
    (let [backend (piper/create-backend {:voice-path "/path/to/model.onnx"})]
      (is (some? backend)
          "Should create backend with valid voice-path")
      (is (= "/path/to/model.onnx" (:voice-path backend))
          "Should store voice-path in backend"))))

(deftest test-backend-info-before-initialization
  (testing "backend-info works before initialization"
    (let [backend (piper/create-backend {:voice-path "/path/to/model.onnx"})
          info (claij.tts.core/backend-info backend)]
      (is (map? info)
          "Should return info map")
      (is (= :piper (:backend-type info))
          "Should have correct backend type")
      (is (= "/path/to/model.onnx" (:voice info))
          "Should include voice path")
      (is (false? (:initialized? info))
          "Should not be initialized yet"))))

(deftest test-health-check-before-initialization
  (testing "health-check works before initialization"
    (let [backend (piper/create-backend {:voice-path "/path/to/model.onnx"})
          health (claij.tts.core/health-check backend)]
      (is (map? health)
          "Should return health map")
      (is (:healthy? health)
          "Should be healthy even if not initialized")
      (is (= :piper (:backend-type health))
          "Should have correct backend type"))))

;; Note: Tests that require actual Python/Piper initialization should be in
;; integration tests that run with: clojure -M:piper:test
;; and require PIPER_VOICE_PATH to be set to a valid model file.
