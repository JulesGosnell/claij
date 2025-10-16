(ns claij.agent.gpt
  (:require
   [clj-http.client :refer [post]]
   [claij.agent.util :refer [assert-env-var trace clj->json json->clj]]))

;; https://platform.openai.com/docs/api-reference/responses

(def api-key (assert-env-var "OPENAI_API_KEY"))

(def model "gpt-5-codex")
(def api-base "https://api.openai.com")
(def api-url (str api-base "/v1/responses"))

(def headers
  {"Authorization" (str "Bearer " api-key)
   "content-type" "application/json"})

(def system "You are GPT. A top Clojure developer")

(defn create-conversation [topic]
  (post
   "https://api.openai.com/v1/conversations"
   {:headers headers
    :body
    (clj->json
     {:metadata {:topic topic}
      :items []})
    :throw-exceptions false}))

(defonce conversation-id (:id (json->clj (:body (create-conversation "test")))))

(defn send-to-gpt
  "Sends a message to OpenAI's ChatGPT API."
  [message]
  (post
   api-url
   {:headers headers
    :body
    (clj->json
     (trace
      "GPT ->:"
      {:conversation conversation-id
       :model model
       :input message
       :store false}))
    :throw-exceptions false}))

(defn gpt
  "Sends a message to ChatGPT and returns the response text as a seq of strings."
  [s]
  (map
   (fn [{t :text}] t)
   (mapcat
    (fn [{c :content}] c)
    (filter
     (fn [{t :type}] (= t "message"))
     (:output (trace "GPT <-:" (json->clj (:body (send-to-gpt s)))))))))
