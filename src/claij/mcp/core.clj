;; (ns claij.core
;;   (:require [ring.adapter.jetty :as jetty]
;;             [taoensso.timbre :as log]))

;; (defn handler [request]
;;   (log/info "Received request:" (:uri request))
;;   {:status 200
;;    :headers {"Content-Type" "text/plain"}
;;    :body "WWED Agent is running again!"})

;; (defn -main [& args]
;;   (log/info "Starting WWED Agent on port 8080...")
;;   (jetty/run-jetty handler {:port 8080 :join? false})
;;   (log/info "Agent started! Visit http://localhost:8080"))

(ns claij.mcp.core
  (:require [ring.adapter.jetty :refer [run-jetty]]
            [reitit.ring :refer [ring-handler router]]
            [clj-http.client :refer [post]]
            [clojure.data.json :as json]
            [clojure.core.async :as async :refer [go-loop <! >! >!! <!! alts!]]
            [clojure.tools.logging :as log]))

;; Configuration
(def config
  {:anthropic-api-key (System/getenv "ANTHROPIC_API_KEY")
   :anthropic-api-url "https://api.anthropic.com/v1/messages"
   :webhook-port 8080
   :model "claude-opus-4-1-20250805"
   ;;:model "claude-sonnet-4-20250514"
   :rocketchat-url (or (System/getenv "ROCKETCHAT_URL") "http://localhost:3000")
   :rocketchat-token (System/getenv "ROCKETCHAT_TOKEN")
   :rocketchat-user-id (System/getenv "ROCKETCHAT_USER_ID")})

;; MCP tools configuration
(def mcp-tools-config
  "MCP tool definitions to pass to Claude API"
  ;; These need to match your MCP server configurations
  [{:type "mcp"
    :name "emacs:eval-elisp"
    :config {:command "mcp-server-emacs"}}

   {:type "mcp"
    :name "rocketchat"
    :config {:url "http://localhost:3000"
             :auth_token (System/getenv "ROCKETCHAT_TOKEN")}}

   {:type "mcp"
    :name "clojure-language-server"
    :config {:command "clojure-lsp"}}])

;; Task queues
(def task-queue (async/chan 100))
(def high-priority-queue (async/chan 10))

;; Context management
(def conversation-contexts (atom {}))

(defn call-claude
  "Call Claude API with MCP tools enabled"
  [messages context-id]
  (let [context (get @conversation-contexts context-id {})
        request-body {:model (:model config)
                      :max_tokens 4096
                      :messages messages
                      :system "You are Claude, integrated as a team member via RocketChat."
                      ;;:metadata {:conversation_id context-id}
                      }]
    (log/info "Calling Claude API for context:" context-id)
    (try
      (let [response (post (:anthropic-api-url config)
                           {:headers {"x-api-key" (:anthropic-api-key config)
                                      "anthropic-version" "2023-06-01"
                                      "content-type" "application/json"}
                            :body (json/write-str request-body)
                            :throw-exceptions false})]
        (if (= 200 (:status response))
          (do
            (prn response)
            (log/info "Claude:" (get-in (json/read-str (get-in response [:body]) :key-fn keyword) [:content]))
            (:body response))
          (do
            (log/error "Claude API error:" (:status response) (:body response))
            nil)))
      (catch Exception e
        (log/error e "Failed to call Claude API")
        nil))))

(defn send-to-rocketchat
  "Send message back to RocketChat"
  [channel text]
  (try
    (let [response (post (str (:rocketchat-url config) "/api/v1/chat.postMessage")
                         {:headers {"X-Auth-Token" (:rocketchat-token config)
                                    "X-User-Id" (:rocketchat-user-id config)
                                    "Content-Type" "application/json"}
                          :body (json/write-str
                                 {:channel channel
                                  :text text})
                          :throw-exceptions false})]
      (log/info "Posted to RocketChat:" (:status response)))
    (catch Exception e
      (log/error e "Failed to send to RocketChat"))))

(defn process-rocketchat-webhook
  "Handle incoming RocketChat webhook"
  [request]
  (let [body (-> request :body slurp (json/read-str :key-fn keyword))
        text (:text body)
        user (get-in body [:user :username])
        channel (:channel_name body)
        message-id (:message_id body)]

    (log/info "Received message from" user "in" channel ":" text)

    ;; Check if we should process this message
    (when (or (re-find #"@claude" text)
              (= channel "general")
              (re-find #"URGENT|🚨" text))

      ;; Determine priority
      (let [priority (if (or (re-find #"URGENT|🚨|ASAP" text)
                             (re-find #"production.*down|broken|emergency" text))
                       :high
                       :normal)
            task {:type :rocketchat-message
                  :text text
                  :user user
                  :channel channel
                  :message-id message-id
                  :timestamp (System/currentTimeMillis)}]

        ;; Queue the task
        (if (= priority :high)
          (async/>!! high-priority-queue task)
          (async/>!! task-queue task))))

    {:status 200 :body "OK"}))

(defn task-processor
  "Process tasks from queues"
  []
  (go-loop []
    (let [[task ch] (alts! [high-priority-queue task-queue])]
      (when task
        (log/info "Processing task from" (if (= ch high-priority-queue) "HIGH-PRIORITY" "NORMAL") "queue")
        (case (:type task)
          :rocketchat-message
          (let [context-id (str (:channel task) "-" (:user task))
                messages [{:role "user"
                           :content (:text task)}]
                response (call-claude messages context-id)]

            ;; Extract and post response
            (when response
              (when-let [content (get-in response ["content" 0 "text"])]
                (send-to-rocketchat (:channel task) content))))

          ;; Other task types...
          (log/warn "Unknown task type:" (:type task))))
      (recur))))

;; Web routes
(def app
  (ring-handler
   (router
    [["/webhook" {:post process-rocketchat-webhook}]
     ["/health" {:get (fn [_] {:status 200 :body "OK"})}]
     ["/status" {:get (fn [_] {:status 200
                               :body (json/write-str
                                      {:queue-size (.size task-queue)
                                       :high-priority-queue-size (.size high-priority-queue)
                                       :contexts (count @conversation-contexts)})})}]])))

(defn -main
  "Start the Claude Agent"
  [& args]
  (log/info "Starting WWED Claude Agent on port" (:webhook-port config))
  (log/info "RocketChat URL:" (:rocketchat-url config))

  ;; Validate configuration
  (when (nil? (:anthropic-api-key config))
    (log/error "ANTHROPIC_API_KEY environment variable not set!")
    (System/exit 1))

  ;; Start task processor
  (task-processor)
  (log/info "Task processor started")

  ;; Start web server
  (run-jetty app {:port (:webhook-port config)
                  :join? false})
  (log/info "Webhook server started on port" (:webhook-port config)))
