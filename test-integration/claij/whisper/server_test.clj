(ns ^:python-required claij.whisper.server-test
  "Tests for whisper server that don't require Python environment."
  (:require [clojure.test :refer [deftest is testing]]
            [claij.whisper.server :as server]))

(deftest test-create-app
  (testing "create-app returns a function"
    (let [app (server/create-app)]
      (is (fn? app)))))

(deftest test-wrap-logging
  (testing "wrap-logging middleware passes through response"
    (let [test-handler (fn [req] {:status 200 :body "test"})
          wrapped (#'server/wrap-logging test-handler)
          request {:request-method :get :uri "/test"}
          response (wrapped request)]

      (is (= 200 (:status response)))
      (is (= "test" (:body response))))))

;; Note: start-server! and stop-server! tests require Python environment
;; with whisper, torch, etc. These are integration tests that should run with:
;; clojure -M:whisper:test
