(ns claij.fsm
  (:require
   [clojure.tools.logging :as log]
   [clojure.core.async :refer [chan go-loop alts! >!! close!]]
   [m3.uri :refer [parse-uri uri-base]]
   [m3.validate :refer [validate make-context uri->continuation uri-base->dir]]
   [claij.util :refer [def-m2 def-m1 index-by ->key map-values]]))

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

      "description"
      {"type" "string"}

      "prompts"
      {"$ref" "#/$defs/prompts"}

      "schema" {"$ref" "#/$defs/schema-ref"}}
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

(defn state-schema
  "make the schema for a state - to be valid for a state, you must be valid for one (only) of its output xitions"
  [{fid "id" fv "version" fs "schema" :as _fsm} {sid "id" :as _state} xs]
  ;; {"$schema" meta-schema-uri
  ;;  "$$id" (format "%s/%s/%d/%s" schema-base-uri fid (or fv 0) sid)
  ;;  "oneOf"
  ;;  (mapv
  ;;   (fn [{xid "id" s "schema" :as _x}]
  ;;     {"properties" (expand-schema xid s)})
  ;;   xs)}

  ;; (-> fs
  ;;     (dissoc "$$id")
  ;;     (dissoc "$version")
  ;;     (assoc "$id" (fs "$$id")))
  {"oneOf" (mapv (->key "schema") xs)})

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
    (log/info (str "-> Transition [" from " -> " to "]"))

    (let [s-schema (state-schema fsm state (map first ox-and-cs))
          id->x-and-c (index-by (comp (->key "id") first) ox-and-cs)
          handler (fn [raw-output]
                    ;; Extract the actual data map from the LLM response
                    ;; LLM may return: [{"$ref" "..."} {"id" [...] ...} {"$ref" "..."}]
                    ;; We need the map with the "id" key
                    (let [output (if (sequential? raw-output)
                                   (first (filter #(and (map? %) (contains? % "id")) raw-output))
                                   raw-output)
                          {ox-id "id"} output]
                      (try
                        (let [[{ox-schema "schema" :as ox} c] (id->x-and-c ox-id)
                              _ (when (nil? ox-schema) (log/error "Could not find schema for transition:" ox-id))
                              ;; Create validation schema that includes $defs from fsm-schema
                              ;; so that $ref fragments can be resolved
                              validation-schema (assoc (select-keys fsm-schema ["$defs" "$schema" "$$id"])
                                                       "allOf" [ox-schema])
                              {v? :valid? es :errors} (validate {:draft schema-draft
                                                                 :uri->schema (partial uri->schema {(uri-base (parse-uri fsm-schema-id)) fsm-schema})}
                                                                validation-schema
                                                                {}
                                                                output)]
                          (if v?
                            (do
                              (log/info (str "   [OK] Output validated for transition " ox-id))
                              (>!! c (cons {"role" "assistant" "content" [ox-schema output nil]}
                                           (cons {"role" r "content" [is input ox-schema]} trail)))
                              nil)
                            (do
                              (log/error (str "   [FAIL] Validation failed for transition " ox-id ": " (pr-str es)))
                              es)))
                        (catch Throwable t
                          (log/error t "Error in handler processing")))))]
      (if-let [action (get-in context [:id->action a])]
        (do
          (log/info (str "   Action: " a))
          (action context fsm ix state (cons {"role" r "content" [is input s-schema]} trail) handler))
        (handler (second (first trail)))))
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
   
   NOTE: Currently assumes exactly one transition from 'start'. See TODO above."
  [context {ss "states" xs "xitions" :as fsm}]
  (let [;; Create channels and mappings
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
        output-schema (state-schema fsm start-state-obj output-xitions)

        ;; Create interface functions
        submit (fn [document]
                 (let [input-data {"id" entry-xition-id
                                   "document" document}
                       message [{"role" "user"
                                 "content" [entry-schema input-data output-schema]}]]
                   (>!! entry-channel message)
                   (log/info (str "   Submitted document to FSM: " start-state))))
        stop-fsm (fn []
                   (log/info (str "\n>> Stopping FSM: " (or (get fsm "id") "unnamed")))
                   (doseq [c all-channels]
                     (close! c)))]

    (log/info (str "\n>> Starting FSM: " (or (get fsm "id") "unnamed")
                   " (" (count ss) " states, " (count xs) " transitions)"))

    ;; Start all the state loops
    (doseq [{id "id" :as s} ss]
      (let [ix-and-cs (sid->ix-and-cs id)
            ox-and-cs (sid->ox-and-cs id)
            ic->ix (into {} (mapv (fn [[x c]] [c x]) ix-and-cs))
            cs (keys ic->ix)]
        (go-loop []
          (let [[v ic] (alts! cs)]
            (when v
              (let [ix (ic->ix ic)]
                (xform context fsm ix s ox-and-cs v)
                (recur)))))))

    ;; Return interface
    [submit stop-fsm]))

;; a correlation id threaded through the data
;; a monitor or callback on each state
;; start and end states which are just labels
;; sub-fsms = like a sub-routine - can be embedded in a state or a transition - hmmm - schemas must match ?
