(ns claij.new.interceptor
  "Interceptor protocol and execution for extending LLM interactions.
  
  Interceptors are the primary extension mechanism. Each interceptor can:
  1. Modify the schema (add fields)
  2. Modify the prompts (add context)
  3. Interpret responses (extract data, update state)
  
  Inspired by Ring middleware but with three distinct hooks."
  (:require [claij.new.schema :refer [base-schema compose-schema]]))

(defn interceptor?
  "Check if x is a valid interceptor.
  
  An interceptor is a map with:
  - :name (required) - identifier for the interceptor
  - :pre-schema (optional) - fn [schema ctx] -> schema
  - :pre-prompt (optional) - fn [prompts ctx] -> prompts  
  - :post-response (optional) - fn [response ctx] -> ctx"
  [x]
  (and (map? x)
       (:name x)
       (or (:pre-schema x)
           (:pre-prompt x)
           (:post-response x))))

(defn execute-pre-schema
  "Execute all pre-schema hooks to compose final schema.
  
  Each interceptor can contribute schema extensions.
  Returns [final-schema ctx]."
  [interceptors base-schema ctx]
  (let [extensions (keep
                    (fn [interceptor]
                      (when-let [f (:pre-schema interceptor)]
                        (try
                          (f base-schema ctx)
                          (catch Exception e
                            (throw (ex-info
                                    (str "Interceptor " (:name interceptor) " pre-schema failed")
                                    {:interceptor (:name interceptor)
                                     :phase :pre-schema}
                                    e))))))
                    interceptors)
        final-schema (compose-schema base-schema extensions)]
    [final-schema ctx]))

(defn execute-pre-prompt
  "Execute all pre-prompt hooks to modify prompts.
  
  Each interceptor can modify the prompts (add context, instructions, etc).
  Returns [final-prompts ctx]."
  [interceptors prompts ctx]
  (let [final-prompts
        (reduce
         (fn [prompts interceptor]
           (if-let [f (:pre-prompt interceptor)]
             (try
               (f prompts ctx)
               (catch Exception e
                 (throw (ex-info
                         (str "Interceptor " (:name interceptor) " pre-prompt failed")
                         {:interceptor (:name interceptor)
                          :phase :pre-prompt}
                         e))))
             prompts))
         prompts
         interceptors)]
    [final-prompts ctx]))

(defn execute-post-response
  "Execute all post-response hooks to interpret response.
  
  Each interceptor can extract data, update state, make decisions.
  Returns updated ctx."
  [interceptors response ctx]
  (reduce
   (fn [ctx interceptor]
     (if-let [f (:post-response interceptor)]
       (try
         (f response ctx)
         (catch Exception e
           (throw (ex-info
                   (str "Interceptor " (:name interceptor) " post-response failed")
                   {:interceptor (:name interceptor)
                    :phase :post-response
                    :response response}
                   e))))
       ctx))
   ctx
   interceptors))

;; Example interceptors

(def memory-interceptor
  "Adds summary field to schema and maintains memory in context."
  {:name :memory

   :pre-schema
   (fn [schema ctx]
     {:properties {:summary {:type "string"
                             :description "Brief summary maintaining continuity: combine previous context with NEW facts from this interaction. Always include: names, preferences, numbers, key decisions. Even if no new facts, restate existing context."}}
      :required ["summary"]})

   :pre-prompt
   (fn [prompts ctx]
     (if-let [memory (:memory ctx)]
       (update prompts :system
               #(str % "\n\nPrevious context: " memory))
       prompts))

   :post-response
   (fn [response ctx]
     (if-let [summary (:summary response)]
       (assoc ctx :memory summary)
       ctx))})

(def logging-interceptor
  "Logs responses for debugging."
  {:name :logging

   :post-response
   (fn [response ctx]
     (println "LLM Response:" response)
     ctx)})

(comment
  ;; Example usage
  (require '[claij.new.schema :as schema])

  (def ctx {:memory "User likes blue"})

  ;; Compose schema with interceptors
  (execute-pre-schema
   [memory-interceptor]
   base-schema
   ctx)
  ;=> [schema-with-summary ctx]

  ;; Modify prompts
  (execute-pre-prompt
   [memory-interceptor]
   {:system "You are a helpful assistant"}
   ctx)
  ;=> [{:system "...\\n\\nPrevious context: User likes blue"} ctx]

  ;; Process response
  (execute-post-response
   [memory-interceptor]
   {:answer "Sure!" :state "ready" :summary "User confirmed action"}
   ctx)
  ;=> {:memory "User confirmed action"}
  )
