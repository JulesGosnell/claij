(ns m3.core
  (:require
   [cljs.pprint :as ppt]
   [cljs.core :as cljs]
   ["handlebars$default" :as handlebars]
   ["marked" :refer [marked]]
   [reagent.core :as reagent]
   [reagent.dom :as d]
   [re-frame.core :as rf]
   [day8.re-frame.tracing :refer-macros [fn-traced]] ;for event tracing
   [m3.log :as log]

   [m3.util     :refer [index-by-$id check-formats]]
   [m3.validate :refer [make-m3]]
   [m3.json     :refer [json-insert-in json-remove-in]]
   [m3.migrate  :refer [migrate]]
   [m3.render   :refer [render-1]]
   [m3.mui      :refer [my-app-bar view-forms]]
   [m3.page     :refer [->page]]
   
   [m3.demo     :refer [demo-m2 demo-m1]]
   [m3.divorce  :refer [divorce-workflow-m1 divorce-m2 divorce-m1]];;
   [m3.final-terms  :refer [final-terms-workflow-m1 final-terms-m2 final-terms-m1 final-terms-m0]]
   ))

;;------------------------------------------------------------------------------

(def m3 (make-m3 {:draft "latest"}))

(rf/reg-event-db
 :initialise
 (fn-traced [_ [_ db]]
            (log/info "INITIALISE")
            db))

(rf/reg-event-db
 :assoc-in
 (fn-traced [db [_ path v]]
            ;;(println "ASSOC-IN:" path ":" v)
            (assoc-in db path v)))

(rf/reg-event-db
 :update-in
 (fn-traced [db [_ path f & args]]
            (when (not (= path [:zip]))(println "UPDATE-IN:" path ":" f args))
            (apply update-in db path f args)))

;;------------------------------------------------------------------------------
;; N.B.
;; this looks ridiculously over-complicated - unfortunately:
;; - a text input on-change event does not carry the old value
;; - react seems to compress dom updates
;; - sometimes react seems to send an event which has no change in it !
;; this means that there is no easy way to know what the original key
;; of the field that we want to rename is, unless we store it each
;; time we do a rename... in "current-key.

;; we use onBlur in the same component to remove :current-key so it
;; does not confuse the next field to be renamed...

;; dispatch-sync does not fix the problem

;; A dom element's key is generated from the path of the json node
;; that it represents. When we edit the key of a json node this has
;; the side effect of changing the dom element's key. This causes the
;; input box into which we are typing to lose focus since it's
;; identity has changed. To avoid this we maintain a map of
;; {currrent-key original-key} so that as we build the dom we can
;; maintain dom element identity and thus continue to type into the
;; input element uninterrupted.

(defn do-rename-in-tidy [db [_]]
  (dissoc db :original-key :current-key))

(rf/reg-event-db :rename-in-tidy (fn-traced [& args] (apply do-rename-in-tidy args)))
<
(defn do-rename-in [{ok :original-key ck :current-key m2 :m2 m1 :m1 z :zip :as db} [_ path old-k new-k]]
  (let [old-k (or ck old-k)
        migration
        {"type" "rename"
         "m2-path" (vec (rest path))
         "m1-path" (vec (rest (butlast (z (conj path old-k)))))
         "source" old-k
         "target" new-k}
        m2-ctx {:draft "latest" :$ref-merger :merge-over}
        m1-ctx {:draft "latest" :$ref-merger :merge-over}
        [m2 [m1]] (migrate m1-ctx m2-ctx migration [m2 [m1]])]
    (assoc
     db
     :m2 m2
     :m1 m1
     :current-key new-k
     :original-key {[path new-k] (or (get ok [path old-k]) old-k)})))

(rf/reg-event-db :rename-in (fn-traced [& args] (apply do-rename-in args)))

;;------------------------------------------------------------------------------

(defn do-delete-in [{m2 :m2 m1 :m1 z :zip :as db} [_ path]]
  (let [migration
        {"type" "delete"
         "m2-path" (vec (rest path))
         "m1-path" (vec (rest (z path)))}
        m2-ctx {:draft "latest" :$ref-merger :merge-over}
        m1-ctx {:draft "latest" :$ref-merger :merge-over}
        [m2 [m1]] (migrate m2-ctx m1-ctx migration [m2 [m1]])]
    (assoc db :m2 m2 :m1 m1)))

(rf/reg-event-db :delete-in (fn-traced [& args] (apply do-delete-in args)))

;;------------------------------------------------------------------------------

(rf/reg-event-db
 :move
 (fn-traced [db [_ src tgt]]
            (println "MOVE:" (type src) src "->" tgt)
            (let [v (get-in db src)]
              (println "V:" v)
              (-> db
                  (json-remove-in src)
                  (json-insert-in (butlast tgt) (last tgt) [(last src) v])))))

;;------------------------------------------------------------------------------

(rf/reg-event-db
 :expand
 (fn-traced [db [_ path]]
            ;;(println "EXPAND:" path)
            (update-in db [:expanded] conj path)))

(rf/reg-event-db
 :collapse
 (fn-traced [db [_ path]]
            ;;(println "COLLAPSE:" path)
            (update-in db [:expanded] disj path)))

;;------------------------------------------------------------------------------

;; support deletion of values
;; allow addition/subtraction of properties and iteme
;; other stuff
;;------------------------------------------------------------------------------

(rf/reg-sub :m3 (fn [db _] (:m3 db)))
(rf/reg-sub :m2 (fn [db _] (:m2 db)))
(rf/reg-sub :m1 (fn [db _] (:m1 db)))
(rf/reg-sub :m0 (fn [db _] (:m0 db)))
(rf/reg-sub :expanded (fn [db _] (:expanded db)))
(rf/reg-sub :original-key (fn [db _] (:original-key db)))

(defn pretty [json]
  (let [sb (goog.string/StringBuffer.)]
    (binding [*out* (StringBufferWriter. sb)
              ppt/*print-right-margin* 30]
      (ppt/pprint json *out*))
    (str sb)))

;;------------------------------------------------------------------------------

(def workflow-m2
  {"$id" "Workflow"
   "$schema" "M3"
   "type" "object"
   "$defs"
   {"State"
    {"type" "object"
     "properties"
     {"$id" {"type" "string"}
      "title" {"type" "string"}}}}
   "properties"
    {"states"
     {"type" "array"
      "items" {"$ref" "#/$defs/State"}}}})

(def workflow-m1
  (array-map
   ;; "$id" "workflow"
   ;; "$schema" "Workflow"
   "states" []))

;;------------------------------------------------------------------------------
;; we need a state and a schema for that state, so that the basic form
;; library can render it if no more specific renderer is found.

;; a schema for a state contains a snippet of metaschema seeing as a
;; state contains a schema - so we need to be able to refer to
;; external schemas - yikes.

;; (def schemas (index-by-$id [m3]))

;; (def state-m2-2
;;   {"$id" "/schemas/state-schema"
;;    "$schema" "/schemas/metaschema"
;;    "type" "object"
;;    "properties"
;;    {"views"
;;     {"title" "Assets & Liabilities"
;;      "type" "array"
;;      "items" {"$ref" "/schemas/metaschema#/$defs/schemaM3"}}}})

;; (validate {:draft "latest" :$ref-merger :merge-over} m3 state-m2-2)

;; TODO:
;; use common state model for all components
;; current state should be wholly m2 ? - not sure
;; page should be wider - tab panel should scroll
;; solve annoying warning re " ReactDOM.render is no longer supported in React 18. Use createRoot instead."

;; each of these should be of type e.g. AssetArray which should have a total at the bottom....

;;------------------------------------------------------------------------------


(defn clj->json [clj]
  (when clj (.stringify js/JSON (clj->js clj) nil 2)))

(defn json->clj [json]
  (when (not (empty? json)) (js->clj (.parse js/JSON json))))

;;------------------------------------------------------------------------------
;; handlebars helpers - switch - by ChatGPT - for javascript

;; Handlebars.registerHelper('switch', function(value, options) {
;;   this._switch_value_ = value;
;;   this._switch_break_ = false;
;;   const html = options.fn(this);
;;   delete this._switch_value_;
;;   delete this._switch_break_;
;;   return html;
;; });

;; Handlebars.registerHelper('case', function(value, options) {
;;   if (value === this._switch_value_ && !this._switch_break_) {
;;     this._switch_break_ = true;
;;     return options.fn(this);
;;   }
;;   return '';
;; });

;; translated into clojurescript:

(defn switch-helper [value options]
  (this-as
   this
   (try
     (set! ^js/string (.-_switch_value_ this) value)
     (set! ^boolean (.-_switch_break_ this) false)
     ((.-fn options) this)
     (finally
       (js-delete this "_switch_value_")
       (js-delete this "_switch_break_")))))

(defn case-helper [value options]
  (this-as
   this
   (if (and
        (= ^js/string (.-_switch_value_ this) value)
        (not ^boolean (.-_switch_break_ this)))
     (do
       (set! ^boolean (.-_switch_break_ this) true)
       ((.-fn options) this))
     "")))

(.registerHelper handlebars "switch" switch-helper)
(.registerHelper handlebars "case" case-helper)

(defn eq-helper [arg1 arg2 options]
  (if (= arg1 arg2)
    (.-fn options)
    (.-inverse options)))

(.registerHelper handlebars "eq" eq-helper)

;; this is what they would look like for handlebars.java - can we come
;; up with a portable way of defining helpers ?



;;------------------------------------------------------------------------------

(defn html-string [html]
  [:div {:dangerouslySetInnerHTML {:__html html}}])

;;------------------------------------------------------------------------------

(defn home-page []
  (let [m3s (rf/subscribe [:m3])
        m2s (rf/subscribe [:m2])
        m1s (rf/subscribe [:m1])
        m0s (rf/subscribe [:m0])
        expanded (rf/subscribe [:expanded])
        original-key (rf/subscribe [:original-key])]
    [:div
     ;;[:header [my-app-bar]]
     ;; [:hr]
     ;; [:hr]
     [:main
      [:table {:align "center"}
       [:tbody

        ;; [:tr
        ;;  [:td {:align "center"}
        ;;   [:div {:class "application-container"}
        ;;    [view-forms
        ;;     (let [page (->page [@m2s @m1s] divorce-workflow-m1
        ;;             "personal-information"
        ;;             ;;"assets-and-liabilities"
        ;;             )]
        ;;       (println "PAGE->:" page)
        ;;       page)]]]]

        ;; [:tr [:td [:hr]]]
        ;; [:tr [:td [:hr]]]

        [:tr
         [:td
          [:table
       [:caption
        ;; [:h2 "Welcome to M3"]
        ;; "Anything you can do, we can do meta..."
        ;; [:p]
        ]
           [:thead
            [:tr
             [:th "M3 Editor"]
             [:th "M3 JSON"]
             [:th "M2 Editor"]
             [:th "M2 JSON"]
             [:th "M1 Editor"]
             [:th "M1 JSON"]
             [:th "M0 Template"]
             [:th "M0 Document"]]]
           [:tbody
            [:tr
             [:td {:valign :top} [:table [:tbody [:tr [:td ((render-1 {:draft "latest" :root @m3s :$ref-merger :merge-over :expanded @expanded :original-key @original-key} [:m3] nil @m3s) {:draft "latest" :root @m3s :$ref-merger :merge-over} [:m2] nil @m3s)]]]]]
             [:td {:valign :top} [:textarea {:rows 180 :cols 50 :read-only true :value (pretty @m3s)}]]
             [:td {:valign :top} [:table [:tbody [:tr [:td ((render-1 {:draft "latest" :root @m3s :$ref-merger :merge-over :expanded @expanded :original-key @original-key} [:m3] nil @m3s) {:draft "latest" :root @m2s :$ref-merger :merge-over} [:m2] nil @m2s)]]]]]
             [:td {:valign :top} [:textarea {:rows 180 :cols 50 :read-only false :value (clj->json @m2s) :on-change (fn [event] (rf/dispatch [:assoc-in [:m2] (json->clj (.-value (.-target event)))]))}]]
             [:td {:valign :top} [:table [:tbody [:tr [:td ((render-1 {:draft "latest" :root @m2s :$ref-merger :merge-over :check-format check-formats :expanded @expanded} [:m2] nil @m2s) {:draft "latest" :root @m1s :$ref-merger :merge-over} [:m1] nil @m1s)]]]]]
             [:td {:valign :top} [:textarea {:rows 180 :cols 50 :read-only false :value (clj->json @m1s) :on-change (fn [event] (rf/dispatch [:assoc-in [:m1] (json->clj (.-value (.-target event)))]))}]]
             [:td {:valign :top} [:textarea {:rows 180 :cols 50 :read-only false :value @m0s :on-change (fn [event] (rf/dispatch [:assoc-in [:m0] (.-value (.-target event))]))}]]
             [:td {:valign :top} [html-string  (let [m1 @m1s m0 @m0s] (when (and m1 m0) (.parse marked ((.compile handlebars m0) (clj->js m1)))))]]]]]]]]]]]))

;; -------------------------
;; Initialize app

(defn mount-root []
  (let [container (.getElementById js/document "app")]
    (d/render [home-page] container)))

(defn ^:export init! []
  (rf/dispatch [:initialise {:m3 m3 :m2s (index-by-$id [final-terms-m2 workflow-m2]) :m2-id "Model" :m2 final-terms-m2 :m1s  (index-by-$id final-terms-m1 workflow-m1) :m1-id "model" :m1 final-terms-m1 :m0 final-terms-m0 :expanded #{}}])
  (mount-root))

;; shadow-cljs auto-reload api
(defn ^:dev/after-load re-render []
  (init!))

;;------------------------------------------------------------------------------

;; going to have to figure out how to deal with $refs without making them dissappear.... - maybe ?
;; order patterProperties before additionalProperties in m3/m2 view
;; object-m3 looks incomplete - patternProperties etc
;; keep expanded in sync with other mutative operations
;; collapse all children ?
;; introduce concept of migration
;;  - destructive: major version
;;  - additive: minor version
;; popups ?
;; adding pattern and additional properties
;; array reordering
;; object reordering
;; definitions
;; test against theremin schemas
;; $schema and $id should only appear in top node
;; drag-n-drop
;; extract look-n-feel
;; expand/collapse
;; an m3 that does not support more esoteric fn-ality - prefixItems, patternProperties, additionalProperties etc
;; allow dragging and dropping on m1 editor
;; - to change m1
;; - to change m2
;;  - eventually we may not need m2 view...
;; drag-n-drop prefixIte,s ad items, patternProperties, additionalProperties

;; on drag-n-drop, validate where you have come from and where you are going to
;; tighten up schema around definitions and make it optionally $defs
;; as we type in a reference it can resolve to wrong thing - references should be selected from a type ahead component

;; as you type in a $ref, you go through #/definitions which causes an error
;; deleting stuff from m2 does not delete it from m1
;; fix up drag and drop stuff
;; if you change the name of a definition, all refs to it should change too
;; if you drag something into definitions, it should leave a ref behind
;; if you drag something out of definitions it should refuse to come unless you drop it over the only ref to itself
;; rename-in does not handle objects inside arrays
;; we need a way to generate enums on the fly by looking at other part of the document - and if those change, refactoring values in enums and where they are used in document - same mechanism should handle use of refs
;; validation doesn't always appear to be being done
;; we need the m3 alongside so we can add new formats ?
;; if we want to refer to a bit of m3 in an m2 - how do we do it ? ah - we refer to it as an external schema fragment :-)
;; we will need undo fn-ality - and audit trail ?

;; you feed a migration an m2 and instructions like cut/paste with locations - it returns a modified m2 and a function that will do the necessary on any m1
;; in this way you can modify an m3 (e.g. make "title" a multi-language string) then cascade this change down to all m2s and then to all the m1s - is this necessary or too clever ?
;; the UI needs a list of m2s and m1s (for each m2) so that you can choose which one you are editing - cool - but now we need a backend - how will that work ?
;; you are not allowed to perform migrations on an m2 that might lead to an m1 becoming invalid (e.g. suddenly satisfying more than one oneOf)
;; a MIGRATION and a MAPPING are the same thing ! ooh - so do migrations need to be reversible ? ouch - are mappings a subset of migrations - language could be the same
;; have a good look at xslt - can we do better ?
;; reference data is injected as enums at doc generation and validation time


;; we need to lift our pltform out of the mire of development/deploymeny cycles - particulrly deployment which is v.expensive - business changes should not require deployment or restart. We should also be able to roll back a business change securely and painlessly
