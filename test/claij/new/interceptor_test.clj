(ns claij.new.interceptor-test
  (:require [clojure.test :refer [deftest is testing]]
            [claij.new.interceptor :as interceptor]
            [claij.new.schema :as schema]))

(deftest test-interceptor?
  (testing "valid interceptor with all hooks"
    (is (interceptor/interceptor?
         {:name :test
          :pre-schema identity
          :pre-prompt identity
          :post-response identity})))

  (testing "valid interceptor with single hook"
    (is (interceptor/interceptor?
         {:name :test
          :pre-schema identity})))

  (testing "invalid - missing name"
    (is (not (interceptor/interceptor?
              {:pre-schema identity}))))

  (testing "invalid - missing all hooks"
    (is (not (interceptor/interceptor?
              {:name :test}))))

  (testing "invalid - not a map"
    (is (not (interceptor/interceptor? :not-a-map)))))

(deftest test-execute-pre-schema
  (testing "single interceptor adds schema extension"
    (let [test-int {:name :test
                    :pre-schema (fn [schema ctx]
                                  {:properties {:summary {:type "string"}}})}
          [result-schema ctx] (interceptor/execute-pre-schema
                               [test-int]
                               schema/base-schema
                               {})]
      (is (contains? (:properties result-schema) :summary))
      (is (contains? (:properties result-schema) :answer))
      (is (contains? (:properties result-schema) :state))))

  (testing "multiple interceptors compose extensions"
    (let [int1 {:name :int1
                :pre-schema (fn [schema ctx]
                              {:properties {:summary {:type "string"}}})}
          int2 {:name :int2
                :pre-schema (fn [schema ctx]
                              {:properties {:confidence {:type "number"}}})}
          [result-schema ctx] (interceptor/execute-pre-schema
                               [int1 int2]
                               schema/base-schema
                               {})]
      (is (contains? (:properties result-schema) :summary))
      (is (contains? (:properties result-schema) :confidence))
      (is (= 4 (count (:properties result-schema))))))

  (testing "interceptor without pre-schema is skipped"
    (let [test-int {:name :test
                    :pre-prompt identity}
          [result-schema ctx] (interceptor/execute-pre-schema
                               [test-int]
                               schema/base-schema
                               {})]
      (is (= schema/base-schema result-schema))))

  (testing "interceptor pre-schema error includes interceptor name"
    (let [failing-int {:name :failing
                       :pre-schema (fn [schema ctx]
                                     (throw (Exception. "Boom!")))}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"failing.*pre-schema failed"
                            (interceptor/execute-pre-schema
                             [failing-int]
                             schema/base-schema
                             {}))))))

(deftest test-execute-pre-prompt
  (testing "single interceptor modifies prompts"
    (let [test-int {:name :test
                    :pre-prompt (fn [prompts ctx]
                                  (update prompts :system str "\nExtra instructions"))}
          prompts {:system "Base instructions" :user "Hello"}
          [result-prompts ctx] (interceptor/execute-pre-prompt
                                [test-int]
                                prompts
                                {})]
      (is (re-find #"Base instructions" (:system result-prompts)))
      (is (re-find #"Extra instructions" (:system result-prompts)))))

  (testing "multiple interceptors chain modifications"
    (let [int1 {:name :int1
                :pre-prompt (fn [prompts ctx]
                              (update prompts :system str "\nAddition 1"))}
          int2 {:name :int2
                :pre-prompt (fn [prompts ctx]
                              (update prompts :system str "\nAddition 2"))}
          prompts {:system "Base" :user "Hello"}
          [result-prompts ctx] (interceptor/execute-pre-prompt
                                [int1 int2]
                                prompts
                                {})]
      (is (re-find #"Addition 1" (:system result-prompts)))
      (is (re-find #"Addition 2" (:system result-prompts)))))

  (testing "interceptor without pre-prompt is skipped"
    (let [test-int {:name :test
                    :pre-schema identity}
          prompts {:system "Base" :user "Hello"}
          [result-prompts ctx] (interceptor/execute-pre-prompt
                                [test-int]
                                prompts
                                {})]
      (is (= prompts result-prompts))))

  (testing "interceptor pre-prompt error includes interceptor name"
    (let [failing-int {:name :failing
                       :pre-prompt (fn [prompts ctx]
                                     (throw (Exception. "Boom!")))}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"failing.*pre-prompt failed"
                            (interceptor/execute-pre-prompt
                             [failing-int]
                             {}
                             {}))))))

(deftest test-execute-post-response
  (testing "single interceptor updates context"
    (let [test-int {:name :test
                    :post-response (fn [response ctx]
                                     (assoc ctx :processed true))}
          response {:answer "Hello" :state "ready"}
          result-ctx (interceptor/execute-post-response
                      [test-int]
                      response
                      {})]
      (is (:processed result-ctx))))

  (testing "multiple interceptors chain context updates"
    (let [int1 {:name :int1
                :post-response (fn [response ctx]
                                 (assoc ctx :step1 true))}
          int2 {:name :int2
                :post-response (fn [response ctx]
                                 (assoc ctx :step2 true))}
          response {:answer "Hello" :state "ready"}
          result-ctx (interceptor/execute-post-response
                      [int1 int2]
                      response
                      {})]
      (is (:step1 result-ctx))
      (is (:step2 result-ctx))))

  (testing "interceptor without post-response is skipped"
    (let [test-int {:name :test
                    :pre-schema identity}
          response {:answer "Hello" :state "ready"}
          result-ctx (interceptor/execute-post-response
                      [test-int]
                      response
                      {:existing true})]
      (is (= {:existing true} result-ctx))))

  (testing "interceptor post-response error includes interceptor name"
    (let [failing-int {:name :failing
                       :post-response (fn [response ctx]
                                        (throw (Exception. "Boom!")))}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"failing.*post-response failed"
                            (interceptor/execute-post-response
                             [failing-int]
                             {}
                             {}))))))

(deftest test-memory-interceptor
  (testing "memory interceptor adds summary to schema"
    (let [[schema ctx] (interceptor/execute-pre-schema
                        [interceptor/memory-interceptor]
                        schema/base-schema
                        {})]
      (is (contains? (:properties schema) :summary))))

  (testing "memory interceptor adds memory to prompts"
    (let [ctx {:memory "User likes blue"}
          prompts {:system "Base" :user "Hello"}
          [result-prompts _] (interceptor/execute-pre-prompt
                              [interceptor/memory-interceptor]
                              prompts
                              ctx)]
      (is (re-find #"User likes blue" (:system result-prompts)))))

  (testing "memory interceptor extracts summary to context"
    (let [response {:answer "Got it" :state "ready" :summary "User confirmed"}
          result-ctx (interceptor/execute-post-response
                      [interceptor/memory-interceptor]
                      response
                      {})]
      (is (= "User confirmed" (:memory result-ctx))))))

(deftest test-logging-interceptor
  (testing "logging interceptor passes through context unchanged"
    (let [response {:answer "Hello" :state "ready"}
          ctx {:existing true}
          result-ctx (interceptor/execute-post-response
                      [interceptor/logging-interceptor]
                      response
                      ctx)]
      (is (= ctx result-ctx)))))
