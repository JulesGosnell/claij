(ns claij.llm.grok
  (:require
   [clj-http.client :refer [post]]
   [claij.util :refer [assert-env-var trace clj->json json->clj]]))

;; https://docs.x.ai/docs/api-reference

(def api-key (assert-env-var "XAI_API_KEY"))

(def model "grok-code-fast-1")
(def api-base "https://api.x.ai")
(def api-url (str api-base "/v1/responses"))

(def headers
  {"Authorization" (str "Bearer " api-key)
   "content-type" "application/json"})

(def role "user")
(def system "You are Grok. A top Clojure developer")

(defn send-to-grok
  "Sends a message to xAI's Grok API."
  [message]
  (post
   api-url
   {:headers headers
    :body
    (clj->json
     (trace
      "GROK ->:"
      {:model model
       :input [{:role "user" :content message}]}))
    :throw-exceptions false}))

(defn grok
  "Sends a message to Grok and returns the response text as a seq of strings."
  [s]
  (mapcat (fn [{cs :content}] (map (fn [{t :text}] t) cs)) (:output (trace "GROK <-:" (json->clj (:body (send-to-grok s)))))))

;; grok seems to ignore the model key and keeps thinking that I am trying to jail-break it...
