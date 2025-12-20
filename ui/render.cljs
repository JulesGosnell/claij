(ns m3.render
  (:require
   [cljs.reader :as rdr]
   [goog.string :refer [format]]
   [re-frame.core :as rf]
   [m3.log :as log]
   [m3.util :refer [valid? conjv make-id vector-remove-nth]]
   [m3.json :refer [absent present? absent?]]
   [m3.validate :as json]
   ))

;;------------------------------------------------------------------------------

(defn log [& args]
  ;;(apply println args)
  )

;;------------------------------------------------------------------------------

;; use array-maps to retain order - treat objects like arrays - much simpler
(defn map-remove-nth [m n]
  (apply array-map (flatten (vector-remove-nth m n))))

(defn map-rename-nth [m n k]
  (apply array-map (flatten (assoc-in (vec m) [n 0] k))))

(defn conjm [m [k v]]
  (apply array-map (concat (flatten (seq m)) [k v])))

;;------------------------------------------------------------------------------

(defn drop-down [_context path es e]
  ;; (println "DROP-DOWN:" path e es)
  (if (> (count es) 1000)

    ;; type-ahead - not working well... - prob needs custom script
    (let [id (make-id (conj path "data"))]
      [:div
       [:datalist {:id id}
        (map
         (fn [n [k v]]
           [:option {:key (make-id (conjv path n)) :value (if (string? v) v (pr-str v)) :label k}])
         (range) es)]
       [:input
        {:list id
         :autoComplete "off"
         :value (if (and (present? e) e) (pr-str e) "")
         :on-change (fn [e] (let [v (rdr/read-string (.-value (.-target e)))] (if (empty? v) (rf/dispatch [:delete-in path]) (rf/dispatch [:assoc-in path v]))))}]])
    
    [:select
     {:value (if (and (present? e) e) (pr-str e) "")
      :on-change (fn [e] (let [v (rdr/read-string (.-value (.-target e)))] (println "DROP-DOWN:" path v) (rf/dispatch (if v [:assoc-in path v] [:delete-in path]))))}
     (map
      (fn [n [k v]]
        [:option {:key (make-id (conjv path n)) :value (pr-str v)} k])
      (range) (concat [["" nil]] es))]))

(defn squidgy-button [expanded path]
  (if (expanded path)
    [:td [:button {:on-click (fn [e] (rf/dispatch [:collapse path]))} "v"]]
    [:td [:button {:on-click (fn [e] (rf/dispatch [:expand path]))} "^"]]))

(defn squidgy? [{oo "oneOf" t "type" :as m2} m1]
  ;;(println "M2:" m2 ", M1:" m1)
  (cond
    oo true
    (= t "object") true
    :else false)

  false)

;;------------------------------------------------------------------------------

(defn render-key [{ui :ui :or {ui ::html5}} {oo "oneOf" t "type" f "format" :or {f :default}}]
  [ui (cond oo "oneOf" :else t) f])

(defmulti render-2 (fn [c2 p2 k2 m2] (render-key c2 m2)))

(def render (memoize render-2))

(defn render-1 [c2 p2 k2 m2]
  (let [r (render c2 p2 k2 (json/expand-$ref c2 p2 m2))]
    (fn [c1 p1 k1 m1]
      (r c1 p1 k1 (json/expand-$ref c2 p1 m1)))))

(defmethod render-2 :default [c2 p2 k2 m2]
  (let [rk (render-key c2 m2)]
    (fn [c1 p1 k1 m1]
      (when (or m2 m1)
        (log/warn "render: no specific method:" rk m2 m1)))))

(defmethod render-2 [::html5 "null" :default] [c2 p2 k2 {title "title" des "description" :as m2}]
  (let [v? (valid? c2 m2)]
    (fn [c1 p1 k1 m1]
      (log "NULL:" p1 k1 m1)
      [:div {:style {:background "#cc99ff"} :class (v? c1 m1)}
       [:button {:on-click (fn [e] (rf/dispatch [:assoc-in p1 nil]))} "+"]])))

(defmethod render-2 [::html5 "boolean" :default]
  [c2
   p2 k2
   {title "title" des "description" d "default" c "const" :as m2}]
  (let [v? (valid? c2 m2)
        ro (boolean c)]
    (fn [c1 p1 k1 m1]
      ;;(println "BOOLEAN:" context p2 k2 m2 p1 k1 m1)
      [:div {:style {:background "#ffcc66"} :class (v? c1 m1)}
       [:input {:type "checkbox" :read-only ro
                ;;:placeholder d
                :checked (and (present? m1) m1) ;; TODO what about const - implicit nils etc
                :on-change (fn [e] (rf/dispatch [:assoc-in p1 (.-checked (.-target e))]))}]])))

(defn render-number-2 [c2 p2 k2
                       {title "title" des "description" es "enum" d "default" c "const" min "minimum" max "maximum" mo "multipleOf" :as m2} f]
  (let [v? (valid? c2 m2)
        ro (boolean c)
        ddes (map (juxt identity identity) es)]
    (fn [c1 p1 k1 m1]
      (log "NUMBER:" p1 k1 m1)
      [:div {:style {:background "#99ff99"} :class (v? c1 m1)}
       (if (seq es)
         (drop-down c2 p1 ddes (when (present? m1) m1))
         [:input {:type f  :max max :min min :step mo :placeholder d :read-only ro :value (or c (when (present? m1) m1))
                  :on-change (fn [e]
                               (let [v (.-value (.-target e))]
                                 (if (empty? v)
                                   (rf/dispatch [:delete-in p1])
                                   (rf/dispatch [:assoc-in p1 (js/parseFloat v 10)]))))}])])))

(def render-number (memoize render-number-2))

(defmethod render-2 [::html5 "number" :default] [c2 p2 k2 m2]
  (render-number c2 p2 k2 m2 "number"))

(defmethod render-2 [::html5 "number" "range"] [c2 p2 k2 m2]
  (render-number c2 p2 k2 m2 "range"))

(defn render-integer-2 [c2 p2 k2
                        {title "title" des "description" es "enum" d "default" c "const" min "minimum" max "maximum" mo "multipleOf" :as m2} f]
  (let [v? (valid? c2 m2)
        ro (boolean c)
        ddes (map (juxt identity identity) es)]
    (fn [c1 p1 k1 m1]
      (log "INTEGER:" p1 k1 m1)
      [:div {:style {:background "#ccff33"} :class (v? c1 m1)}
       (if (seq es)
         (drop-down c2 p1 ddes (when (present? m1) m1))
         [:input {:type f :max max :min min :step mo :placeholder d :read-only ro :value (or c (when (present? m1) m1))
                  :on-change (fn [e]
                               (let [v (.-value (.-target e))]
                                 (if (empty? v)
                                   (rf/dispatch [:delete-in p1])
                                   (rf/dispatch [:assoc-in p1 (js/parseInt v 10)]))))}])])))

(def render-integer (memoize render-integer-2))

(defmethod render-2 [::html5 "integer" :default] [c2 p2 k2 m2]
  (render-integer c2 p2 k2 m2 "number"))

(defmethod render-2 [::html5 "integer" "range"] [c2 p2 k2 m2]
  (render-integer c2 p2 k2 m2 "range"))

(defn render-string-2 [c2 p2 k2
                       {title "title" des "description" es "enum" d "default" c "const" minL "minLength" maxL "maxLength" ro "readOnly" :as m2} f]
  (let [v? (valid? c2 m2)
        readOnly (or (boolean c) ro)
        ddes (map (juxt identity identity) es)]
    (fn [c1 p1 k1 m1]
      (log "STRING:" p1 k1 m1)
      [:div {:style {:background "#ffff99"} :class (v? c1 m1)}
       (if (seq es)
         ;; enum string
         (drop-down c2 p1 ddes (when (present? m1) m1))
         ;; simple string
         [:input {:type f :placeholder d :value (when (present? m1) m1) :readOnly readOnly :minLength minL :maxLength maxL :size maxL
                  :on-change (fn [e] (rf/dispatch [:assoc-in p1 (.-value (.-target e))]))}])])))

(def render-string (memoize render-string-2))

(defmethod render-2 [::html5 "string" :default] [c2 p2 k2 m2]
  (render-string c2 p2 k2 m2 "text"))

(defmethod render-2 [::html5 "string" "time"] [c2 p2 k2 m2]
  (render-string c2 p2 k2 m2 "time"))

(defmethod render-2 [::html5 "string" "date"] [c2 p2 k2 m2]
  (render-string c2 p2 k2 m2 "date"))

(defmethod render-2 [::html5 "string" "date-time"] [c2 p2 k2 m2]
  (render-string c2 p2 k2 m2 "datetime-local"))

(defmethod render-2 [::html5 "string" "week"] [c2 p2 k2 m2]
  (render-string c2 p2 k2 m2 "week"))

(defmethod render-2 [::html5 "string" "year-month"] [c2 p2 k2 m2]
  (render-string c2 p2 k2 m2 "month"))

(defmethod render-2 [::html5 "string" "uri-reference"] [c2 p2 k2 m2]
  (render-string c2 p2 k2 m2 "text"))

(defmethod render-2 [::html5 "string" "regex"] [c2 p2 k2 m2]
  (render-string c2 p2 k2 m2 "text"))

(defmethod render-2 [::html5 "string" "bank-sort-code"] [c2 p2 k2 m2]
  (render-string c2 p2 k2 m2 "text"))

(defmethod render-2 [::html5 "string" "bank-account-number"] [c2 p2 k2 m2]
  (render-string c2 p2 k2 m2 "text"))

(defmethod render-2 [::html5 "string" "telephone-number"] [c2 p2 k2 m2]
  (render-string c2 p2 k2 m2 "text"))

(defmethod render-2 [::html5 "integer" "money"] [c2 p2 k2 m2]
  (render-string c2 p2 k2 m2 "number"))

(defmethod render-2 [::html5 "number" "money"] [c2 p2 k2 m2]
  (render-string c2 p2 k2 m2 "number"))

;; TODO: fix types menu and then get AnyOf working etc...
(defn get-m1 [c2 p2 m2]
  ;;(println "GET-M1 <-" (pr-str m2))
  (let [{m1t "type" {{m2t "const"} "type" one-of-m3 "oneOf" any-of-m3 "anyOf" all-of-m3 "allOf"} "properties" one-of-m2 "oneOf" any-of-m2 "anyOf" all-of-m2 "allOf" :as m2} (json/expand-$ref c2 p2 m2)
        result
        (cond
          m2t {"type" m2t}
          m1t ({"boolean" true "string" "" "integer" 0 "number" 0.0 "null" nil} m1t) ;TODO: complete
          (= m1t "boolean") true
          one-of-m3 {"oneOf" []}
          any-of-m3 {"anyOf" []}
          all-of-m3 {"allOf" []}
          one-of-m2 true ;; TODO: try each of the legs in get-m1 until we get a useful answer...
          any-of-m2 true
          all-of-m2 true
          :else
          (println "get-m1: can't resolve m2:" (pr-str m2) "to an m1")
          )]
    ;;(println "GET-M1 -> " (pr-str result))
    result))

;; TRY TO GET ADDRESS DEFINITION TO EXPAND

(defmethod render-2 [::html5 "object" :default]
  [{expanded? :expanded ok :original-key :as c2}
   p2 k2
   {ps "properties" pps "patternProperties" aps "additionalProperties" title "title" es "enum" :as m2}]
  (let [v? (valid? c2 m2)]
    (fn [c1 p1 k1 m1]
      (log "OBJECT:" "M2:" [p2 k2 m2] "M1:" [p1 k1 m1])
      [:div {:style {:background "#99ccff"} :class (v? c1 m1)}
       (if (seq es)
         (drop-down c2 p1 (map (juxt (fn [{t "title"}] t) identity) es) (when (present? m1) m1))
         (let [extra-ps-m1 (apply dissoc (when (present? m1) m1) (keys ps)) ;pps + aps
               pattern-ps-m2s-and-m1 (filter
                                      (fn [[m2s]] (seq m2s))
                                      (map
                                       (juxt (fn [[epk]] (reduce (fn [acc [ppk :as pp]] (if (re-find (re-pattern ppk) epk) (conj acc pp) acc)) [] pps)) identity)
                                       extra-ps-m1))
               pattern-ps-m1 (map second pattern-ps-m2s-and-m1)
               additional-ps (apply dissoc extra-ps-m1 (keys pattern-ps-m1))]
           [:table {:border 1}
            (when title [:caption [:h4 title]])
            [:tbody
             (when ps
               [:tr {:onDragOver (fn [e] (.preventDefault e))
                     :onDrop (fn [e]
                               (.preventDefault e)
                               (rf/dispatch [:move (rdr/read-string (.getData (.-dataTransfer e) "m1")) p1]))}
                [:td
                 [:table {:border 1}
                  [:caption "Named Properties"]
                  [:tbody
                   (doall
                    (map
                     (fn [[k {t "title" d "description" :as m2}]]
                       (let [p2 (vec (concat p2 ["properties" k]))
                             p1 (conjv p1 k)
                             id (make-id p1)
                             squidgable? (squidgy? m2 m1)
                             visible? (or (and squidgable? (expanded? p2)) (not squidgable?))]
                         (rf/dispatch [:update-in [:zip] (fnil assoc {}) p2 p1])
                         [:tr {:key id :id id :title d
                               :draggable true
                               :onDragStart (fn [e] (.setData (.-dataTransfer e) "m1" p1))
                               :onDragOver (fn [e] (.preventDefault e))
                               :onDrop (fn [e]
                                         (.preventDefault e)
                                         (.stopPropagation e)
                                         (rf/dispatch [:move (rdr/read-string (.getData (.-dataTransfer e) "m1")) p1]))}
                          [:td
                           [:table {:border 1}
                            (when t [:caption [:h4 t]])
                            [:tbody
                             [:tr
                              [:td [:input {:type "text" :value k :read-only true}]]
                              (when visible? [:td ((render-1 c2 p2 k m2) c1 p1 k (get (when (present? m1) m1) k absent))])
                              (when squidgable? (squidgy-button expanded? p2))
                              [:td [:button {:on-click (fn [e] (rf/dispatch [:delete-in p1]))} "-"]]]]]]]))
                     ps))]]]])
             (when pps
               ;;(prn "PATTERN PROPERTIES:" pps)
               ;; Pattern Properties
               ;; - show a table of (n) pattern (1) -> (n) property-key (1) -> (n) property-value
               ;; - a property may match multiple patterns and thus need to conform to multiple schemas
               ;; - so adding a property under one pattern may cause it to appear under others....
               ;; TODO:
               ;; - properties should be foldable
               ;; - properties should start folded/furled
               ;; - we need a (with-furling ...) function...
               [:tr
                [:td
                 [:table {:border 1}
                  [:caption "Pattern Properties"]
                  [:tbody
                   (concat
                    (map
                     (fn [[pattern {t "title" d "description" :as schema}]]
                       (let [p2 (vec (concat p2 ["patternProperties" pattern]))
                             id2 (make-id p2)
                             r (render-1 c2 p2 pattern schema)]
                         [:tr {:key id2 :id  id2 :title pattern}
                          [:td [:input {:type "text" :value pattern :read-only true}]]
                          [:td
                           [:table  {:border 1}
                            [:tbody
                             (map
                              (fn [[k v]]
                                (let [p2 (conj p2 k)
                                      p1 (conj p1 k)
                                      id1 (make-id p1)]
                                  [:tr {:key id1 :id  id1 :title (or t d)}
                                   [:td [:input {:type "text" :value k :pattern pattern :read-only true}]]
                                   (when (expanded? p2) [:td (r c1 p1 k v)])
                                   (squidgy-button expanded? p2)]))
                              (filter
                               (fn [[k]] (re-find (re-pattern pattern) k))
                               (when (present? m1) m1)))]]]]))
                     pps)
                    [[:tr {:key (make-id (conjv p2 "plus")) :align :center}
                      [:td [:button
                            ;;{:on-click (fn [e] (rf/dispatch [:assoc-in p2 ""]))}
                            "+"]]
                      [:td [:button
                           ;;{:on-click (fn [e] (rf/dispatch [:update-in p1 conjv (or def (get-m1 c2 is))]))}
                            "+"]]]])]]]])

             (when aps
               [:tr {:onDragOver (fn [e] (.preventDefault e))
                     :onDrop (fn [e]
                               (.preventDefault e)
                               (rf/dispatch [:move (rdr/read-string (.getData (.-dataTransfer e) "m2")) p1]))}
                [:td
                 [:table {:border 1}
                  [:caption "Additional Properties"]
                  [:tbody
                   (doall
                    (concat
                     (map
                      (fn [n [k v]]
                        (let [p2 (conj p2 "additionalProperties")
                              old-p1 p1
                              p1 (conjv p1 k)
                              ok2 (get ok [old-p1 k])
                              id (make-id (if ok2 (conjv old-p1 ok2) p1))]
                          [:tr {:key id}
                           [:td
                            [:table {:border 1}
                             [:tbody
                              [:tr {:draggable (= "properties" k2)
                                    :onDragStart (fn [e] (.setData (.-dataTransfer e) "m2" p1))
                                    :onDragOver (fn [e] (.preventDefault e))
                                    :onDrop (fn [e]
                                              (.preventDefault e)
                                              (.stopPropagation e)
                                              (rf/dispatch [:move (rdr/read-string (.getData (.-dataTransfer e) "m2")) (conj (vec (butlast p1)) n)]))}
                               ;; TODO: store m1 path so it can be used to zip this and next traversal together...
                               ;;(rf/dispatch [:update-in [:zip] (fnil update {}) p1 (fnil conj []) (conj p2 k)])
                               (rf/dispatch [:update-in [:zip] (fnil assoc {}) (conj p2 k) p1])
                               ;; TODO: only renames one-level
                               [:td [:input {:type "text" :value k :read-only false
                                             :on-change (fn [e] (let [siblings (disj (set (keys m1)) k) v (.-value (.-target e))] (if (siblings v) (log/warn "cannot name property same as sibling: " p1 v) (rf/dispatch [:rename-in old-p1 k v]))))
                                             :onBlur (fn [e] (rf/dispatch [:rename-in-tidy]))}]] ;TODO: something wrong with this
                               (when (expanded? (conj p2 k)) [:td ((render-1 c2 p2 k (when (map? aps) aps)) c1 p1 k v)])
                               (squidgy-button expanded? (conj p2 k))
                               ;; TODO: only deletes m2 - TODO - needs fixing
                               [:td [:button {:on-click (fn [e] (rf/dispatch [:delete-in p1]))} "-"]]]]]]]))
                      (range)
                      additional-ps)           ;TODO: these are ordered wrong
                     [[:tr {:key (make-id (conjv p1 "plus")) :align :center}
                       [:td
                        [:button

                         ;; we should render a form, guided by the m2 but only allow submission of content when valid - once submitted it will appear above and should only be edited there...

                         ;; if aps = false we will not show this button
                         ;; if aps is a schema, we will pop up a dialog form (from schema) and capture a key and a value to poke into m1
                         ;; if aps is true, we will use a prepared oneOf to pop up same form
                         ;; what happens if the schema we popup has additional properties itself ? does this make sense ?
                         ;; we can't add a pair to m1 and then produce form for it becaause we don't know what type to use - aps might be a oneOf
                         ;; maybe we only use a popup for a non-oneOf
                         ;; the value might itself be an object...
                         ;; is all this worth worrying about ?
                         {:on-click (fn [e] (println "CLICK:") (rf/dispatch [:update-in p1 conjm [(str "property-" (count (when (present? m1) m1))) (get-m1 c2 p2 aps)]]))}
                         "+"]]]]))]]]])]]))])))

;; N.B.

;; lists and objects are very similar can we reuse same UI code ?
;; represent an object as an array of tuple-2
;; let's provide item numbers instead of keys for arrays
;; array keys should be read-only
;; sometime (when) prefixItems (i,e. tuple) should be arranged horizontally not vertically
;; we should provide some indication of why we chose a particular schema fr a particular row (debug mode)

;; array            | object
;;--------------------------------
;; no keys          | keys
;; prefixItems      | named Properties
;; items            | patternProperties - is this right ?
;; minItems         | minProperties
;; maxItems         | maxProperties
;; additionalItems  | additionalProperties
;; unevaluatedItems | unevaluatedProperties

;; uniqueItems      | 
;;                  | required
;; contains
;; minContains
;; maxContains
;;                  | propertyNames
;;                  | dependencies
;;                  | dependentSchemas
;;                  | dependentRequired
;;                  | propertyDependencies

;; TODO
;;


;; propertyNames
;;  - we could use this instead of patternProperties - which one would be best ?
;; prefixItems can not be missing from an array - awkward


;; have removed :draggable (non-prefixItems) - add back later - we should be able to reorder items and additionalItems - but maybe not interchangeably
;; lets start with assumption that min and max array sizes are handled here
;; min/maxItems include prefixItems
(defn render-array [minIs maxIs read-only? v? parent-path m1 rows]
  [:div {:style {:background "#ffcccc"} :class v?}
   [:table
    [:tbody
     (doall
      (concat
       (map
        (fn [[path k fixed? delete-f td]]
          [:tr {:key (make-id path)}
           [:td td]
           [:td (when (and (not read-only?) fixed?) [:button  {:on-click (fn [_e] (rf/dispatch [:update-in parent-path delete-f k]))} "-"])]])
        rows)
       (when (and (not read-only?) (or (not maxIs) (< (count (when (present? m1) m1)) maxIs)))
         [[:tr {:key (make-id (conjv parent-path "plus")) :align :center}
           [:td [:button  {:on-click (fn [_e]
                                           ;; TODO: this needs reworking...
                                           ;; (rf/dispatch [:update-in parent-path conjv (or def (get-m1 c2 is))])
                                       )}"+"]]]])))]]])

;; how do min and maxItems interact with all different sorts of items ?
;; we need to consider :
;; - prefixItems 
;; - items
;; - additionalItems
;; - [unevaluateditems]

;; additionalItems is only used when items is an array - some

(defmethod render-2 [::html5 "array" :default]
  [c2 p2 k2 {{def "default" :as is} "items" pis "prefixItems" minIs "minItems" maxIs "maxItems" ro "readOnly" :as m2}]
  ;; N.B. min/maxItems should be passed through to render-array and not used here...
  (let [v? (valid? c2 m2)]
    (fn [c1 p1 k1 m1]
      (let [rows
            (map
             (fn [n [prefix? m2] m1]
               (let [[p2-suffix k2] (if prefix? [["prefixItems" n] n] [["items"] "items"])
                     p2 (vec (concat p2 p2-suffix))
                     p1 (conjv p1 n)
                     td ((render-1 c2 p2 k2 m2) c1 p1 n m1)]
                 [p1 n (not prefix?) vector-remove-nth td]))
             (range)
            ;; TODO: this is getting gnarly - simplify or break out
             (concat (map (juxt (constantly true) identity) pis)
                     (map (juxt (constantly false)  identity) (repeat is)))
            ;; need to make sure that we have at least as much m1 as prefixItems in m2
             (let [m1 (when (present? m1) m1)] (concat m1 (repeat (- (count pis) (count m1)) nil))))]
        (render-array minIs maxIs ro (v? c1 m1) p1 m1 rows)))))

(defmethod render-2 [::html5 "oneOf" :default]
  [c2 p2 k2 {oos "oneOf" t "title" des "description" :as m2}]
  (let [v? (valid? c2 m2)]
    (fn [c1 p1 k1 m1]
      ;;(println "ONE-OF:" p1 m1)
      ;;(println "ONE-OF:" (map (juxt (partial get-m1 context) (partial json/expand-$ref c2 p1)) oos) m1)
      (let [{[[match] :as valid] true invalid false}
            (if (present? m1)
              (group-by
               (comp not seq second)
               (mapv (fn [oo] [oo ((json/check-schema c2 p1 oo) c1 [] m1)]) oos))
              {})
            num-valid (count valid)]
        (cond
          (or (absent? m1) (= 1 num-valid))
          [:div {:title des :style {:background "#cc6699"} :class (v? c1 m1)}
           [:table
            [:caption
             [:h4
              (str (or t "One of") ":  ")
              (drop-down c2 p1 (map (juxt (fn [oo] (let [oo (json/expand-$ref c2 p2 oo)] (or (oo "title") (get-in oo ["properties" "type" "const"] absent)))) (partial get-m1 p2 c2)) oos) (when (present? m1) (get-m1 c2 p2 match)))]]
            [:tbody
             (when match [:tr [:td ((render-1 c2 p2 k2 match) c1 p1 k1 m1)]])]]] ;TODO: add schema index to p2 ?
          (= 0 num-valid)
          (log/error [(format "oneOf: %s schemas matched, when one and only one should have done so" num-valid) (mapv second invalid)])
          :else
          (log/error [(format "oneOf: %s schemas matched, when one and only one should have done so" num-valid) (mapv first valid)]))))))


