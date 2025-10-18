(ns claij.tts.handler-test
  "Unit tests for TTS handler (no Python required)."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.data.json :as json]
            [claij.tts.core :as tts]
            [claij.tts.handler :as handler])
  (:import [java.io ByteArrayInputStream]))

;; Mock backend for testing (no Python required)
(defrecord MockBackend []
  tts/TTSBackend
  (initialize! [this] this)
  (synthesize [_this text]
    {:audio-bytes (byte-array 100)
     :sample-rate 22050})
  (synthesize [_this text _options]
    {:audio-bytes (byte-array 100)
     :sample-rate 22050})
  (health-check [_this]
    {:healthy? true
     :backend-type :mock
     :details {:initialized? true}})
  (backend-info [_this]
    {:backend-type :mock
     :version "test"}))

(defn- create-mock-backend []
  (->MockBackend))

(defn- create-test-app []
  (handler/create-app (create-mock-backend)))

(deftest test-health-handler
  (testing "health endpoint returns success"
    (let [request {:uri "/health" :request-method :get}
          app (create-test-app)
          response (app request)]

      (is (= 200 (:status response))
          "Should return 200 status")

      (is (= "application/json" (get-in response [:headers "Content-Type"]))
          "Should return JSON content type")

      (let [body (json/read-str (:body response) :key-fn keyword)]
        (is (:healthy? body)
            "Should return healthy status")
        (is (= "mock" (:backend-type body))
            "Should return backend type")))))

(deftest test-not-found-handler
  (testing "404 handler returns correct response"
    (let [request {:uri "/nonexistent" :request-method :get}
          app (create-test-app)
          response (app request)]
      (is (= 404 (:status response))
          "Should return 404 status")
      (is (= "application/json" (get-in response [:headers "Content-Type"]))
          "Should return JSON content type")
      (let [body (json/read-str (:body response))]
        (is (= "Not found" (get body "error"))
            "Should return error message")))))

(deftest test-app-routing
  (testing "app routes to correct handlers"
    (let [app (create-test-app)]
      (testing "routes to health handler"
        (let [request {:uri "/health" :request-method :get}
              response (app request)]
          (is (= 200 (:status response)))))

      (testing "routes to not-found for unknown paths"
        (let [request {:uri "/unknown" :request-method :get}
              response (app request)]
          (is (= 404 (:status response)))))

      (testing "routes to not-found for wrong method"
        (let [request {:uri "/synthesize" :request-method :get}
              response (app request)]
          (is (= 404 (:status response))))))))

(deftest test-synthesize-handler-with-plain-text
  (testing "synthesize handler with plain text body"
    (let [text "Hello world"
          request {:uri "/synthesize"
                   :request-method :post
                   :headers {"content-type" "text/plain"}
                   :body (ByteArrayInputStream. (.getBytes text "UTF-8"))}
          app (create-test-app)
          response (app request)]

      (is (= 200 (:status response))
          "Should return 200 for valid text")
      (is (= "audio/wav" (get-in response [:headers "Content-Type"]))
          "Should return WAV content type")
      (is (bytes? (:body response))
          "Should return byte array")
      (is (= 100 (alength (:body response)))
          "Should return audio bytes from mock backend"))))

(deftest test-synthesize-handler-with-json
  (testing "synthesize handler with JSON body"
    (let [text "Hello world"
          json-body (json/write-str {:text text})
          request {:uri "/synthesize"
                   :request-method :post
                   :headers {"content-type" "application/json"}
                   :body (ByteArrayInputStream. (.getBytes json-body "UTF-8"))}
          app (create-test-app)
          response (app request)]

      (is (= 200 (:status response))
          "Should return 200 for valid JSON")
      (is (= "audio/wav" (get-in response [:headers "Content-Type"]))
          "Should return WAV content type")
      (is (bytes? (:body response))
          "Should return byte array"))))

(deftest test-synthesize-handler-empty-text
  (testing "synthesize handler fails gracefully with empty text"
    (let [request {:uri "/synthesize"
                   :request-method :post
                   :headers {"content-type" "text/plain"}
                   :body (ByteArrayInputStream. (.getBytes "" "UTF-8"))}
          app (create-test-app)
          response (app request)]

      (is (= 500 (:status response))
          "Should return 500 for empty text")
      (let [body (json/read-str (:body response))]
        (is (contains? body "error")
            "Response should contain error key")))))

(deftest test-synthesize-handler-missing-text-in-json
  (testing "synthesize handler fails with missing text field in JSON"
    (let [json-body (json/write-str {:other "field"})
          request {:uri "/synthesize"
                   :request-method :post
                   :headers {"content-type" "application/json"}
                   :body (ByteArrayInputStream. (.getBytes json-body "UTF-8"))}
          app (create-test-app)
          response (app request)]

      (is (= 500 (:status response))
          "Should return 500 for missing text")
      (let [body (json/read-str (:body response))]
        (is (contains? body "error")
            "Response should contain error key")))))
