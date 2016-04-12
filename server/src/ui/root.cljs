(ns ui.root
  (:refer-clojure :exclude [find remove when])
  (:require [clojure.string :as string]
            [clojure.set :as set])
  (:require-macros [ui.macros :refer [elem afor log box text button input transaction extract for-fact when]]))

(enable-console-print!)

(declare render)
(declare move-selection!)

;;---------------------------------------------------------
;; Utils
;;---------------------------------------------------------

(def KEYS {:enter 13
           :shift 16
           :escape 27
           :backspace 8
           :left 37
           :up 38
           :right 39
           :down 40})

(defn non-zero-inc [number]
  (if (= number -1)
    1
    (inc number)))

(defn non-zero-dec [number]
  (if (= number 1)
    -1
    (dec number)))

;;---------------------------------------------------------
;; Runtime wrapper
;;---------------------------------------------------------

(defonce eve (.indexer js/Runtime))

(defn find-one [table & [info]]
  (.findOne eve (name table) (clj->js info)))

(defn find [table & [info]]
  (.find eve (name table) (clj->js info)))

(defn add [diff table fact]
  (.add diff (name table) (clj->js fact))
  diff)

(defn remove [diff table fact]
  (.remove diff (name table) (clj->js fact))
  diff)

;;---------------------------------------------------------
;; local state
;;---------------------------------------------------------

(defonce state-store (atom {}))

(defn args-to-key [args]
  (string/join "-" (for [arg args]
                     (if (keyword? arg)
                       (name arg)
                       arg))))

(defmulti state identity)

(defmethod state :cells [& args]
  (or (@state-store (args-to-key args))
      (array)))

(defmethod state :selections [& args]
  (or (@state-store (args-to-key args))
      (array {:x 0 :y 0 :width 1 :height 1})))

(defmethod state :grid [_ id]
  (or (@state-store (args-to-key [:grid id]))
      id))

(defmethod state :name [_ name]
  (get (@state-store :name) (.toLowerCase name)))

(defmethod state :name-matches [_ partial-name]
  (seq (filter (fn [[k v]]
                 (> (.indexOf k partial-name) -1))
               (@state-store :name))))

(defmethod state :default [& args]
  (@state-store (args-to-key args)))

(defmulti set-state! identity)
(defmethod set-state! :default [& args]
  (swap! state-store assoc (args-to-key (butlast args)) (last args)))

(defmethod set-state! :grid [_ id value]
  (swap! state-store assoc (args-to-key [:grid id]) value)
  (swap! state-store update-in [:name] assoc (.toLowerCase value) id))

(def facts-by-id (js-obj))
(def facts-by-tag (js-obj))

(defn get-fact-by-id [id]
  (aget facts-by-id id))

(defn entities [info]
  (let [{:keys [id tag]} info]
    (cond
      id [(get-fact-by-id id)]
      tag (let [first-tag (if (set? tag)
                            (first tag)
                            tag)
                objs (map get-fact-by-id (aget facts-by-tag first-tag))
                result (to-array (filter (fn [obj]
                                           (every? identity
                                                   (for [[k v] info
                                                         :let [obj-value (obj k)]]
                                                     (cond
                                                       (and (set? v) (set? obj-value)) (set/subset? v obj-value)
                                                       (set? obj-value) (obj-value v)
                                                       :else (= v (obj k))))))
                                         objs))]
            (if (> (count result) 0)
              result))
      :else (throw (js/Error. "Lookups must either contain an id or a tag")))))

(defn entity [info]
  (first (entities info)))

(defn property-updater [cur v]
  (cond
    (set? cur) (conj cur v)
    cur #{cur v}
    :else v))

(defn property-remover [cur v]
  (cond
    (and (set? cur) (>= (count cur) 2)) (disj cur v)
    (set? cur) (first (disj cur v))
    (= cur v) nil
    :else cur))

(defn add-eavs! [eavs]
  (doseq [[e a v] eavs
          :let [obj (or (aget facts-by-id e) {:id e})]]
    (println "ADDING" e a v)
    (aset facts-by-id e (update-in obj [a] property-updater v))
    (when (= :tag a)
      (doseq [tag (if (set? v)
                    v
                    #{v})]
        (aset facts-by-tag tag (if-let [cur (aget facts-by-tag tag)]
                                 (conj cur e)
                                 #{e}))))))

(defn remove-eavs! [eavs]
  (doseq [[e a v] eavs
          :let [obj (or (aget facts-by-id e) {:id e})]]
    (aset facts-by-id e (update-in obj [a] property-remover v))
    (when (= :tag a)
      (doseq [tag (if (set? v)
                    v
                    #{v})]
        (aset facts-by-tag tag (if-let [cur (aget facts-by-tag tag)]
                                 (disj cur e)))))))

(defn make-transaction-context []
  (js-obj))

(defn insert-facts! [context info]
  (let [id (if (symbol? (:id info))
             (or (aget context (name (:id info))) (let [new-id (js/uuid)]
                                                    (aset context (name (:id info)) new-id)
                                                    new-id))
             (or (:id info) (js/uuid)))]
    (add-eavs! (for [[k v] (dissoc info :id)
                     :let [v (if (symbol? v)
                               (aget context (name v))
                               v)]]
                 [id k v])))
  context)

(defn remove-facts! [context info]
  (let [id (or (:id info) (throw (js/Error "remove-facts requires an id to remove from")))]
    (remove-eavs! (for [[k v] (dissoc info :id)]
                    [id k v])))
  context)

(defn remove-entity! [context id]
  (let [obj (get-fact-by-id id)
        tags (:tag obj)]
    (aset facts-by-id id nil)
    (doseq [tag (if (set? tags)
                  tags
                  #{tags})]
      (aset facts-by-tag tag (if-let [cur (aget facts-by-tag tag)]
                               (disj cur id)))))
  context)

; (transaction context
;              (-> context
;                  (insert-facts! {:id 'root :tag "cell" :height 1 :width 1 :x 1 :y 1 :type :property})
;                  (insert-facts! {:tag "cell" :height 2 :width 2 :x 4 :y 5 :type :property :buddy 'root})
;                  (remove-facts! {:id (:id (first (entities {:tag "cell"}))) :width 1 :height 1})
;                  (remove-entity! (:id (first (entities {:tag "cell"})))))
;              (println (entities {:tag "cell"})))

; (entities :tag "cell" :grid "yo")
; (entities :tag "cell" :grid grid-id)
; (fact-btu :tick 1234)
; (insert-fact! :id 1 :tag "cell" :name "foo")
; (remove-fact! :id 3)

;;---------------------------------------------------------
;; Styles
;;---------------------------------------------------------

(defn style [& things]
  (let [mixins (take-while #(not (keyword? %)) things)
        non-mixins (drop-while #(not (keyword? %)) things)
        pairs (partition 2 non-mixins)
        start (reduce str "" mixins)]
    (reduce (fn [prev [a b]]
              (str prev (name a) ":" b "; "))
            start
            pairs)))

(def shared-style (style :color "white"))

(def colors {:background-border "#ddd"})

(def background-border (style :border (str "1px solid " (:background-border colors))))

;;---------------------------------------------------------
;; Global dom stuff
;;---------------------------------------------------------

(defonce global-dom-state (atom {}))

(defn prevent-default [event]
  (.preventDefault event))

(defn global-mouse-down []
  (@global-dom-state :mouse-down))

(defn global-dom-init []
  (.addEventListener js/window "mousedown"
                     (fn [event]
                       (swap! global-dom-state assoc :mouse-down true)))

  (.addEventListener js/window "mouseup"
                     (fn [event]
                       (log "GLOBAL MOUSE UP!")
                       (swap! global-dom-state assoc :mouse-down false)))
  (.addEventListener js/window "keydown"
                     (fn [event]
                       (let [target-node-name (.-nodeName (.-target event))
                             ignore-names #{"INPUT", "TEXTAREA"}]
                         (when-not (ignore-names target-node-name)
                           (prevent-default event))))))

(defn focus-once [node elem]
  (when-not (.-focused node)
    (set! (.-focused node) true)
    (.focus node)))

(defn auto-focus [node elem]
  (.focus node))

;;---------------------------------------------------------
;; Autocomplete
;;---------------------------------------------------------

(defn autocomplete-selection-keys [event elem]
  (let [{:keys [cell id]} (.-info elem)
        key-code (.-keyCode event)
        {:keys [grid-id]} cell
        intermediates (state :active-cell-intermediates id)
        intermediates (if-not (:autocomplete-selection intermediates)
                        (assoc intermediates :autocomplete-selection 0)
                        intermediates)]
    (when (= key-code (KEYS :up))
      (transaction
        (set-state! :active-cell-intermediates id (update-in intermediates [:autocomplete-selection] dec))
        (.preventDefault event)))
    (when (= key-code (KEYS :down))
      (transaction
        (set-state! :active-cell-intermediates id (update-in intermediates [:autocomplete-selection] inc))
        (.preventDefault event)))))


(defn match-autocomplete-options [options value]
  (if (or (not value)
          (= value ""))
    options
    (filter (fn [opt]
              (> (.indexOf (.toLowerCase (:text opt)) value) -1))
            options)))

(defmulti get-autocompleter-options identity)

(defmethod get-autocompleter-options :value [_ value]
  (concat (when (and value (not= value ""))
            (if-let [matches (state :name-matches value)]
              (for [[k v] matches]
                {:text k :adornment "link" :action :link :value v})
              [{:text value :adornment "create" :action :create :value value}]))
          (match-autocomplete-options [{:text "Table" :adornment "insert" :action :insert :value :table}
                                       {:text "Image" :adornment "insert" :action :insert :value :image}
                                       {:text "Text" :adornment "insert" :action :insert :value :text}
                                       {:text "Chart" :adornment "insert" :action :insert :value :chart}
                                       {:text "Drawing" :adornment "insert" :action :insert :value :drawing}
                                       {:text "UI" :adornment "insert" :action :insert :value :ui}]
                                      value)))

(defmethod get-autocompleter-options :property [_ value]
  (when (and value (not= value ""))
    [{:text value :action :set-property :value value}]))

(defn autocompleter-item [{:keys [type adornment selected] :as info}]
  (box :style (style :padding "7px 10px 7px 8px"
                     :background (if selected
                                   "#222"
                                   "none")
                     :white-space "nowrap"
                     :flex-direction "row")
    :children (array
                     (when adornment
                       (text :style (style :color "#777"
                                           :margin-right "5px")
                             :text adornment))
                     (text :style (style :color (or (:color info) "#ccc"))
                           :text (:text info))
                     )))

(defn autocompleter [type value selected]
  (let [options (get-autocompleter-options type value)]
    (when options
      (let [with-selected (update-in (vec options) [(mod selected (count options))] assoc :selected true)
            items (to-array (map autocompleter-item with-selected))]
        (box :style (style :position "absolute"
                           :background "#000"
                           :margin-top "10px"
                           :left -1
                           :z-index 10
                           :min-width "100%"
                           :border "1px solid #555")
             :children items)))))

(defn get-selected-autocomplete-option [type value selected]
  (when-let [options (get-autocompleter-options type value)]
    (when (seq options)
      (nth options (mod selected (count options))))))

;;---------------------------------------------------------
;; selections
;;---------------------------------------------------------

(defn get-selections [grid-id]
  (let [ents (entities {:tag "selection"
                       :grid-id grid-id})]
    (if ents
      (.map ents (fn [cur]
                   (if (:cell-id cur)
                     (merge (entity {:id (:cell-id cur)}) cur)
                     cur)))
      (array {:id "fake-selection" :x 0 :y 0 :width 1 :height 1}))))

(defn get-active-cell [grid-id]
  (let [grid-user-state (entity {:tag "grid-user-state"
                                 :grid-id grid-id})]
    (if (:active-cell grid-user-state)
      (entity {:id (:active-cell grid-user-state)}))))

(defn update-active-cell! [context grid-id new-value]
  (let [grid-user-state (entity {:tag "grid-user-state" :grid-id grid-id})]
    (when grid-user-state
      (remove-facts! context {:id (:id grid-user-state) :active-cell (:active-cell grid-user-state)}))
    (when new-value
      (if-not grid-user-state
        (insert-facts! context {:tag "grid-user-state" :grid-id grid-id :active-cell new-value})
        (insert-facts! context {:id (:id grid-user-state) :active-cell new-value})))))

(defn update-extending-selection [context grid-id new-value]
  (let [grid-user-state (entity {:tag "grid-user-state" :grid-id grid-id})]
    (when grid-user-state
      (remove-facts! context {:id (:id grid-user-state) :extending-selection (:extending-selection grid-user-state)}))
    (when new-value
      (if-not grid-user-state
        (insert-facts! context {:tag "grid-user-state" :grid-id grid-id :extending-selection new-value})
        (insert-facts! context {:id (:id grid-user-state) :extending-selection new-value})))))

;;---------------------------------------------------------
;; Cell types
;;---------------------------------------------------------

(defn property-keys [event elem]
  (let [{:keys [cell id]} (.-info elem)
        key-code (.-keyCode event)
        {:keys [grid-id]} cell
        intermediates (state :active-cell-intermediates id)]
    (when (= key-code (KEYS :enter))
      (-> event (.-currentTarget) (.-parentNode)
          (.. (querySelector ".value") (focus))))
      (let [selected (get-selected-autocomplete-option :property (:property intermediates) (:autocomplete-selection intermediates 0))]
        (when (not= (:text selected) (:property intermediates))
          (transaction
            (set-state! :active-cell-intermediates id (assoc intermediates :property (:text selected))))))
    (when (= key-code (KEYS :escape))
      (transaction
        (set-state! :active-cell-intermediates id nil)
        (set-state! :active-cell grid-id nil)))))

(defn value-keys [event elem]
  (let [{:keys [cell id]} (.-info elem)
        key-code (.-keyCode event)
        {:keys [grid-id]} cell]
    (when (= key-code (KEYS :enter))
      (if (.-shiftKey event)
        ;;  In Excel, shift-enter is just like enter but in the upward direction, so if shift
        ;;  is pressed then we move back up to the property field.
        (-> event (.-currentTarget) (.-parentNode)
            (.. (querySelector ".property") (focus)))
        ;; otherwise we submit this cell and move down
        (let [intermediates (state :active-cell-intermediates id)
              selected (get-selected-autocomplete-option :value (or (:value intermediates) (state :grid (:value cell))) (:autocomplete-selection intermediates 0))
              {:keys [action]} selected]
          (println selected)
          (transaction context
            (cond
              (= action :insert) (do
                                   (set-state! :cells grid-id (afor [cell (state :cells grid-id)]
                                                                    (if (= id (:id cell))
                                                                      (merge cell {:property (or (:property intermediates) (:property cell))
                                                                                   :type (:value selected)})
                                                                      cell)))
                                   (set-state! :active-cell-intermediates id (assoc intermediates :value nil)))
              (or (= action :create)
                  (= action :link)) (let [value-id (if (= action :link)
                                                     (:value selected)
                                                     (js/uuid))]
                                      (when (= action :create)
                                        (set-state! :grid value-id (:text selected))
                                        ;; TODO: add a new grid
                                        )
                                      (set-state! :cells grid-id (afor [cell (state :cells grid-id)]
                                                                       (if (= id (:id cell))
                                                                         (merge cell {:property (or (:property intermediates) (:property cell))
                                                                                      :value value-id})
                                                                         cell)))
                                      (set-state! :active-cell-intermediates id nil)
                                      (update-active-cell! context grid-id nil)
                                      (move-selection! context grid-id :down)))))))
    (when (= key-code (KEYS :escape))
      (transaction
        (set-state! :active-cell-intermediates id nil)
        (set-state! :active-cell grid-id nil))))
  (autocomplete-selection-keys event elem))

(defn store-intermediate [event elem]
  (let [{:keys [cell field id]} (.-info elem)
        current-intermediate (or (state :active-cell-intermediates id) {})
        value (-> (.-currentTarget event) (.-value))]
    (transaction
      (set-state! :active-cell-intermediates id (assoc current-intermediate field value :autocomplete-selection 0)))))

(defn track-focus [event elem]
  (let [{:keys [cell field id]} (.-info elem)
        current-intermediate (or (state :active-cell-intermediates id) {})]
    (transaction
      (set-state! :active-cell-intermediates id (assoc current-intermediate :focus field)))))

(defn draw-property [cell active?]
  (let [intermediates (state :active-cell-intermediates (:id cell))]
    (if active?
      (input :style (style :color "#777"
                           :font-size "10pt"
                           :line-height "10pt"
                           :margin "6px 0 0 8px")
             :postRender (if-not (:property cell)
                           focus-once
                           js/undefined)
             :c "property"
             :focus track-focus
             :input store-intermediate
             :keydown property-keys
             :info {:cell cell :field :property :id (:id cell)}
             :placeholder "property"
             :value (or (:property intermediates) (:property cell)))
      (text :style (style :color "#777"
                          :font-size "10pt"
                          :margin "8px 0 0 8px")
            :text (:property cell "property")))))

(defmulti draw-cell :type)

(defmethod draw-cell :property [cell active?]
  (let [intermediates (state :active-cell-intermediates (:id cell))
        current-focus (or (:focus intermediates)
                          (if-not (:property cell)
                            :property
                            :value))
        property-element (draw-property cell active?)]
    (box :children
         (if active?
           (array property-element
                  (input :style (style :font-size "12pt"
                                       :color "#CCC"
                                       :margin "0px 0 0 8px")
                         :postRender (if (:property cell)
                                       focus-once
                                       js/undefined)
                         :focus track-focus
                         :input store-intermediate
                         :keydown value-keys
                         :c "value"
                         :info {:cell cell :field :value :id (:id cell)}
                         :placeholder "value"
                         :value (or (:value intermediates) (state :grid (:value cell))))
                  (if (= :property current-focus)
                    (autocompleter :property (or (:property intermediates) (:property cell) "") (:autocomplete-selection intermediates 0))
                    (autocompleter :value (or (:value intermediates) (state :grid (:value cell)) "") (:autocomplete-selection intermediates 0))))
           (array property-element
                  (button :style (style :font-size "12pt"
                                        :margin "1px 0 0 8px")
                          :click (fn [event elem] (println "CLICKED!"))
                          :children (array (text :text (state :grid (:value cell ""))))))))))

(defmethod draw-cell :default [cell active?]
  (let [intermediates (state :active-cell-intermediates (:id cell))
        current-focus (or (:focus intermediates)
                          (if-not (:property cell)
                            :property
                            :value))
        property-element (draw-property cell active?)
        type (name (:type cell))]
    (box :children
         (if active?
           (array property-element
                  (input :style (style :font-size "12pt"
                                       :color "#CCC"
                                       :margin "0px 0 0 8px")
                         :postRender (if (:property cell)
                                       focus-once
                                       js/undefined)
                         :focus track-focus
                         :input store-intermediate
                         :keydown value-keys
                         :c "value"
                         :info {:cell cell :field :value :id (:id cell)}
                         :placeholder "value"
                         :value (or (:value intermediates) type))
                  (if (= :property current-focus)
                    (autocompleter :property (or (:property intermediates) (:property cell) "") (:autocomplete-selection intermediates 0))
                    (autocompleter :value (or (:value intermediates) (:value cell) "") (:autocomplete-selection intermediates 0))))
           (array property-element
                  (text :style (style :font-size "12pt"
                                      :margin "3px 0 0 8px")
                        :text type))))))

;;---------------------------------------------------------
;; Grid
;;---------------------------------------------------------

(defn draw-grid [node elem]
  (let [ctx (.getContext node "2d")
        ratio (.-devicePixelRatio js/window)
        info (.-info elem)
        width (:grid-width info)
        height (:grid-height info)
        size-x (:cell-size-x info)
        size-y (:cell-size-y info)
        adjusted-size-y (* ratio size-y)
        adjusted-size-x (* ratio size-x)]
    (set! (.-width node) (* ratio width))
    (set! (.-height node) (* ratio height))
    (set! (.-lineWidth ctx) 1)
    (set! (.-strokeStyle ctx) "#333")
    (dotimes [vertical (/ height size-y)]
      (.beginPath ctx)
      (.moveTo ctx 0 (* adjusted-size-y vertical))
      (.lineTo ctx (* ratio width) (* adjusted-size-y vertical))
      (.stroke ctx)
      (.closePath ctx))
    (dotimes [horizontal (/ width size-x)]
      (.beginPath ctx)
      (.moveTo ctx (* adjusted-size-x horizontal) 0)
      (.lineTo ctx (* adjusted-size-x horizontal) (* ratio height))
      (.stroke ctx)
      (.closePath ctx))
  ))

(defn target-relative-coords [event]
  (let [bounding-box (.getBoundingClientRect (.-currentTarget event))
        x (.-clientX event)
        y (.-clientY event)]
    {:x (- x (.-left bounding-box))
     :y (- y (.-top bounding-box))}))

(defn cell-intersects? [pos pos2]
  (let [{:keys [x y width height]} pos
        {x2 :x y2 :y width2 :width height2 :height} pos2]
    (and (> (+ x width) x2)
         (> (+ x2 width2) x)
         (> (+ y height) y2)
         (> (+ y2 height2) y))))

(defn cell-contains? [pos pos2]
  (let [{:keys [x y width height]} pos
        {x2 :x y2 :y width2 :width height2 :height} pos2]
    (and (>= x2 x)
         (>= (+ x width) (+ x2 width2))
         (>= y2 y)
         (>= (+ y height) (+ y2 height2)))))

(defn get-intersecting-cell [pos cells]
  (when cells
    (let [len (count cells)]
      (loop [cell-ix 0]
        (if (> cell-ix len)
          nil
          (let [cell (aget cells cell-ix)]
            (if (cell-intersects? pos cell)
              cell
              (recur (inc cell-ix)))))))))

(defn get-all-interesecting-cells [pos cells]
  (let [result (array)]
    (dotimes [cell-ix (count cells)]
      (let [cell (aget cells cell-ix)]
        (when (cell-intersects? pos cell)
          (.push result cell))))
    (if (not= 0 (count result))
      result)))

(defn add-cell! [context grid-id cell]
  (log "ADDING A CELL")
  (let [with-id (assoc cell :tag "cell" :id 'new-guy :grid-id grid-id :type :property)]
    (insert-facts! context with-id)
    with-id))

(defn set-selection [event elem]
  (let [{:keys [x y]} (target-relative-coords event)
        {:keys [cell-size-x cell-size-y id cells]} (.-info elem)
        range? (.-shiftKey event)
        extend? (or (.-ctrlKey event) (.-metaKey event))
        selected-x (.floor js/Math (/ x cell-size-x))
        selected-y (.floor js/Math (/ y cell-size-y))
        pos {:tag "selection" :grid-id id :x selected-x :y selected-y :width 1 :height 1}
        maybe-selected-cell (get-intersecting-cell pos cells)
        addition (or maybe-selected-cell pos)
        final-selection (if (:id addition)
                          {:tag "selection" :grid-id id :cell-id (:id addition)}
                          addition)]
    (transaction context
                 (cond
                   range? (let [start (first (get-selections id))]
                            (doseq [selection (get-selections id)]
                              (remove-facts! context selection))
                            (insert-facts! context {:tag "selection" :grid-id id
                                                    :x (:x start) :y (:y start)
                                                    ;; height and width are calculated by determining the distance
                                                    ;; between the start and end points, but we also need to factor
                                                    ;; in the size of the end cell.
                                                    :width (+ (- (:x addition) (:x start)) (:width addition))
                                                    :height (+ (- (:y addition) (:y start)) (:height addition))
                                                    }))
                   extend? (insert-facts! context final-selection)
                   :else (do
                           (doseq [selection (get-selections id)]
                             (remove-facts! context selection))
                           (insert-facts! context final-selection)))
                 (update-extending-selection context id true)
                 (update-active-cell! context id nil))))

(declare stop-selecting)

(defn mousemove-extend-selection [event elem]
  (let [{:keys [id cell-size-y cell-size-x]} (.-info elem)
        point-selecting? (or (.-metaKey event) (.-ctrlKey event))
        grid-user-state (entity {:tag "grid-user-state" :grid-id id})]
    (when (and (:extending-selection grid-user-state)
               (not point-selecting?))
      (let [{:keys [x y]} (target-relative-coords event)
            selected-x (.floor js/Math (/ x cell-size-x))
            selected-y (.floor js/Math (/ y cell-size-y))
            start (first (get-selections id))
            ;; height and width are calculated by determining the distance
            ;; between the start and end points, but we also need to factor
            ;; in the size of the end cell.
            x-diff (- selected-x (:x start))
            y-diff (- selected-y (:y start))
            width (+ x-diff (if (> x-diff 0)
                              1
                              0))
            height (+ y-diff (if (> y-diff 0)
                               1
                               0))
            maybe {:tag "selection"
                   :grid-id id
                   :x (:x start)
                   :y (:y start)
                   :width (if (not= 0 width) width 1)
                   :height (if (not= 0 height) height 1)}]
        (transaction context
                     (doseq [selection (get-selections id)]
                       (remove-facts! context selection))
                     (insert-facts! context maybe)
                     (when-not (global-mouse-down)
                       (update-extending-selection context id false)
                       (stop-selecting event elem)))))))

(defn normalize-cell-size [{:keys [x y width height] :as cell}]
  (if-not (or (< width 0)
              (< height 0))
    cell
    (let [[final-x final-width] (if (< width 0)
                                  [(+ x width) (inc (.abs js/Math width))]
                                  [x width])
          [final-y final-height] (if (< height 0)
                                   [(+ y height) (inc (.abs js/Math height))]
                                   [y height])]
      {:x final-x
       :y final-y
       :width final-width
       :height final-height})))

(defn stop-selecting [event elem]
  (let [{:keys [id cells]} (.-info elem)
        selections (get-selections id)
        grid-user-state (entity {:tag "grid-user-state" :grid-id id})]
    (if (and (:extending-selection grid-user-state)
             (= 1 (count selections)))
      (let [current (first selections)
            normalized (normalize-cell-size current)
            intersecting (get-all-interesecting-cells normalized cells)
            final (if intersecting
                    (for [cell intersecting]
                      {:tag "selection" :grid-id id :cell-id (:id cell)})
                    [(assoc normalized :tag "selection" :grid-id id)])]
        (transaction context
                     (doseq [selection (get-selections id)]
                       (remove-facts! context selection))
                     (doseq [new-selection final]
                       (insert-facts! context new-selection))
                     (update-extending-selection context id false))))
    (transaction context
                 (update-extending-selection context id false))))

(defn remove-overlap [cells updated-cells axis-and-direction-map]
  (let [changed (.slice updated-cells)
        final (.slice cells)
        width-direction (or (:width axis-and-direction-map) 0)
        height-direction (or (:height axis-and-direction-map) 0)]
    (while (not= 0 (.-length changed))
      (let [current-changed (.shift changed)]
        (loop [ix 0]
          (if (< ix (count final))
            (let [cell-to-check (aget final ix)]
              (if (or (= cell-to-check current-changed)
                      (not (cell-intersects? current-changed cell-to-check)))
                (recur (inc ix))
                (do
                  ;; determining the overlap is a matter of subtracting the left coordinate
                  ;; of one from the right coordinate of the other (and the equivalent for
                  ;; up and down), however because the change can be negative which is the
                  ;; left and which is the right might change.
                  (let [left (if (> width-direction 0)
                               (:x cell-to-check)
                               (:x current-changed))
                        right (if (> width-direction 0)
                                (+ (:x current-changed) (:width current-changed))
                                (+ (:x cell-to-check) (:width cell-to-check)))
                        width-overlap (* width-direction (- right left))
                        top (if (> height-direction 0)
                              (:y cell-to-check)
                              (:y current-changed))
                        bottom (if (> height-direction 0)
                                (+ (:y current-changed) (:height current-changed))
                                (+ (:y cell-to-check) (:height cell-to-check)))
                        height-overlap (* height-direction (- bottom top))
                        ;; modify it to remove the overlap by moving it over based on the
                        ;; overlap size
                        modified (-> cell-to-check
                                     (update-in [:x] + width-overlap)
                                     (update-in [:y] + height-overlap))]
                    ;; store the modified version and it to the list of things we
                    ;; need to check for overlap
                    (.push changed modified)
                    (.push final modified)
                    ;; remove the original item from final
                    (.splice final ix 1)
                    ;; look at the same ix since we just removed this cell
                    (recur ix)))))))))
    final))

(defn move-selection! [context grid-id direction]
  (let [cells (entities {:tag "cell"
                         :grid-id grid-id})
        selections (get-selections grid-id)
        current-selection (last selections)
        grid-user-state (entity {:tag "grid-user-state"
                                 :grid-id grid-id})
        x-offset (or (:x-offset grid-user-state) 0)
        y-offset (or (:y-offset grid-user-state) 0)
        updated-pos (condp = direction
                      :left (-> (update-in current-selection [:x] dec)
                                (update-in [:y] + y-offset))
                      :up (-> (update-in current-selection [:y] dec)
                              (update-in [:x] + x-offset))
                      :right (-> (update-in current-selection [:x] + (:width current-selection))
                                 (update-in [:y] + y-offset))
                      :down (-> (update-in current-selection [:y] + (:height current-selection))
                                (update-in [:x] + x-offset)))]
    ;; when we move the selection it becomes a unit-size selection
    ;; we then have to check if that unit is currently occupied by
    ;; a cell. If it is, we select the cell and store the offset to
    ;; make sure that if we're just passing through we end up in the
    ;; same row or column as we started.
    (let [resized-pos {:x (:x updated-pos)
                       :y (:y updated-pos)
                       :width 1
                       :height 1}
          maybe-selected-cell (get-intersecting-cell resized-pos cells)
          offset (if maybe-selected-cell
                   {:x (- (:x resized-pos) (:x maybe-selected-cell))
                    :y (- (:y resized-pos) (:y maybe-selected-cell))}
                   {:x 0 :y 0})
          final (if maybe-selected-cell
                  (-> (select-keys maybe-selected-cell [:x :y :width :height])
                      (assoc :cell-id (:id maybe-selected-cell)))
                  resized-pos)]
      (when offset (if grid-user-state
                     (do
                       (remove-facts! context {:id (:id grid-user-state)
                                               :x-offset x-offset
                                               :y-offset y-offset})
                       (insert-facts! context {:id (:id grid-user-state)
                                               :x-offset (:x offset)
                                               :y-offset (:y offset)}))
                     (insert-facts! context {:tag "grid-user-state"
                                             :grid-id grid-id
                                             :x-offset (:x offset)
                                             :y-offset (:y offset)})))
      (update-extending-selection context grid-id false)
      ;; remove all the previous selections
      (doseq [selection selections]
        (remove-facts! context selection))
      (insert-facts! context (assoc final :grid-id grid-id :tag "selection")))))

(defn extend-selection! [context grid-id direction]
  (let [cells (entity {:tag "cell" :grid-id grid-id})
        selections (get-selections grid-id)
        current-selection (last selections)
        updated-pos (condp = direction
                       ;; we use non-zero-inc/dec here because the size of the selection
                       ;; should always include the intially selected cell. So instead
                       ;; of a width of zero it will always be 1 or -1
                       :left (update-in current-selection [:width] non-zero-dec)
                       :up (update-in current-selection [:height] non-zero-dec)
                       :right (update-in current-selection [:width] non-zero-inc)
                       :down (update-in current-selection [:height] non-zero-inc))]
    ;; there's no offset if we're extending
    ;; TODO: set the offset
    (set-state! :offset grid-id {:x 0 :y 0})
    (update-extending-selection context grid-id true)
    (set-state! :selections grid-id (array updated-pos))))

(def directions {(KEYS :left) :left
                 (KEYS :up) :up
                 (KEYS :right) :right
                 (KEYS :down) :down})

(defn activate-cell! [grid-id cell]
  (if-not (:cell-id cell)
    (transaction context
                 (let [new-cell (add-cell! context grid-id cell)
                       selections (get-selections grid-id)]
                   (dotimes [ix (count selections)]
                     (let [selection (aget selections ix)]
                       (remove-facts! context selection)))
                   (update-active-cell! context grid-id (:id new-cell))
                   (insert-facts! context {:tag "selection" :grid-id grid-id :cell-id (:id new-cell)})))
    (transaction context
                 (let [prev-active (entity {:tag "active" :grid-id grid-id})]
                   (update-active-cell! context grid-id (:cell-id cell))))))

(defn grid-keys [event elem]
  (when (= (.-currentTarget event) (.-target event))
    (let [{:keys [id cells]} (.-info elem)
          selections (entities {:tag "selection"
                                :grid-id id})
          current-selection (last selections)
          key-code (.-keyCode event)
          shift? (.-shiftKey event)
          direction (directions key-code)]
      (condp = key-code
        ;; when pressing enter, we either need to create a new cell or if we have a currently
        ;; selected cell we need to activate it
        (:enter KEYS) (activate-cell! id current-selection)
        ;; pressing backspace should nuke any selected cells
        (:backspace KEYS) (let [selected-ids (into #{} (for [selection (get-selections id)]
                                                         (:cell-id selection)))]
                            (transaction context
                              (doseq [selection (get-selections id)]
                                (when (:cell-id selection)
                                  (remove-facts! context (entity {:id (:cell-id selection)})))
                                (remove-facts! context selection)
                                (insert-facts! context (select-keys current-selection [:tag :grid-id :x :y :width :height]))))
                            (.preventDefault event))
        ;; otherwise if you pressed an arrow key, we need to move or extend depending on
        ;; whether shift is being held
        (when direction
          (transaction context
            (if shift?
              (extend-selection! id direction)
              (move-selection! context id direction)))
          (.preventDefault event))))))

(defn grid-input [event elem]
  (when (= (.-currentTarget event) (.-target event))
    (let [{:keys [id cells]} (.-info elem)
          selections (get-selections id)
          current-selection (last selections)
          current-value (-> event (.-currentTarget) (.-value))]
      ;; if you don't have an already existing cell selected, then we need
      ;; to create a new cell with a property name starting with whatever
      ;; you've typed at this point
      (if-not (:id current-selection)
        (transaction context
          (let [new-cell (add-cell! context id current-selection)
                new-cell-id (:id new-cell)]
            (doseq [selection selections]
              (remove-facts! context selection))
            (set-state! :selections id (array new-cell))
            (set-state! :active-cell id new-cell-id)
            (set-state! :active-cell-intermediates new-cell-id {:property current-value})))
        ;; otherwise if you are on an already existing cell, we need to
        ;; activate that cell, and set its value to what you've typed so far
        ;; TODO: what happens in the case where you don't have a normal property
        ;; cell? What does it mean to type on top of a chart, for example?
        (transaction
          (set-state! :active-cell id (:id current-selection))
          (set-state! :active-cell-intermediates (:id current-selection) {:value current-value})))
      (set! (.-value (.-currentTarget event)) ""))))

(defn grid-keys-up [event elem]
  ;; check for shift key if we were expanding
  (let [{:keys [id]} (.-info elem)]
    (when (and (= (:shift KEYS) (.-keyCode event))
               (state :extending-selection id))
      (transaction
        (stop-selecting event elem)
        (set-state! :extending-selection id false)))))

(defn start-resize [event elem]
  (.stopPropagation event))

(defn transfer-focus-to-keyhandler [event elem]
  (-> (.-currentTarget event)
      (.querySelector ".keyhandler")
      (.focus)))

(defn maybe-activate-cell [event elem]
  (let [{:keys [id]} (.-info elem)
        selected (last (entities {:tag "selection"
                                  :grid-id id}))]
    (activate-cell! id selected)))

(defn grid [info]
  (let [canvas (elem :t "canvas"
                     :info info
                     :dragstart prevent-default
                     :postRender draw-grid
                     :style (style :width (:grid-width info)
                                   :height (:grid-height info)))
        children (array canvas)
        {:keys [cells cell-size-x cell-size-y selections]} info
        active-cell (get-active-cell (:id info))]
    (println "ACTIVE CELL" active-cell)
    (dotimes [cell-ix (count cells)]
      (let [{:keys [x y width height color id] :as cell} (aget cells cell-ix)
            is-active? (= (:id active-cell) (:id cell))]
        (.push children (box :id id
                             :style (style :width (- (* cell-size-x (or width 1)) 2)
                                           :height (- (* cell-size-y (or height 1)) 2)
                                           :position "absolute"
                                           :top (+ 0 (* y cell-size-y))
                                           :left (+ 0 (* x cell-size-x))
                                           :border "1px solid #666"
                                           :background (or color "#000"))
                             :children (array (draw-cell cell is-active?))))))
    (dotimes [selection-ix (count selections)]
      (let [selection (aget selections selection-ix)
            selection (if (:cell-id selection)
                        (entity {:id (:cell-id selection)})
                        selection)
            color "#fff"
            ;; we have to normalize selections since while they're being expanded
            ;; they can have negative widths and heights
            {:keys [x y width height]} (normalize-cell-size selection)]
        (.push children (box :style (style :width (- (* cell-size-x width) 2)
                                           :height (- (* cell-size-y height) 2)
                                           :position "absolute"
                                           :top (* y cell-size-y)
                                           :left (* x cell-size-x)
                                           :pointer-events "none"
                                           :background "rgba(255,255,255,0.12)"
                                           :border (str "1px solid " (or color "#aaffaa")))
                             ;; add a resize handle to the selection
                             :children (array (elem :mousedown start-resize
                                                    ;; mouseup and mousemove can't be handled here since it's
                                                    ;; fairly unlikely that your mouse will be exactly over the
                                                    ;; resize handle as you're resizing. These are handled globally
                                                    ;; on the window
                                                    :style (style :width 10
                                                                  :height 10
                                                                  :position "absolute"
                                                                  :bottom -5
                                                                  :right -5
                                                                  :background "none")))))))
    (.push children (input :c "keyhandler"
                           :key active-cell
                           :postRender (if-not active-cell
                                         auto-focus
                                         js/undefined)
                           :keydown grid-keys
                           :keyup grid-keys-up
                           :input grid-input
                           :info info
                           :style (style :width 0
                                         :height 0
                                         :padding 0
                                         ; :visibility "hidden"
                                         :position "absolute"
                                         :background "transparent"
                                         :color "transparent")))
    (elem :children children
          :c (str "noselect " (when-not active-cell "focused"))
          :info info
          :tabindex -1
          :focus transfer-focus-to-keyhandler
          :mousedown set-selection
          :dblclick maybe-activate-cell
          :mousemove mousemove-extend-selection
          :mouseup stop-selecting
          :style (style :position "relative"))))

;;---------------------------------------------------------
;; Root
;;---------------------------------------------------------

(defn root []
  (box :style (style :width "100vw"
                     :height "100vh"
                     :align-items "center"
                     :justify-content "center"
                     :color "#ccc"
                     :font-family "Lato")
       :children (array (grid {:grid-width (.-innerWidth js/window)
                               :grid-height (.-innerHeight js/window)
                               :selections (get-selections "main")
                               :cells (entities {:tag "cell"
                                               :grid-id "main"})
                               :cell-size-y 50
                               :cell-size-x 120
                               :id "main"}))))

;;---------------------------------------------------------
;; Rendering
;;---------------------------------------------------------

(defonce renderer (atom false))

(defn render []
  (when (not (.-queued @renderer))
    (set! (.-queued @renderer) true)
    (js/requestAnimationFrame
      (fn []
        (let [ui (root)]
          (.render @renderer #js [ui])
          (set! (.-queued @renderer) false))))))

;;---------------------------------------------------------
;; Init
;;---------------------------------------------------------

(defn init []
  (when (not @renderer)
    (reset! renderer (new js/Renderer))
    (.appendChild (.-body js/document) (.-content @renderer))
    (global-dom-init))
  (render))

(init)

;;---------------------------------------------------------
;; TODO
;;---------------------------------------------------------
;;
;; Cells
;;  - cut
;;  - copy
;;  - paste
;;  - resize
;;  - move?
;;  - persistence
;;
;; Cell Types
;;  - draw
;;  - activate
;;  - focus/selection management
;;  - types
;;    - Property
;;    - Text
;;    - Table
;;    - Image
;;    - Formula?
;;    - Chart
;;    - Drawing
;;    - UI
;;    - Embedded grid
;;
;; Autocompleter
;;  - Searching
;;  - Actions
;;
;; Formula language
;;  - Translator
;;  - Autocomplete provider
;;