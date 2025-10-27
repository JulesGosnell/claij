(ns claij.llm.claude
  (:require
   [clj-http.client :refer [post]]
   [clojure.string :refer [split]]
   [claij.util :refer [assert-env-var trace clj->json json->clj]]))

;; https://docs.claude.com/en/api/overview

(def api-key (assert-env-var "ANTHROPIC_API_KEY"))

(def model "claude-opus-4-1-20250805")
(def api-base "https://api.anthropic.com")
(def api-url (str api-base "/v1/messages"))

(def api-version "2023-06-01") ;; version of RESTful API, not AI

(def headers
  {"x-api-key" api-key
   "anthropic-version" api-version
   "content-type" "application/json"})

(def accumulator
  (atom "There is no conversational state yet."))

;; calude not returning messages in correct format - if he does this - throw them back at him.....
(defn claude
  [request]
  (let [answer
        (post
         api-url
         {:headers headers
          :body
          (clj->json
           (trace
            "CLAUDE ->:"
            {:model model
             :system "You are Claude. A top Clojure developer."
             :max_tokens 4096
             :messages
             [;;{:role "user" :content setup}
              {:role "user" :content @accumulator}
              {:role "user" :content request}
              {:role "user" :content "now please add the following separator after your reply to the above: '041083c4-7bb7-4cb7-884b-3dc4b2bd0413'."}
              {:role "user" :content "and now please summarise everything above the separator - this will be the conversational state for my next request. Please keep it to as few tokens as possible whilst still preserving the necessary. Please read it carefully when I give it back to you as it may include important facts."}]}))
    ;;:as :json
          :throw-exceptions false})
        ;; if count is not 2 we should throw this back until Claude responds with correct format...
        [response new-acc]
        (split
         (get-in
          (trace
           "CLAUDE <-:"
           (json->clj (:body answer)))
          [:content 0 :text])
         #"041083c4-7bb7-4cb7-884b-3dc4b2bd0413")]
    (swap! accumulator (fn [_old new] new) new-acc)
    response))
