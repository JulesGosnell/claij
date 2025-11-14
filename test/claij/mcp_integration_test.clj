(ns claij.mcp-integration-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.tools.logging :as log]
;;   [claij.mcp :refer [mcp-actions mcp-fsm default-mcp-service mcp-sessions]]
   [claij.fsm :refer [start-fsm]]
   [claij.actions :refer [make-context]]))

;; (defn wait-for-init
;;   "Wait up to timeout-ms for service to be initialized. Returns true if initialized."
;;   [service timeout-ms]
;;   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
;;     (loop []
;;       (cond
;;         @(:initialized? service) true
;;         (> (System/currentTimeMillis) deadline) false
;;         :else (do (Thread/sleep 100) (recur))))))

;; (deftest ^:integration mcp-basic-test
;;   (testing "MCP service initialization and basic operations"
;;     (let [context (make-context {:id->action mcp-actions})
;;           [submit stop-fsm] (start-fsm context mcp-fsm)]

;;       (try
;;         ;; Submit initialization request matching InitializeRequest schema
;;         (submit {"method" "initialize"
;;                  "params" {"protocolVersion" "2025-06-18"
;;                            "capabilities" {"elicitation" {}}
;;                            "clientInfo" {"name" "claij"
;;                                          "version" "1.0-SNAPSHOT"}}})

;;         ;; Wait for service to be created and initialized
;;         (let [service (loop [attempts 0]
;;                         (if-let [s (get @mcp-sessions default-mcp-service)]
;;                           s
;;                           (if (< attempts 50)
;;                             (do (Thread/sleep 100)
;;                                 (recur (inc attempts)))
;;                             nil)))]
;;           (is service "Service should exist")

;;           ;; Wait for initialization to complete
;;           (is (wait-for-init service 10000) "Service should initialize within 10 seconds")

;;           ;; Verify initialization result
;;           (let [init-result @(:init-result service)]
;;             (is init-result "Should have initialization result")
;;             (log/info "MCP service initialized successfully:" init-result)))

;;         (finally
;;           (stop-fsm))))))
