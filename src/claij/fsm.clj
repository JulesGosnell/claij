(ns claij.fsm
  (:require
   [clojure.string :refer [join]]
   [clojure.tools.logging :as log]
   [clojure.data.json :refer [write-str]]
   [clojure.core.async :refer [chan go-loop alts! >!! close!]]
   [m3.uri :refer [parse-uri uri-base]]
   [m3.validate :refer [validate make-context uri->continuation uri-base->dir]]
   [claij.util :refer [def-m2 def-m1 index-by ->key map-values make-retrier]]
   [claij.llm.open-router :refer [open-router-async]]))

;; the plan
;; to transition we need a json document - a "proposal"
;; this either comes from a request (us->llm) or a response (llm->us)
;; the fsm is direction agnostic
;; each xition carries a schema
;; the document will be validated against oneOf these schemas
;; it will make the xition of which it validates against the schema
;; when making a request, we will send a "oneOf" the xition from our state's schemas
;; the llm will make a response that conforms to the xition it wants to make
;; if it validates, we will make the xition, otherwise we go back to the llm til it gets it right

;; a transition ensures that you hve all the correct parameter for the following state
;; the state is one of:
;; -  human
;; - an llm
;; - an mcp service
;; - a repl
;; - a yet-to-be-invented extension
;; the state takes its input and provides an output

;; thoughts
;; - if the action is going to cause a request to kick off to an llvm then we need to pass in everything that it might need...
;; - fsm
(def meta-schema-uri "https://json-schema.org/draft/2020-12/schema")

;; if the last xition was a response from an llm then the next will be
;; How can we work it so that the fsm has no concept of request/response but simply connects input channels to output channels -  put together a small demo that evaluates a piece of dsl
;; we need to use async http calls ...
;;------------------------------------------------------------------------------

(def schema-draft :draft2020-12)

(def-m2
  fsm-m2
  {"$schema" "https://json-schema.org/draft/2020-12/schema"
   ;;"$id" "https://example.com/fsm-schema" ;; $id is messing up $refs :-(
   "$version" 0

   "$defs"
   {"prompt"
    {"type" "string"}
    ;; {"type" "object"
    ;;  "properties"
    ;;  {"role"
    ;;   {"type" "string"
    ;;    "enum" ["system" "user" "assistant"]}
    ;;   "content"
    ;;   {"type" "string"}}
    ;;  "additionalProperties" false
    ;;  "required" ["role" "content"]}

    "prompts"
    {"type" "array"
     "items" {"$ref" "#/$defs/prompt"}}

;; Schema reference constraint - enforces $ref format only for compression
    "schema-ref"
    {"type" "object"
     "properties"
     {"$ref" {"type" "string"
              "pattern" "^#/\\$defs/.+"}}
     "required" ["$ref"]
     "additionalProperties" false}

    ;; Valid JSON Schema constraint
    "json-schema"
    {"$ref" "https://json-schema.org/draft/2020-12/schema"}

    "state"
    {"type" "object"
     "properties"
     {"id"
      {"type" "string"}

      "description"
      {"type" "string"}

      "action"
      {"type" "string"}

      "prompts"
      {"$ref" "#/$defs/prompts"}}

     ;; "if"
     ;; {"properties" {"action" {"const" "llm"}}}
     ;; "then"
     ;; {"required" ["prompts"]}
     ;; "else"
     ;; {"properties" {"prompts" false}}

     "additionalProperties" false
     "required" ["id"
                 ;;"action" ;; TODO
                 ]}

    "xition"
    {"type" "object"
     "properties"
     {"id"
      {"type" "array"
       "prefixItems"
       [{"type" "string"}
        {"type" "string"}]
       "unevaluatedItems" false}

      "label"
      {"type" "string"}

      "description"
      {"type" "string"}

      "prompts"
      {"$ref" "#/$defs/prompts"}

      ;; TODO: tighten to {"oneOf" [{"$ref" "#/$defs/json-schema"} {"type" "string"}]}
      ;; String values are lookup keys into context :id->schema for dynamic schema generation
      "schema" true}
     "additionalProperties" false
     "required" ["id" "schema"]}}

   "type" "object"
   "properties"
   {"id"
    {"type" "string"}

    "description"
    {"type" "string"}

    "schema" {"$ref" "#/$defs/json-schema"}

    "prompts"
    {"$ref" "#/$defs/prompts"}

    "states"
    {"type" "array"
     "items" {"$ref" "#/$defs/state"}}

    "xitions"
    {"type" "array"
     "items" {"$ref" "#/$defs/xition"}}}

   "additionalProperties" false})

(defmacro def-fsm [name m1]
  `(def-m1 ~name fsm-m2 ~m1))

;;------------------------------------------------------------------------------

(def schema-base-uri "https://claij.org/schemas")

;; TODO - fsm needs a unique id - 
;; TODO: consider version...
(defn xition-id->schema-uri [state-id]
  (str schema-base-uri "/" "FSM-ID" "/" "FSM-VERSION" "/" state-id "/transitions"))

;;------------------------------------------------------------------------------
;; m3 hack city - m3 should make this easier !

(def u->s
  {{:type :url, :origin "http://jules.com", :path "/schemas/test"}
   {"$schema" "https://json-schema.org/draft/2020-12/schema"
    ;;"$id" "http://jules.com/schemas/test"
    "$defs" {"foo" {"type" "string"}}}})

;; add map (m) of base-uri->m2 to m3's remote schema resolution strategy...

(defn uri->schema [m c p uri]
  (if-let [m2 (m (uri-base uri))]
    [(-> (make-context
          (-> c
              (select-keys [:uri->schema :trace? :draft :id-key])
              (assoc :id-uri (uri-base uri)))
          m2)
         (assoc :id-uri (uri-base uri))
         (update :uri->path assoc (uri-base uri) []))
     []
     m2]
    ((uri->continuation uri-base->dir) c p uri)))

;;------------------------------------------------------------------------------

(defn expand-schema [id schema]
  {"$schema" {"type" "string"}
   "$id" {"type" "string"}
   "id" {"const" id}
   "document" schema})

(defn resolve-schema
  "Resolve a transition schema, supporting dynamic schema generation.
   
   If schema is a string, looks up (get-in context [:id->schema schema-key])
   to get a schema function, then calls (schema-fn context xition).
   
   If schema is a map (or other), returns it unchanged.
   
   NOTE: Future optimization opportunity - when we have the event and the
   resolved schema is a oneOf/anyOf, we could narrow it further based on
   event content. Currently we only narrow to the selected transition,
   not within a transition's schema."
  [context xition schema]
  (if (string? schema)
    (if-let [schema-fn (get-in context [:id->schema schema])]
      (schema-fn context xition)
      (do
        (log/warn "No schema function found for key:" schema)
        true))
    schema))

(defn state-schema
  "Make the schema for a state - to be valid for a state, you must be valid for one (only) of its output xitions.
   Resolves dynamic schemas via context :id->schema lookup."
  [context {fid "id" fv "version" fs "schema" :as _fsm} {sid "id" :as _state} xs]
  {"oneOf" (mapv (fn [{s "schema" :as xition}]
                   (resolve-schema context xition s))
                 xs)})

(defn xition-schema
  "make the schema for a transition - embed its schema field in a larger context"
  [{fid "id" fv "version" :as _fsm} {[from to :as xid] "id" s "schema" :as _xition}]
  {"$schema" meta-schema-uri
   "$$id" (format "%s/%s/%d/%s.%s" schema-base-uri fid (or fv 0) from to)
   "properties" (expand-schema xid s)})

;; rename "action" "transform"
;; TODO: if we inline cform, we may be able to more work outside and less inside it...
;; $$id is a hack because $id breaks $ref resolution - investigate
(defn xform [context {{fsm-schema-id "$$id" :as fsm-schema} "schema" :as fsm} {[from to] "id" :as ix} {a "action" :as state} ox-and-cs [{r "role" [is input] "content" :as head} & trail :as all]]
  (try
    (log/info (str "=> [" from " -> " to "]"))

    (let [s-schema (state-schema context fsm state (map first ox-and-cs))
          id->x-and-c (index-by (comp (->key "id") first) ox-and-cs)
          retry-count (atom 0)
          max-retries 3
          retrier (make-retrier max-retries)
          initial-trail (cons {"role" r "content" [is input s-schema]} trail)

          ;; Handler validates output and retries on failure
          ;; Closes over initial-trail and extends it on retry
          handler
          (fn handler
            ([new-context event] (handler new-context event initial-trail))
            ([new-context {ox-id "id" :as event} current-trail]
             (try
               (let [[{ox-schema-raw "schema" :as ox} c] (id->x-and-c ox-id)
                     _ (when (nil? ox) (log/error "no output xition:" (prn-str ox-id)))
                     ox-schema (resolve-schema new-context ox ox-schema-raw)
                     {v? :valid? es :errors}
                     (validate
                      {:draft schema-draft
                       :uri->schema (partial uri->schema {(uri-base (parse-uri fsm-schema-id)) fsm-schema})}
                      ox-schema
                      {}
                      event)]
                 (if v?
                     ;; SUCCESS - Put context and trail on channel
                   (do
                     (log/info (str "   [OK] Validation: " ox-id))
                     (>!! c {:context new-context
                             :trail (cons {"role" "assistant" "content" [ox-schema event nil]}
                                          (cons {"role" r "content" [is input ox-schema]} trail))})
                     nil)
                     ;; FAILURE - Retry with error feedback
                   (retrier
                    @retry-count
                       ;; Retry operation
                    (fn []
                      (log/error (str "   [X] Validation Failed: " ox-id " (attempt " (inc @retry-count) "/" max-retries ")"))
                      (swap! retry-count inc)
                      (let [error-msg (str "Your response failed schema validation.\n\n"
                                           "Validation errors: " (pr-str es) "\n\n"
                                           "Please review the schema and provide a corrected response.")
                            error-schema {"type" "string"}
                               ;; Build error trail entry: [input-schema, error-message, event-schema]
                            error-trail (cons {"role" "user"
                                               "content" [error-schema error-msg ox-schema]}
                                              current-trail)]
                        (log/info "      [>>] Sending validation error feedback to LLM")
                           ;; Call action again with error in trail
                        (if-let [action (get-in context [:id->action a])]
                          (action new-context fsm ix state error-trail handler)
                          (handler new-context (second (first error-trail)) error-trail))))
                       ;; Max retries exceeded
                    (fn []
                      (log/error (str "   [X] Max Retries Exceeded: Validation failed after " max-retries " attempts"))
                      (log/error (str "   Final output: " (pr-str event)))
                      (log/debug (str "   Validation errors: " (pr-str es)))))))
               (catch Throwable t
                 (log/error t "Error in handler processing")))))]

      ;; Call action with handler
      (if-let [action (get-in context [:id->action a])]
        (do
          (log/info (str "   Action: " a))
          (action context fsm ix state initial-trail handler))
        (handler context (second (first initial-trail)))))
    (catch Throwable t
      (log/error t "Error in xform"))))

;; TODO: FSM Structure Validation
;; =============================
;; FSM structural validation should happen at load/definition time (in def-fsm macro),
;; not at runtime in start-fsm. Required validations:
;;
;; 1. FSM must contain "start" and "end" states in the states array
;; 2. There must be at least one transition from "start" (entry point)
;; 3. There must be at least one transition to "end" (exit point)
;; 4. All transition "id" components must reference valid state IDs
;; 5. All states must be reachable (graph connectivity check)
;; 6. No orphaned states (states with no incoming or outgoing transitions)
;;
;; This validation should be part of the def-fsm macro expansion or a separate
;; validate-fsm-structure function called during FSM definition.

;; TODO: Multiple Start Transitions
;; =================================
;; Current implementation assumes exactly one transition from "start".
;; However, FSMs may have multiple start transitions for different entry points:
;;   ["start" "initial-review"]
;;   ["start" "emergency-fix"]
;;   ["start" "automated-scan"]
;;
;; Potential solutions:
;; 1. Require explicit start state parameter: (start-fsm actions fsm "initial-review")
;; 2. Support named entry points with multiple submit functions
;; 3. Default to first transition, allow override
;; 4. Make it an error - FSM must have exactly one start transition
;;
;; Decision needed on the desired semantics.

(defn start-fsm
  "Start an FSM with context and FSM definition. The start state is automatically 
   inferred from the entry transition (the transition with 'start' as the source state).
   
   Context should contain:
   - :id->action - Map of action-id to action function
   - Other keys as needed by actions (e.g. :store, :provider, :model)
   
   Returns [submit await stop] where:
   - submit: Function to submit input data to the FSM
   - await: Function to wait for FSM completion, optionally with timeout-ms
   - stop: Function to stop the FSM
   
   Usage:
     (let [[submit await stop] (start-fsm context fsm)]
       (submit input-data)
       (let [result (await 5000)]  ; Wait up to 5 seconds
         (if (= result :timeout)
           (println \"FSM timed out\")
           (println \"FSM completed:\" result))))
   
   NOTE: Currently assumes exactly one transition from 'start'. See TODO above."
  [context {ss "states" xs "xitions" :as fsm}]
  (let [;; Create completion promise (internal to start-fsm)
        completion-promise (promise)
        context (assoc context :fsm/completion-promise completion-promise)

        ;; Create channels and mappings
        id->x (index-by (->key "id") xs)
        id->s (index-by (->key "id") ss)
        xid->c (map-values (fn [_k _v] (chan)) id->x)
        all-channels (vals xid->c)
        xs->x-and-cs (fn [_ xs] (mapv (juxt identity (comp xid->c (->key "id"))) xs))
        sid->ix-and-cs (map-values xs->x-and-cs (group-by (comp second (->key "id")) xs))
        sid->ox-and-cs (map-values xs->x-and-cs (group-by (comp first (->key "id")) xs))

        ;; Extract entry transition info
        [[{[_ start-state :as entry-xition-id] "id" :as entry-xition}]] (sid->ox-and-cs "start")
        entry-channel (xid->c entry-xition-id)
        entry-schema (get entry-xition "schema")
        start-state-obj (id->s start-state)
        output-xitions (map first (sid->ox-and-cs start-state))
        output-schema (state-schema context fsm start-state-obj output-xitions)

        ;; Error handling for channel operations
        safe-channel-operation (fn [op ch]
                                 (try
                                   (op ch)
                                   (catch Exception e
                                     (log/error e "Error operating on channel"))))

        ;; Create interface functions
        submit (fn [input-data]
                 ;; Wrap trail with context in message
                 (let [trail [{"role" "user"
                               "content" [entry-schema input-data output-schema]}]
                       message {:context context :trail trail}]
                   (safe-channel-operation #(>!! entry-channel %) message)
                   (log/info (str "   Submitted document to FSM: " start-state))))

        await (fn
                ([] (deref completion-promise))
                ([timeout-ms] (deref completion-promise timeout-ms :timeout)))

        stop-fsm (fn []
                   (log/info (str "stopping fsm: " (or (get fsm "id") "unnamed")))
                   (doseq [c all-channels]
                     (safe-channel-operation close! c)))]

    (log/info (str "starting fsm: " (or (get fsm "id") "unnamed")
                   " (" (count ss) " states, " (count xs) " transitions)"))

    ;; Start all the state loops
    (doseq [{id "id" :as s} ss]
      (let [ix-and-cs (sid->ix-and-cs id)
            ox-and-cs (sid->ox-and-cs id)
            ic->ix (into {} (mapv (fn [[x c]] [c x]) ix-and-cs))
            cs (keys ic->ix)]
        (when (seq cs)
          (go-loop []
            (let [[{ctx :context trail :trail :as msg} ic] (alts! cs)]
              (when msg
                (let [ix (ic->ix ic)]
                  (xform ctx fsm ix s ox-and-cs trail)
                  (recur))))))))

    ;; Return interface
    [submit await stop-fsm]))

(defn last-event
  "Extract the final event from a trail.
   
   Takes a trail (vector of trail entries) and returns the event from the
   most recent entry (the head of the trail).
   
   Usage:
     (let [[context trail] (await)]
       (last-event trail))  ; Returns the final event"
  [trail]
  (second (get (first trail) "content")))

(defn run-sync
  "Run an FSM synchronously, blocking until completion.
   
   Starts the FSM, submits the input data, and waits for completion.
   Returns [context trail] tuple containing final context and execution trail.
   
   Optional timeout-ms parameter (default 30000ms/30s) prevents indefinite blocking.
   Returns :timeout keyword if timeout is reached.
   
   Usage:
     (let [[context trail] (run-sync fsm ctx input-data)]
       (println \"Final event:\" (last-event trail)))
     
     ;; With timeout check
     (let [result (run-sync fsm ctx input-data 5000)]
       (if (= result :timeout)
         (println \"FSM timed out\")
         (let [[context trail] result]
           (println \"FSM completed:\" (last-event trail)))))"
  ([fsm context input-data]
   (run-sync fsm context input-data 30000))
  ([fsm context input-data timeout-ms]
   (let [[submit await stop] (start-fsm context fsm)]
     (try
       (submit input-data)
       (await timeout-ms)
       (finally
         (stop))))))

;;------------------------------------------------------------------------------
;; LLM Action Support
;;------------------------------------------------------------------------------

(def llm-configs
  "Configuration for different LLM providers and models.
   Each entry maps [provider model] to a config map with:
   - :prompts - vector of prompt maps with :role and :content
   - Future: could include :temperature, :max-tokens, etc."
  {["anthropic" "claude-sonnet-4.5"]
   {:prompts [{:role "system"
               :content "CRITICAL: Your response must be ONLY valid JSON - no explanatory text before or after."}
              {:role "system"
               :content "CRITICAL: Ensure your JSON is complete - do not truncate. Check that all braces and brackets are closed."}
              {:role "system"
               :content "CRITICAL: Be concise in your response to avoid hitting token limits."}]}

   ;; OpenAI models - generally work well with standard prompts
   ["openai" "gpt-4o"] {}
   ["openai" "gpt-5-codex"] {}

   ;; xAI models
   ["x-ai" "grok-code-fast-1"] {}
   ["x-ai" "grok-4"] {}

   ;; Google models
   ["google" "gemini-2.5-flash"] {}})

(defn make-prompts
  "Build prompt messages from FSM configuration and conversation trail.
   Optionally accepts provider/model for LLM-specific prompts."
  ([fsm ix state trail]
   (make-prompts fsm ix state trail nil nil))
  ([{fsm-schema "schema" fsm-prompts "prompts" :as _fsm}
    {ix-prompts "prompts" :as _ix}
    {state-prompts "prompts"}
    trail
    provider
    model]
   (let [;; Look up LLM-specific configuration
         llm-config (get llm-configs [provider model] {})
         llm-prompts (get llm-config :prompts [])

         ;; Separate system and user prompts from LLM config
         llm-system-prompts (mapv :content (filter #(= (:role %) "system") llm-prompts))
         llm-user-prompts (mapv :content (filter #(= (:role %) "user") llm-prompts))]

     (concat
      ;; Build system message with all system-level prompts
      [{"role" "system"
        "content" (join
                   "\n"
                   (concat
                    ["All your requests and responses will be in JSON."
                     "You are being given the following reference JSON schema. Later schemas may refer to $defs in this one:" (write-str fsm-schema) "."
                     "Requests will arrive as [INPUT-SCHEMA, DOCUMENT, OUTPUT-SCHEMA] triples."
                     "The INPUT-SCHEMA describes the structure of the DOCUMENT."
                     "You must respond to the contents of the DOCUMENT."
                     "Your response must be a single JSON document that is STRICTLY CONFORMANT (please pay particular attention to the \"id\" which must be present as a pair of strings) to the OUTPUT-SCHEMA:"]
                    fsm-prompts
                    ix-prompts
                    state-prompts
                    llm-system-prompts))}]

      ;; Add any LLM-specific user prompts
      (when (seq llm-user-prompts)
        [{"role" "user"
          "content" (join "\n" llm-user-prompts)}])

      ;; Add conversation trail
      (map (fn [m] (update m "content" write-str)) (reverse trail))))))

(defn llm-action
  "FSM action: call LLM with prompts built from FSM config and trail.
   Extracts provider/model from input data, defaults to openai/gpt-4o-mini."
  [context fsm ix state trail handler]
  (let [[{[_input-schema input-data _output-schema] "content"} & _tail] trail
        {provider "provider" model "model"} (get input-data "llm")
        provider (or provider "openai")
        model (or model "gpt-4o-mini")
        prompts (make-prompts fsm ix state trail provider model)]
    (println ">>> llm-action CALLED:" provider "/" model "prompts:" (count prompts))
    (flush)
    (open-router-async
     provider model
     prompts
     (fn [output]
       (println ">>> llm-action GOT OUTPUT:" (pr-str output))
       (flush)
       (handler context output)))))

;; a correlation id threaded through the data
;; a monitor or callback on each state
;; start and end states which are just labels
;; sub-fsms = like a sub-routine - can be embedded in a state or a transition - hmmm - schemas must match ?
