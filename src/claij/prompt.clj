(ns claij.prompt)


(def schema
  {"$schema" ""
   "$id" ""
   "state" {"type" "string"}
   "last-text" {"type" "string"}
   "text" {"type" "string"}
   "clj" {"type" "string"}
   "required" []
   })


(def prompt "")

;; every answer will be in json
;; validate on receipt and reject if not valid
;; new features are supplied as interceptors

;; each interceptor needs to:
;; accept and return a schema
;; accept and return a document


(defn identity-interceptor [m2]
  [m2
   (fn [m1]
     m1)])

(defn summarising-interceptor [m2]
  ;; adds state related fields
  [m2
   (fn [m1]
     ;; reads and stashes state related fields
     )])


;; interceptors can be composed with comp

