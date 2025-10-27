(ns claij.llm.gemini
  (:require
   [clj-http.client :refer [post]]
   [claij.agent.util :refer [assert-env-var trace clj->json json->clj]]))

;; https://ai.google.dev/gemini-api/docs/models

(def api-key (assert-env-var "GEMINI_API_KEY"))
(def model "gemini-2.5-pro")

(def api-base "https://generativelanguage.googleapis.com")
(def api-url (str api-base "/v1beta/models/" model ":generateContent"))

(def headers
  {"x-goog-api-key" api-key
   "content-type" "application/json"})

(defn gemini [s]
  (let [answer
        (post
         api-url
         {:headers headers
          :body
          (clj->json
           (trace
            "GEMINI ->:"
            {:contents
             [{:parts
               [{:text s}]}]}))
          :throw-exceptions false})]
    (map
     :text
     (mapcat
      (comp :parts :content)
      (:candidates
       (trace
        "GEMINI <-:"
        (json->clj
         (:body answer))))))))

