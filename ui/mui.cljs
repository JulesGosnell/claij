(ns m3.mui
  (:require
   [clojure.set :refer [rename-keys]]
   [react :as react]
   [reagent.core :as r]
   [re-frame.core :as rf]
   [react-imask :refer [IMaskMixin]]
   ["@mui/material/Paper" :refer [Paper]]
   [reagent-mui.icons.account-circle     :refer [account-circle]]
   [reagent-mui.icons.add-box            :refer [add-box]]
   [reagent-mui.icons.delete-forever     :refer [delete-forever]]
   [reagent-mui.icons.edit               :refer [edit]]
   [reagent-mui.icons.notifications      :refer [notifications]]
   [reagent-mui.icons.work               :refer [work]]
   [reagent-mui.material.app-bar         :refer [app-bar]]
   [reagent-mui.material.badge           :refer [badge]]
   [reagent-mui.material.box             :refer [box]]
   [reagent-mui.material.breadcrumbs     :refer [breadcrumbs]]
   [reagent-mui.material.button          :refer [button]]
   [reagent-mui.material.button-group    :refer [button-group]]
   [reagent-mui.material.checkbox        :refer [checkbox]]
   [reagent-mui.material.container       :refer [container]]
   [reagent-mui.material.form-control    :refer [form-control]]
   [reagent-mui.material.grid            :refer [grid]]
   [reagent-mui.material.icon-button     :refer [icon-button]]
   [reagent-mui.material.input           :refer [input]]
   [reagent-mui.material.input-label     :refer [input-label]]
   [reagent-mui.material.link            :refer [link]]
   [reagent-mui.material.menu            :refer [menu]]
   [reagent-mui.material.menu-item       :refer [menu-item]]
   [reagent-mui.material.paper           :refer [paper]]
   [reagent-mui.material.stack           :refer [stack]]
   [reagent-mui.material.step            :refer [step]]
   [reagent-mui.material.step-label      :refer [step-label]]
   [reagent-mui.material.stepper         :refer [stepper]]
   [reagent-mui.material.tab             :refer [tab]]
   [reagent-mui.material.table           :refer [table]]
   [reagent-mui.material.table-body      :refer [table-body]]
   [reagent-mui.material.table-cell      :refer [table-cell]]
   [reagent-mui.material.table-container :refer [table-container]]
   [reagent-mui.material.table-footer    :refer [table-footer]]
   [reagent-mui.material.table-head      :refer [table-head]]
   [reagent-mui.material.table-row       :refer [table-row]]
   [reagent-mui.material.tabs            :refer [tabs]]
   [reagent-mui.material.tabs            :refer [tabs]]
   [reagent-mui.material.text-field      :refer [text-field]]
   [reagent-mui.material.toolbar         :refer [toolbar]]
   [reagent-mui.material.tooltip         :refer [tooltip]]
   [reagent-mui.material.typography      :refer [typography]]

   [m3.log :as log]
   [m3.json :refer [absent present?]]
   [m3.validate :as json]
   [m3.util :refer [valid? check-formats conjv make-id mapl vector-remove-nth]]
   [m3.render :refer [render-2 render-1]]

   [m3.divorce :refer [security-model]] ;TODO: should not be here
   ))

(let [state (r/atom {:roles (security-model "roles") :active-roles #{} :active-tab 0})]

  (defn inbox-button []
    (fn []
      [icon-button
       {:size "large"
        :aria-label "inbox"
        :color "inherit"}
       [badge
        {:badge-content 17
         :color "error"}
        [notifications]]]))

  (defn edit-button []
    (fn []
      [icon-button
       {:size "large"
        :aria-label "edit"
        :color "inherit"}
       [edit]]))

  (defn workflow-button []
    (fn []
      [icon-button
       {:size "large"
        :aria-label "workflow"
        :color "inherit"}
       [work]]))

  (defn change-role [e]
    (let [t (.-target e)]
      (swap! state update :active-roles (fn [rs] ((if (.-checked t) conj disj) rs (.-name t))))))

  (defn role-menu [id roles]
    (fn []
      [menu
       {:id "menu-appbar"
        :anchor-el (fn [] (id @state))
        :open (boolean (id @state))
        :on-close #(swap! state dissoc id)
        :anchor-origin {:vertical "top" :horizontal "right"}
        :transform-origin {:vertical "top" :horizontal "right"}
        ;;:on-close (fn [] (swap! state dissoc id))
        }
       (doall
        (map
         (fn [role]
           [menu-item
            {:key role}
            [checkbox
             {:name role
              :aria-label role
              :checked (contains? (:active-roles @state) role)
              :on-change change-role}]
            [typography {:text-align :center} role]])
         roles))]))

  (defn role-button [id]
    (fn []
      [tooltip {:title "Select Role"}
       [icon-button
        {:size "large"
         :aria-label "roles"
         :color "inherit"
         :on-click (fn [e] (swap! state assoc id (.-target e)))}
        [badge
         {:badge-content (count (:active-roles @state))
          :color "info"}
         [account-circle]]]]))

  (defn my-app-bar []
    (fn []
      [box {:sx {:flex-grow 1}}
       [app-bar {:position :static}
        [toolbar {:disable-gutters true}
         [edit-button]
         [inbox-button]
         [workflow-button]
         [box {:sx {:flex-grow 1}}]
         [box {:sx {:display {:xs :none :md :flex}}}
          [role-menu :role-button (:roles @state)]
          [role-button :role-button]]]]]))

  (defn tab-panel [{i :index} child]
    (let [id (str "simple-tabpanel-" i)
          active? (= i (:active-tab @state))]
      [:div
       {:role "tabpanel"
        :hidden (not active?)
        :id id
        :aria-label id}
       (when active?
         [box
          {:sx {:p 3}}
          [typography {:component :div} child]])]))

  (defn transition-buttons [bs]
    [box {:sx {:display :flex :justify-content :center}}
     [button-group
      {:variant :contained}
      (doall
       (map-indexed
        (fn [i {b "title" s "state"}]
          [button {:key i :on-change (fn [_e] (println "BUTTON:" s))}
           b])
        bs))]])

  (def item identity)

  (defn view-forms [[{{{m2-vs "prefixItems"} "views"} "properties"} {m1-vs "views" ts "transitions"}]]
    [grid {:container true :spacing 4 :justify-content :center}
     [grid {:item true :xs 12}
      [item
       [stepper {:active-step 1 :alternative-label true}
        [step  [step-label "Personal Information"]]
        [step  [step-label "Assets & Liabilities"]]
        [step  [step-label "Personal Expenses"]]
        [step [step-label "Making an Offer"]]]]]
     [grid {:item true :xs 12 :justify-content :center}
      [item
       [transition-buttons ts]]]
     [grid {:item true :xs 12}
      [item
       [box {:sx {:width "100%"}}
        [box {:sx {:border-bottom 1 :border-color "divider"}}
         [tabs {;;:orientation :vertical
                :variant :fullWidth
                :value (:active-tab @state) :on-change (fn [e i] (swap! state assoc :active-tab i))}
          (doall
           (map
            (fn [i {t "title"}]
              [tab {:key i :id (str "simple-tab-" i) :aria-controls (str "simple-tabpanel-" i) :label t :value i :wrapped true}])
            (range)
            m2-vs))]]
        (doall
         (map
          (fn [i v m1]
            [tab-panel {:key i :index i} [:table [:tbody [:tr [:td ((render-1 {:ui ::mui :check-format check-formats :draft "latest" :$ref-merger :merge-over :expanded #{}} [:m2] nil v) {} [:m1] nil m1)]]]]])
          (range)
          m2-vs
          m1-vs))]]]
     [grid  {:item true :xs 12 :justify-content :center}
      [item
       [transition-buttons ts]]]]))

;;------------------------------------------------------------------------------

(derive ::mui :m3.render/html5) ;; if no mui method available, use the html5 one...

(def masked-text-field
  (r/adapt-react-class
   (IMaskMixin
    (fn [props]
      ;;(prn "masked-text-field:" (rename-keys (js->clj props) {}))
      (r/as-element [text-field (rename-keys (js->clj props) {})])))))

(defn render-money-2 [c2 p2 k2 {t "type" title "title" des "description" es "enum" d "default" c "const" min "minimum" max "maximum" mo "multipleOf" :as m2}]
  (let [v? (valid? c2 m2)
        scale (if (= t "number") 2 0)
        signed (or (not min) (< min 0))]
    (fn [c1 p1 k1 m1]
      [:div {:style {:background "#ffff99" :padding "8px"} :class (v? c1 m1)}
       [masked-text-field
        {:mask "£num"
         :blocks {"num"
                  {:mask js/Number,
                   :radix "."
                   :scale scale
                   :signed signed
                   :thousands-separator ","
                   :pad-fractional-zeros false
                   :normalize-zeros true
                   :min min
                   :max max}}
         :value (str m1)
         :unmask true
         ;;:on-accept (fn [value mask] (prn "ON-ACCEPT:" value))
         :label title}]])))

(def render-money (memoize render-money-2))

(defmethod render-2 [::mui "integer" "money"] [c2 p2 k2 m2]
  (render-money c2 p2 k2 m2))

(defmethod render-2 [::mui "number" "money"] [c2 p2 k2 m2]
  (render-money c2 p2 k2 m2))

(defn render-imaskjs-2 [c2 p2 k2 {title "title" des "description" es "enum" d "default" c "const" :as m2} mask unmask]
  (let [v? (valid? c2 m2)]
    (fn [c1 p1 k1 m1]
      [:div {:style {:background "#ffff99" :padding "8px"} :class (v? c1 m1)}
       [masked-text-field
        {:mask mask
         :value m1
         :unmask unmask
         :placeholder-char \_
         :lazy false
         :overwrite true
         ;;:on-accept (fn [value mask] (prn "ON-ACCEPT:" value))
         :label title}]])))

(def render-imaskjs (memoize render-imaskjs-2))

(defmethod render-2 [::mui "string" "bank-sort-code"] [c2 p2 k2 m2]
  (render-imaskjs c2 p2 k2 m2 "00-00-00" false))

(defmethod render-2 [::mui "string" "bank-account-number"] [c2 p2 k2 m2]
  (render-imaskjs c2 p2 k2 m2 "{#} 00000000" true))

(defmethod render-2 [::mui "string" "telephone-number"] [c2 p2 k2 m2]
  (render-imaskjs c2 p2 k2 m2 "{+44 (\\0)}0000 000000" false))

(defmethod render-2 [::mui "string" :default]
  [c2 p2 k2
   {title "title" des "description" es "enum" d "default" c "const" minL "minLength" maxL "maxLength" ro "readOnly" :as m2}]
  (let [v? (valid? c2 m2)]
    (fn [c1 p1 k1 m1]
      [:div {:style {:background "#ffff99" :padding "8px"} :class (v? c1 m1)}
       (if (seq es)
         ;; enum string
         nil;;(drop-down context p1 (map (juxt identity identity) es) (when (present? m1) m1))
         ;; simple string
         [text-field
          {:placeholder d
           :default-value (when (present? m1) m1)
           :label title
           ;; :readOnly (or (boolean c) ro)
           ;; :minLength minL
           ;; :maxLength maxL
           ;; :size maxL
           ;; :on-change (fn [e] (rf/dispatch [:assoc-in p1 (.-value (.-target e))]))
           }])])))

(defmethod render-2 [::mui "object" :default]
  [{expanded? :expanded ok :original-key :as c2}
   p2 k2
   {ps "properties" pps "patternProperties" aps "additionalProperties" title "title" es "enum" :as m2}]
  (let [v? (valid? c2 m2)]
    (fn [c1 p1 k1 m1]
      [:div {:style {:background "#99ccff" :padding "8px"} :class (v? c1 m1)}
       ;; enum support
       [stack {:spacing 2 :label "TITLE" :variant :outlined}
        ;; TODO - optimise
        (doall
         (map
          (fn [[k {t "title" d "description" :as m2}]]
            (let [p2 (vec (concat p2 ["properties" k]))
                  p1 (conjv p1 k)
                  id (make-id p1)
                  squidgable? false ;;(squidgy? m2 m1)
                  visible? (or (and squidgable? (expanded? p2)) (not squidgable?))]
              ;;(rf/dispatch [:update-in [:zip] (fnil assoc {}) p2 p1])
              [:div {:key id} ((render-1 c2 p2 k m2) c1 p1 k (get (when (present? m1) m1) k absent))]))
          ps))]])))

(defmethod render-2 [::mui "array" :default]
  [c2 p2 k2
   {{def "default" :as is} "items" pis "prefixItems" maxIs "maxItems" ro? "readOnly" :as m2}]
  (let [v? (valid? c2 m2)
        r1 (render-1 c2 p2 k2 m2)]
    (fn [c1 p1 k1 m1]
      (let [m1 (when (present? m1) m1)]
        [:div {:style {:background "#ffcccc"} :class (v? c1 m1)}
         [stack
          [table-container {:component Paper}
           [table {:sx {} :size :small}
            ;;[table-head]
            [table-body
             ;; TODO: optimise
             (mapl
              (fn [n [prefix? m2 m1]]
                (let [p2 (vec (concat p2 (if prefix? ["prefixItems" n] ["items"])))
                      k2 (last p2)
                      p1 (conjv p1 n)
                      k1 n]
                  [table-row {:key (make-id p1)}
                   [table-cell
                    ;; non-prefix items should
                    ;; - be reorderable - by dragging or buttons ? - when not read-only
                    (r1 c1 p1 k1 m1)]
                   [table-cell {:align :left}
                    (when  (and (not prefix?) (not ro?))
                      [button
                       {:start-icon (r/as-element [delete-forever])
                        :on-click (fn [_] (rf/dispatch [:update-in (butlast p1) vector-remove-nth (last p1)]))}])]]))
              (range)
              (concat
               (map
                (fn [m2 m1] [true m2 m1])
                pis
                (concat m1 (repeat nil)))
               (map
                (fn [m2 m1] [false m2 m1])
                (repeat is)
                (drop (count pis) m1))))]
            [table-footer
             [table-row
              [table-cell {:align :center}
               (when-not ro?
                 [button {:start-icon (r/as-element [add-box])}])]]]]]]]))))
