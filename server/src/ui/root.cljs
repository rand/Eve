(ns ui.root
  (:refer-clojure :exclude [find remove when])
  (:require [clojure.string :as string]
            [clojure.walk :as walk]
            [clojure.set :as set])
  (:require-macros [ui.macros :refer [elem afor log box text button input transaction extract for-fact when]]))

(enable-console-print!)

(declare render)
(declare move-selection!)
(declare add-cell!)
(declare formula-grid->query)
(declare active-grid-id)
(declare get-grid-id-from-window)

(def USE-SERVER? true)
(def BE-STUPIDLY-OPTIMISTIC? true)
(def LOCAL-ONLY-TAGS #{"selection" "grid-user-state"})
(def FILTERS #{"=" ">" "<" "not="})
(def INFIX #{"=" ">" "<" "not=" "*" "/" "+" "-"})

(def GRID-SIZE {:width 120 :height 50})
(def FORMULA-GRID-SIZE {:width 30 :height 25})

;;---------------------------------------------------------
;; Utils
;;---------------------------------------------------------

(def KEYS {:enter 13
           :shift 16
           :tab 9
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

(defn query-string [obj]
  (pr-str (walk/prewalk (fn [cur]
                          (if-not (symbol? cur)
                            cur
                            (symbol (name cur))))
                        obj)))

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
;; Websocket
;;---------------------------------------------------------

(def websocket-address (str "ws://" (-> js/window
                                        (.-location)
                                        (.-host))))
(defonce websocket (atom nil))
(defonce id-to-query (atom {}))

(declare render)

(defn results-to-objects [fields results]
  (if-not results
    (array)
    (let [len (count fields)]
      (afor [result results]
            (let [obj (js-obj)]
              (dotimes [ix len]
                (aset obj (aget fields ix) (aget result ix)))
              obj)))))

(declare locally-add-eavs!)
(declare locally-remove-eavs!)

(defn send-websocket [message]
  (let [json-message (.stringify js/JSON (clj->js message))]
    (.send @websocket json-message)))

(defn send-query [id query]
  (send-websocket {:id id :type "query" :query query}))

(defn send-close [id]
  (send-websocket {:id id :type "close"}))

(defn websocket-init []
  (let [socket (new js/WebSocket websocket-address)]
    (set! (.-onopen socket) (fn [event]
                              (send-query "all facts"
                                          (query-string `(query [e a v]
                                                                (fact-btu e a v))))
                              (println "connected to server!")))
    (set! (.-onerror socket) (fn [event]
                               (println "the socket errored :(")))
    (set! (.-onclose socket) (fn [event]
                               (println "the socket closed :( :( :(")))
    (set! (.-onmessage socket) (fn [event]
                                 (let [data (.parse js/JSON (.-data event))
                                       changed? (atom false)]
                                   (condp = (.-type data)
                                     "result" (if (= (.-id data) "all facts")
                                                (let [inserts (.-insert data)
                                                      removes (.-remove data)
                                                      context (js-obj)]
                                                  (when (seq removes)
                                                    (reset! changed? true)
                                                    (log removes)
                                                    (locally-remove-eavs! context removes))
                                                  (when (seq inserts)
                                                    (reset! changed? true)
                                                    (locally-add-eavs! context inserts)))
                                                (when (seq (.-fields data))
                                                  (let [fields (.-fields data)
                                                        adds (results-to-objects fields (.-insert data))
                                                        removes (results-to-objects fields (.-remove data))
                                                        diff (.diff eve)]
                                                    (when (seq adds)
                                                      (reset! changed? true)
                                                      (.addMany diff (.-id data) adds))
                                                    (when (seq removes)
                                                      (reset! changed? true)
                                                      (.removeFacts diff (.-id data) removes))
                                                    (when @changed?
                                                      (.applyDiff eve diff)))))
                                     "error" (.error js/console "uh oh")
                                     "query-info" (do)
                                     )
                                   (when @changed?
                                     (render)))))
    ;; set a handler for when we navigate away so that we can make sure all
    ;; queries for this client are closed.
    (set! (.-onbeforeunload js/window)
          (fn []
            (send-close "all facts")
            (for [[k query-id] @id-to-query]
              (send-close query-id))
            nil))
    (reset! websocket socket)))


(defn replace-and-send-query [id query]
  (let [current (@id-to-query id)
        new-id (js/uuid)]
    (when current
      (send-close current)
      ;; remove the values that were in the table
      )
    (send-query new-id query)
    (swap! id-to-query assoc id new-id)))

;;---------------------------------------------------------
;; local state
;;---------------------------------------------------------

(defonce facts-by-id (js-obj))
(defonce facts-by-tag (js-obj))
(defonce attributes-index (js-obj))
(defonce entity-name-pairs (atom #{}))

(defonce local-ids (atom #{}))

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
    (and cur (not= cur v)) #{cur v}
    :else v))

(defn property-remover [cur v]
  (cond
    (and (set? cur) (> (count cur) 2)) (disj cur v)
    (set? cur) (first (disj cur v))
    (= cur v) nil
    :else cur))

(defn remote-add-eavs! [context eavs]
  (let [inserts-string (reduce (fn [cur [e a v]]
                                 (if (nil? v)
                                   cur
                                   (str cur " " (query-string `(insert-fact! ~e ~a ~v)))))
                               ""
                               eavs)]
    (when-not (aget context "__inserts")
      (aset context "__inserts" (array)))
    (.push (aget context "__inserts") inserts-string)))

(defn remote-remove-eavs! [context eavs]
  (when (seq eavs)
    (let [removes (mapcat (fn [[e a v]]
                            (when-not (nil? v)
                              (let [sym (gensym 'tick)]
                                (query-string `(query [](fact-btu ~e ~(name a) ~v :tick ~sym)
                                        (remove-by-t! ~sym))))))
                        eavs)
          removes-subquery (reduce str "" removes)]
      (when (seq removes)
        (when-not (aget context "__removes")
          (aset context "__removes" (array)))
        (.push (aget context "__removes") removes-subquery)))))

(defn locally-add-eavs! [context eavs]
  (doseq [[e a v] eavs
          :let [obj (or (aget facts-by-id e) {:id e})
                a (if-not (keyword? a)
                    (keyword a)
                    a)
                updated (update-in obj [a] property-updater v)]]
    (aset facts-by-id e updated)
    (aset attributes-index (name a) true)
    (when (= :name a)
      (swap! entity-name-pairs conj [e v]))
    (when (= :tag a)
      (doseq [tag (if (set? v)
                    v
                    #{v})]
        (aset facts-by-tag tag (if-let [cur (aget facts-by-tag tag)]
                                 (conj cur e)
                                 #{e}))))))

(defn locally-remove-eavs! [context eavs]
  (doseq [[e a v] eavs
          :let [obj (or (aget facts-by-id e) {})
                a (if-not (keyword? a)
                    (keyword a)
                    a)
                new-value (property-remover (obj a) v)
                updated (if-not (nil? new-value)
                          (assoc obj a new-value)
                          (dissoc obj a))]]
    (if (second (seq updated))
      (aset facts-by-id e updated)
      (aset facts-by-id e nil))
    (when (= :name a)
      (swap! entity-name-pairs disj [e v]))
    (when (= :tag a)
      (doseq [tag (if (set? v)
                    v
                    #{v})]
        (aset facts-by-tag tag (if-let [cur (aget facts-by-tag tag)]
                                 (disj cur e)))))))

(defn add-eavs! [context eavs force-local]
  (if (and USE-SERVER? (not force-local))
    (do
      (when BE-STUPIDLY-OPTIMISTIC?
        (locally-add-eavs! context eavs))
      (remote-add-eavs! context eavs))
    (locally-add-eavs! context eavs)))

(defn remove-eavs! [context eavs force-local]
  (if (and USE-SERVER? (not force-local))
    (do
      (when BE-STUPIDLY-OPTIMISTIC?
        (locally-remove-eavs! context eavs))
      (remote-remove-eavs! context eavs))
    (locally-remove-eavs! context eavs)))

(defn make-transaction-context []
  (js-obj))

(defn commit-transaction [context]
  (let [inserts (aget context "__inserts")
        removes (aget context "__removes")
        final-query (str "(query [] \n"
                         inserts
                         "\n"
                         removes
                         ")")
        query-id (js/uuid)]
    (when (or (seq inserts) (seq removes))
      (send-query query-id final-query)
      (send-close query-id))))

(defn insert-facts! [context info]
  (let [id (if (symbol? (:id info))
             (or (aget context (name (:id info))) (let [new-id (js/uuid)]
                                                    (aset context (name (:id info)) new-id)
                                                    new-id))
             (or (:id info) (js/uuid)))
        adds (array)]
    (when (or (LOCAL-ONLY-TAGS (:tag info))
              (if (coll? (:tag info))
                (first (filter LOCAL-ONLY-TAGS (:tag info)))))
      (swap! local-ids conj id))
    (doseq [[k v] (dissoc info :id)
            v (if (coll? v)
                v
                [v])]
      (let [v (if (symbol? v)
                (aget context (name v))
                v)]
        (if-not (nil? v)
          (.push adds [id k v]))))
    (add-eavs! context
               adds
               (@local-ids id)))
  context)

(defn remove-facts! [context info]
  (let [id (or (:id info) (throw (js/Error "remove-facts requires an id to remove from")))]
    (remove-eavs! context
                  (for [[k v] (dissoc info :id)
                        v (if (coll? v)
                            v
                            [v])]
                    [id k v])
                  (@local-ids id)))
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

(defn update-state! [context grid-id key new-value]
  (let [grid-user-state (entity {:tag "grid-user-state" :grid-id grid-id})]
    (when-not (= (key grid-user-state) new-value)
      (when grid-user-state
        (remove-facts! context {:id (:id grid-user-state) key (key grid-user-state)}))
      (when-not (nil? new-value)
        (if-not grid-user-state
          (insert-facts! context {:id 'new-user-state :tag "grid-user-state" :grid-id grid-id key new-value})
          (insert-facts! context {:id (:id grid-user-state) key new-value}))))))

(defn get-state [grid-id key & [otherwise]]
  (or (key (entity {:tag "grid-user-state" :grid-id grid-id}))
      otherwise))

(defn clear-intermediates! [context grid-id]
  (let [intermediate-keys [:intermediate-property :intermediate-value :focus :autocomplete-selection :intermediate-width]
        grid-user-state (entity {:tag "grid-user-state" :grid-id grid-id})]
    (when grid-user-state
      (remove-facts! context (select-keys grid-user-state (concat [:id] intermediate-keys))))))

(defn update-entity! [context entity-id update-map]
  (let [with-id (assoc update-map :id entity-id)
        keys-to-update (keys with-id)
        current-entity (entity {:id entity-id})
        ;; we want to filter out any keys that would add and remove the same value
        ;; since that's a no-op and also it can lead to writer loops
        valid-keys-to-update (filter (fn [cur-key]
                                       (or (= cur-key :id)
                                           (not= (get current-entity cur-key) (get update-map cur-key))))
                                     keys-to-update)
        current-values (select-keys current-entity valid-keys-to-update)]
    (when (seq current-values)
      (remove-facts! context current-values))
    (insert-facts! context (select-keys with-id valid-keys-to-update))))

(defn matching-names [partial-name]
  ;; TODO: how do we get the names?
  (let [all-names @entity-name-pairs]
    (seq (filter (fn [[entity name]]
                   (> (.indexOf name partial-name) -1))
                 all-names))))

(defn for-display [value]
  ;; check if this is an id that has a name
  (if-let [name (and value (:name (entity {:id value})))]
    name
    ;; otherwise just return the value
    (str value)))

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

(def colors {:grid-background "rgb(34,35,39)"
             :grid-lines "rgb(57,60,78)"

             :cell-background "rgb(34,35,39)"
             :cell-border ""
             :cell-copied-border "#acf"

             :subcell-background  "#363944"

             :selection-border "#acf"
             :selection-background "rgba(133, 146, 255, 0.17)"

             :results-background "#363944"

             :text "hsla(247,10%,80%,1)"
             :property-text "#6F729F"

             :autocomplete-adornment-color "#777"})

;;---------------------------------------------------------
;; Global dom stuff
;;---------------------------------------------------------

(defonce global-dom-state (atom {}))
(defonce measure-span (.createElement js/document "span"))

(defn prevent-default [event]
  (.preventDefault event))

(defn global-mouse-down []
  (@global-dom-state :mouse-down))

(defn global-dom-init []
  (set! (.-className measure-span) "measure-span")
  (-> (.-body js/document)
      (.appendChild measure-span))
  (.addEventListener js/window "mousedown"
                     (fn [event]
                       (swap! global-dom-state assoc :mouse-down true)))
  (.addEventListener js/window "mouseup"
                     (fn [event]
                       (swap! global-dom-state assoc :mouse-down false)))
  (.addEventListener js/window "keydown"
                     (fn [event]
                       (let [target-node-name (.-nodeName (.-target event))
                             ignore-names #{"INPUT", "TEXTAREA"}]
                         (when-not (ignore-names target-node-name)
                           (prevent-default event)))))
  (.addEventListener js/window "popstate"
                     (fn [event]
                       (reset! active-grid-id (get-grid-id-from-window))
                       (render))))

(defn focus-once [node elem]
  (when-not (.-focused node)
    (set! (.-focused node) true)
    (.focus node)))

(defn auto-focus [node elem]
  (.focus node))

(defn auto-focus-and-select [node elem]
  (auto-focus node elem)
  (.select node))

(defn set-font-properties [node]
  (let [computed-font-size (-> (.getComputedStyle js/window node nil)
                               (aget "font-size"))]
    (-> (.-style measure-span)
        (.-fontSize)
        (set! computed-font-size))))

(defn measure-size [value min-width]
  (set! (.-textContent measure-span) value)
  (let [measure-width (-> (.getBoundingClientRect measure-span)
                          (.-width))
        new-width (->> (max min-width measure-width)
                       (.ceil js/Math))]
    new-width))

(defn auto-size-input [node elem]
  (let [padding 40
        min-width 30
        _ (set-font-properties node)
        new-width (measure-size (.-value node) min-width)
        new-width-str (str (+ padding new-width) "px")]
    (set! (-> node .-style .-width) new-width-str)))

(defn auto-size-and-focus-once [node elem]
  (focus-once node elem)
  (auto-size-input node elem))

;;---------------------------------------------------------
;; Input parsing
;;---------------------------------------------------------

(defn parse-input [input]
  (let [cleaned (.trim input)
        num-parse (js/parseFloat input)
        [type value] (cond
                       (not (js/isNaN num-parse)) [:number num-parse]
                       (= "false" input) [:boolean false]
                       (= "true" input) [:boolean true]
                       :else [:text input])]
    {:type type
     :value value}))

;;---------------------------------------------------------
;; Autocomplete
;;---------------------------------------------------------

(defn autocomplete-selection-keys [event elem]
  (let [{:keys [cell id]} (.-info elem)
        key-code (.-keyCode event)
        {:keys [grid-id]} cell
        autocomplete-selection (get-state grid-id :autocomplete-selection 0)]
    (when (= key-code (KEYS :up))
      (transaction context
        (update-state! context grid-id :autocomplete-selection (dec autocomplete-selection))
        (.preventDefault event)))
    (when (= key-code (KEYS :down))
      (transaction context
        (update-state! context grid-id :autocomplete-selection (inc autocomplete-selection))
        (.preventDefault event)))))


(defn match-autocomplete-options [options value]
  (if (or (not value)
          (= value ""))
    options
    (filter (fn [opt]
              (> (.indexOf (.toLowerCase (:text opt)) value) -1))
            options)))

(defmulti get-autocompleter-options identity)


(defmethod get-autocompleter-options :value [_ value {:keys [cell grid-id]}]
  (let [property (or (get-state grid-id :intermediate-property) (:property cell))]
    (if (= "name" property)
      (when (and value (not= value ""))
        [{:text value :adornment "text" :action :value :value value}])
      (concat (when (and value (not= value ""))
                (concat
                  (if-let [matches (matching-names value)]
                    (for [[k v] matches]
                      {:text v :adornment "link" :action :link :value k}))
                  (if (string? value)
                    [{:text value :adornment "create" :action :create :value value}
                     {:text value :adornment "text" :action :value :value value}]
                    [{:text value :adornment (cond
                                               (number? value) "number"
                                               (or (true? value) (false? value)) "boolean")
                      :action :value :value value}]
                    )
                  ))
              (match-autocomplete-options [
                                           ; {:text "Code" :adornment "insert" :action :insert :value "code"}
                                           {:text "Table" :adornment "insert" :action :insert :value "table"}
                                           {:text "Query" :adornment "insert" :action :insert :value "formula-grid" :generate-grid {:tag "formula"}}
                                           ; {:text "Image" :adornment "insert" :action :insert :value "image"}
                                           ; {:text "Text" :adornment "insert" :action :insert :value "text"}
                                           ; {:text "Chart" :adornment "insert" :action :insert :value "chart"}
                                           ; {:text "Drawing" :adornment "insert" :action :insert :value "drawing"}
                                           ; {:text "UI" :adornment "insert" :action :insert :value "ui"}
                                           ]
                                          value)))))

(defmethod get-autocompleter-options :property [_ value]
  (when (and value (not= value ""))
    [{:text value :action :set-property :value value}]))

(defn get-tags []
  (.keys js/Object facts-by-tag))

(defn get-attributes [tag]
  (let [ents (entities {:tag tag})]
    (if (seq ents)
      (reduce (fn [attributes ent]
                (into attributes (map name (keys ent))))
              #{}
              ents)
      (into #{} (.keys js/Object attributes-index)))))

(defn get-functions []
  ["="
   "not="
   ">"
   "<"
   "+"
   "-"
   "/"
   "*"
   "sum"])

(def modifiers [{:text "without" :adornment "modifier" :action :assoc :value "without" :to-assoc {:token-type "not"}}
                {:text "select" :adornment "modifier" :action :assoc :value "select" :to-assoc {:token-type "select"}}
                {:text "draw" :adornment "modifier" :action :assoc :value "draw" :to-assoc {:token-type "draw"}}
                ])

(def ui-elems [{:text "div" :adornment "html" :action :assoc :value "div" :to-assoc {:token-type "html"}}])

(defmethod get-autocompleter-options :formula-token [_ typed-value info]
  (let [{:keys [current-cell nodes vars]} info
        {:keys [parent name value]} (nodes current-cell)
        parent-info (nodes parent)
        parent-type (:token-type parent-info)
        cleaned-value (if (and typed-value (not= typed-value ""))
                        typed-value)
        options (cond
                  ;; if we're a root, we're looking for tags, implications, references
                  ;; and functions
                  ;; @TODO: implications, functions, and references
                  ;; @TODO: get tags from a query instead of assuming I have them locally
                  (or (#{"not" "or" "and"} parent-type)
                      (= :root parent)) (let [tags (afor [tag (get-tags)]
                                                    {:text (for-display tag) :adornment "tag" :action :assoc :value tag :to-assoc {:token-type "tag"}})
                                         fns (for [func (get-functions)]
                                               {:text func :adornment "function" :action :assoc :value func :to-assoc {:token-type "function"}})
                                         refs (for [var vars]
                                                {:text (cljs.core/name var) :adornment "reference" :action :assoc :value (cljs.core/name var) :to-assoc {:token-type "reference"}})]
                                     (concat tags
                                             refs
                                             fns
                                             modifiers
                                             (when cleaned-value
                                               [{:text cleaned-value :adornment "variable" :action :assoc :value cleaned-value :to-assoc {:token-type "variable"}}])
                                     ))
                  ;; In the case of a select, there are only two options: "and" and "or"
                  (and parent-info (= parent-type "select")) [{:text "or" :adornment "modifier" :action :assoc :value "or" :to-assoc {:token-type "or"}}
                                                              {:text "and" :adornment "modifier" :action :assoc :value "and" :to-assoc {:token-type "and"}}]
                  ;; if our parent *is* a function, then we need to look for references and
                  ;; values
                  ;; @TODO: functions
                  (and parent-info (= (:token-type parent-info) "function")) (let [parsed (when cleaned-value
                                                                                            (parse-input cleaned-value))
                                                                                   refs (for [var vars]
                                                                                          {:text (cljs.core/name var) :adornment "reference" :action :assoc :value (cljs.core/name var) :to-assoc {:token-type "reference"}})]
                                                                               (concat
                                                                                 refs
                                                                                 (when cleaned-value
                                                                                   [{:text cleaned-value :adornment (cljs.core/name (:type parsed)) :action :assoc :value (:value parsed) :to-assoc {:token-type "value"}}])))
                  (and parent-info (= (:token-type parent-info) "draw")) (let []
                                                                           (concat
                                                                             ui-elems
                                                                             [{:text "repeat for" :adornment "modifier" :action :assoc :value "repeat for" :to-assoc {:token-type "repeat for"}}
                                                                            ]))
                  (and parent-info (= (:token-type parent-info) "repeat for")) (for [var vars]
                                                                                 {:text (cljs.core/name var) :adornment "reference" :action :assoc :value (cljs.core/name var) :to-assoc {:token-type "reference"}})
                  (and parent-info (= (:token-type parent-info) "html")) (let []
                                                                           (concat
                                                                             [{:text "text" :adornment "html" :action :assoc :value "text" :to-assoc {:token-type "html attribute"}}
                                                                              {:text "style" :adornment "html" :action :assoc :value "style" :to-assoc {:token-type "html attribute"}}]
                                                                             ui-elems))
                  (and parent-info (= (:token-type parent-info) "html attribute")) (let [parsed (when cleaned-value
                                                                                                  (parse-input cleaned-value))
                                                                                         refs (for [var vars]
                                                                                                {:text (cljs.core/name var) :adornment "reference" :action :assoc :value (cljs.core/name var) :to-assoc {:token-type "reference"}})]
                                                                                     (concat
                                                                                       refs
                                                                                       (when cleaned-value
                                                                                         [{:text cleaned-value :adornment (cljs.core/name (:type parsed)) :action :assoc :value (:value parsed) :to-assoc {:token-type "value"}}])))
                  ;; if we have a parent and it's not a function, we should scope ourselves
                  ;; to attributes on the parent, related tags, functions, and named entities
                  (and parent-info (not= (:token-type parent-info) "function")) (let [attribute-names (get-attributes (:value parent-info))
                                                                                attrs (for [attribute attribute-names]
                                                                                        {:text attribute :adornment "attribute" :action :assoc :value attribute :to-assoc {:token-type "attribute"}})
                                                                                fns (for [func (get-functions)]
                                                                                      {:text func :adornment "function" :action :assoc :value func :to-assoc {:token-type "function"}})]
                                                                            (concat attrs
                                                                                    fns
                                                                                    modifiers
                                                                                    (when (and cleaned-value (not (attribute-names cleaned-value)))
                                                                                      [{:text cleaned-value :adornment "attribute" :action :assoc :value cleaned-value :to-assoc {:token-type "attribute"}}])))
                  ;; @TODO: aggregate modifiers (grouping, uniques)
                  )
        final (if cleaned-value
                (match-autocomplete-options options typed-value)
                options)]
    final
    ))

(defn autocompleter-item [{:keys [type adornment selected] :as info}]
  (box :style (style :padding "7px 10px 7px 8px"
                     :background (if selected
                                   "#222"
                                   "none")
                     :white-space "nowrap"
                     :flex-direction "row")
    :children (array
                     (when adornment
                       (text :style (style :color (:autocomplete-adornment-color colors)
                                           :margin-right "5px")
                             :text adornment))
                     (text :style (style :color (or (:color info) (:text colors)))
                           :text (:text info))
                     )))

(defn autocompleter
  ([type value selected]
   (autocompleter type value selected nil))
  ([type value selected info]
  (let [options (get-autocompleter-options type value info)]
    (when options
      (let [with-selected (when (seq options)
                            (update-in (vec options) [(mod selected (count options))] assoc :selected true))
            items (to-array (map autocompleter-item with-selected))]
        (box :style (style :position "absolute"
                           :background "#000"
                           :margin-top "10px"
                           :left -1
                           :z-index 10
                           :min-width "100%"
                           :border "1px solid #555")
             :children items))))))

(defn get-selected-autocomplete-option [type value selected info]
  (when-let [options (get-autocompleter-options type value info)]
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
                   (let [cell-id (:cell-id cur)]
                     (if cell-id
                       (if-let [cell (entity {:id cell-id})]
                         (let [merged (merge cell cur)
                               active? (= (get-state grid-id :active-cell) cell-id)]
                           (if active?
                             (assoc merged :width (or (get-state grid-id :intermediate-width) (:width merged)))
                             merged))
                         cur)
                       cur))))
      (array {:id "fake-selection" :x 0 :y 0 :width 1 :height 1}))))

(defn get-active-cell [grid-id]
  (let [grid-user-state (entity {:tag "grid-user-state"
                                 :grid-id grid-id})]
    (if (:active-cell grid-user-state)
      (entity {:id (:active-cell grid-user-state)}))))

;;---------------------------------------------------------
;; Navigation
;;---------------------------------------------------------

(defn get-grid-id-from-window []
  (let [[_ _ grid-id] (-> js/window
                        (.-location)
                        (.-pathname)
                        (.split "/"))]
    (or grid-id "main")))

(defonce active-grid-id (atom (get-grid-id-from-window)))

(defn move-navigate-stack! [context dir]
  (let [history (.-history js/window)]
    (if (= dir :back)
      (.back history)
      (.forward history))))

(defn navigate! [context navigate-grid-id]
  (when-not (= navigate-grid-id @active-grid-id)
    (let [history (.-history js/window)]
      (.pushState history nil nil (str "/grid/" navigate-grid-id))
      (reset! active-grid-id navigate-grid-id))))

(defn navigate-event! [event elem]
  (let [{:keys [navigate-grid-id]} (.-info elem)]
    (transaction context
                 (navigate! context navigate-grid-id))))

;;---------------------------------------------------------
;; Cell types
;;---------------------------------------------------------

(defn cell-value->cell-size [cell value]
  (let [grid-width (if (= "formula-token" (:type cell))
                     (:width FORMULA-GRID-SIZE)
                     (:width GRID-SIZE))
        width (+ 15 (measure-size value (- 15 grid-width)))
        new-cell-width (max (.ceil js/Math (/ width grid-width))
                            (:width cell)
                            1)]
    new-cell-width))

(defn property-keys [event elem]
  (let [{:keys [cell id]} (.-info elem)
        key-code (.-keyCode event)
        {:keys [grid-id]} cell]
    (when (= key-code (KEYS :enter))
      (-> event (.-currentTarget) (.-parentNode)
          (.. (querySelector ".value") (focus)))
      (.preventDefault event))
      (let [intermediate-property (get-state grid-id :intermediate-property)
            selected (get-selected-autocomplete-option :property intermediate-property (get-state grid-id :autocomplete-selection 0) nil)]
        (when (not= (:text selected) intermediate-property)
          (transaction context
            (update-state! context id :intermediate-property (:text selected)))))
    (when (= key-code (KEYS :escape))
      (transaction context
                   (clear-intermediates! context grid-id)
                   (update-state! context grid-id :active-cell nil)))))

(defn value-keys [event elem]
  (let [{:keys [cell id autocompleter autocompleter-info]} (.-info elem)
        key-code (.-keyCode event)
        {:keys [grid-id]} cell
        shift? (.-shiftKey event)
        action (cond
                 (and shift? (= key-code (KEYS :enter))) :focus-property
                 (= key-code (KEYS :enter)) :submit
                 (= key-code (KEYS :tab)) :submit
                 (= key-code (KEYS :escape)) :escape
                 )]
    (when (= action :focus-property)
      ;;  In Excel, shift-enter is just like enter but in the upward direction, so if shift
      ;;  is pressed then we move back up to the property field.
      (-> event (.-currentTarget) (.-parentNode)
          (.. (querySelector ".property") (focus))))
    (when (= action :submit)
        ;; otherwise we submit this cell and move down
        (let [selected (get-selected-autocomplete-option (or autocompleter :value)
                                                         (or (get-state grid-id :intermediate-value)
                                                             (for-display (:value cell)))
                                                         (get-state grid-id :autocomplete-selection 0)
                                                         autocompleter-info)
              {:keys [action]} selected
              direction (cond
                          (and shift? (= key-code (KEYS :tab))) :left
                          (= key-code (KEYS :enter)) :down
                          (= key-code (KEYS :tab)) :right)
              new-width (max (get-state grid-id :intermediate-width 1)
                             (cell-value->cell-size cell (:text selected)))]
          (transaction context
                       (update-entity! context (:id cell) {:width new-width})
                       (cond
                         (= action :insert) (let [generate-grid (:generate-grid selected)
                                                  cell-update {:property (get-state grid-id :intermediate-property (:property cell))
                                                               :type (:value selected)}]
                                              (when generate-grid
                                                (insert-facts! context (assoc generate-grid :id 'generate-grid-id)))
                                              (update-entity! context (:id cell) (if generate-grid
                                                                                   (assoc cell-update :value 'generate-grid-id)
                                                                                   cell-update))
                                              (clear-intermediates! context grid-id))

                         (= action :assoc) (let [generate-grid (:generate-grid selected)
                                                 cell-update (merge {:value (:value selected)}
                                                                    (:to-assoc selected))]
                                             (when generate-grid
                                               (insert-facts! context (assoc generate-grid :id 'generate-grid-id)))
                                             ;; @TODO: this probably shouldn't be here
                                             (update-entity! context (:id cell) cell-update)
                                             (when (= (:type cell) "formula-token")
                                               ;; @FIXME: this assumes synchronous update, which may not be true at some
                                               ;; point and will lead to sadness
                                               (replace-and-send-query grid-id (formula-grid->query grid-id)))
                                             (clear-intermediates! context grid-id)
                                             (update-state! context grid-id :active-cell nil)
                                             (move-selection! context grid-id direction))
                         (or (= action :create)
                             (= action :link)
                             (= action :value)) (let [value-id (if (= action :create)
                                                                 'make-an-id
                                                                 (:value selected))
                                                      query-id (js/uuid)
                                                      property (get-state grid-id :intermediate-property (:property cell))]
                                                  (when (= action :create)
                                                    (insert-facts! context {:id value-id :name (:text selected)})
                                                    ;; Add an initial cell that contains the name we gave this grid
                                                    (add-cell! context value-id {:x 0 :y 0 :width 1 :height 1 :type "property" :property "name" :value (:text selected)}))
                                                  ;; if we have a property then we need to both update the cell
                                                  ;; and set a property on the grid
                                                  (when property
                                                    (update-entity! context (:id cell) {:property property
                                                                                        :value value-id})
                                                    (when (not= value-id (:value cell))
                                                      (insert-facts! context {:id grid-id
                                                                              (keyword property) value-id})
                                                      ;; if we had previously set a value on the grid then we need
                                                      ;; to remove it. We can check for this by the cell having a property
                                                      ;; and value already on it.
                                                      (when (and (:property cell) (not (nil? (:value cell))))
                                                        (remove-facts! context {:id grid-id
                                                                                (:property cell) (:value cell)}))))
                                                  ;; if there isn't a property that's being set, then the only thing we're
                                                  ;; doing here is setting the value of this cell and not setting an attribute
                                                  ;; on the grid.
                                                  (when-not property
                                                    (update-entity! context (:id cell) {:value value-id}))
                                                  ;; @TODO: this probably shouldn't be here
                                                  (when (= (:type cell) "formula-token")
                                                    ;; @FIXME: this assumes synchronous update, which may not be true at some
                                                    ;; point and will lead to sadness
                                                    (replace-and-send-query grid-id (formula-grid->query grid-id)))
                                                  (clear-intermediates! context grid-id)
                                                  (update-state! context grid-id :active-cell nil)
                                                  (move-selection! context grid-id direction))))))
    (when (= action :escape)
      (transaction context
                   (clear-intermediates! context grid-id)
                   (update-state! context grid-id :active-cell nil))))
  (autocomplete-selection-keys event elem))

(defn store-intermediate [event elem]
  (let [{:keys [cell field id parser]} (.-info elem)
        grid-id (:grid-id cell)
        value (or (.-value event) (-> (.-currentTarget event) (.-value)))
        value (if parser
                (:value (parser value))
                value)
        node (.-currentTarget event)
        _ (set-font-properties node)
        new-cell-width (cell-value->cell-size cell (.-value node))]
    (if (js/isNaN new-cell-width)
      (throw (js/Error. "WIDTH ENDED UP NAN...")))
    (transaction context
                 (update-state! context grid-id :intermediate-width new-cell-width)
                 (update-state! context grid-id :autocomplete-selection 0)
                 (update-state! context grid-id (keyword (str "intermediate-" (name field))) value))))

(defn track-focus [event elem]
  (let [{:keys [cell field id]} (.-info elem)]
    (transaction context
                 (update-state! context (:grid-id cell) :focus field))))

(defn draw-property [cell active? & [extra-style]]
  (if active?
    (input :style (style extra-style
                         :color (:property-text colors)
                         :font-size "10pt"
                         :line-height "10pt"
                         :margin "0px 0 0 8px")
           :postRender (if-not (:property cell)
                         focus-once
                         js/undefined)
           :c "property"
           :focus track-focus
           :input store-intermediate
           :keydown property-keys
           :info {:cell cell :field :property :id (:id cell)}
           :placeholder "property"
           :value (or (get-state (:grid-id cell) :intermediate-property) (:property cell)))
    (text :style (style extra-style
                        :color (:property-text colors)
                        :font-size "10pt"
                        :margin "0px 0 0 8px")
          :text (:property cell "property"))))

(defmulti draw-cell :type)

(defmethod draw-cell "property" [cell active?]
  (let [grid-id (:grid-id cell)
        current-focus (get-state grid-id :focus (if-not (:property cell)
                                                  :property
                                                  :value))
        property-element (draw-property cell active?)]
    (box :style (style :justify-content "center"
                       :max-height 50
                       :flex "1")
         :children
         (if active?
           (array property-element
                  (input :style (style :font-size "10pt"
                                       :color (:text colors)
                                       :margin "2px 0 0 8px")
                         :postRender (if (:property cell)
                                       auto-size-and-focus-once
                                       auto-size-input)
                         :focus track-focus
                         :input store-intermediate
                         :keydown value-keys
                         :c "value"
                         :info {:cell cell
                                :field :value
                                :id (:id cell)
                                :parser parse-input
                                :autocompleter-info {:cell cell
                                                     :grid-id grid-id}}
                         :placeholder "value"
                         :value (str (or (get-state grid-id :intermediate-value) (for-display (:value cell)))))
                  (if (= :property current-focus)
                    (autocompleter :property (or (get-state grid-id :intermediate-property) (:property cell) "")  (get-state grid-id :autocomplete-selection 0))
                    (autocompleter :value
                                   (or (get-state grid-id :intermediate-value)
                                       (for-display (:value cell)) "")
                                   (get-state grid-id :autocomplete-selection 0)
                                   {:cell cell
                                    :grid-id grid-id})))
           (array property-element
                  (button :style (style :font-size "10pt"
                                        :align-self "flex-start"
                                        :margin "2px 0 0 8px")
                          :info {:navigate-grid-id (:value cell)}
                          :click navigate-event!
                          :children (array (text :text (for-display (:value cell))))))))))

(defmethod draw-cell :default [cell active?]
  (let [grid-id (:grid-id cell)
        current-focus (get-state grid-id :focus (if-not (:property cell)
                                                  :property
                                                  :value))
        property-element (draw-property cell active?)]
    (box :children
         (if active?
           (array property-element
                  (input :style (style :font-size "10pt"
                                       :color (:text colors)
                                       :margin "0px 0 0 8px")
                         :postRender (if (:property cell)
                                       auto-size-and-focus-once
                                       auto-size-input)
                         :focus track-focus
                         :input store-intermediate
                         :keydown value-keys
                         :c "value"
                         :info {:cell cell :field :value :id (:id cell) :parser parse-input}
                         :placeholder "value"
                         :value (str (or  (get-state grid-id :intermediate-value) (for-display (:value cell)))))
                  (if (= :property current-focus)
                    (autocompleter :property (or (get-state grid-id :intermediate-property) (:property cell) "")  (get-state grid-id :autocomplete-selection 0))
                    (autocompleter :value
                                   (or (get-state grid-id :intermediate-value)
                                       (for-display (:value cell))
                                       "")
                                   (get-state grid-id :autocomplete-selection 0)
                                   {:cell cell
                                    :grid-id grid-id})))
           (array property-element
                  (text :style (style :font-size "10pt"
                                      :margin "3px 0 0 8px")
                        :text (:type cell)))))))

;;---------------------------------------------------------
;; Formula grid cell
;;---------------------------------------------------------

(declare grid)
(declare max-cell-xy)

(defmethod draw-cell "formula-grid" [cell active?]
  (let [grid-id (:grid-id cell)
        sub-grid-id (:value cell)
        grid-width (:width FORMULA-GRID-SIZE)
        grid-height (:height FORMULA-GRID-SIZE)
        current-focus (get-state grid-id :focus (if-not (:property cell)
                                                  :property
                                                  :value))
        property-element (draw-property cell active? (style :align-items "center"
                                                            :display "flex"
                                                            :flex "none"
                                                            :height (dec grid-height)))
        cells (entities {:tag "cell"
                         :grid-id sub-grid-id})
        max-cell-size (max-cell-xy cells)]
    (box :style (style :flex "1")
         :children
           (array property-element
                  (box :style (style :flex "none"
                                     :overflow "visible"
                                     :align-items "center")
                       :children (array (grid {:grid-width (+ 1 (* grid-width (:width cell) (/ (:width GRID-SIZE) grid-width)))
                                               :grid-height (+ 1 (* grid-height (- (:height cell) 0.5) (/ (:height GRID-SIZE) grid-height)))
                                               :selections (get-selections sub-grid-id)
                                               :cells cells
                                               :parent grid-id
                                               :default-cell-type "formula-token"
                                               :cell-size-y grid-height
                                               :cell-size-x grid-width
                                               :inactive (not active?)
                                               :id sub-grid-id})))
                  (let [results-id (@id-to-query sub-grid-id)
                        results (if results-id
                                  (find results-id)
                                  (array))
                        fields (if (seq results)
                                 (.keys js/Object (aget results 0))
                                 (array))
                        fields (.filter fields #(not= %1 "__id"))
                        rows (afor [row results]
                                   (box :style (style :flex-direction "row"
                                                      :flex "none"
                                                      :padding "5px 10px")
                                        :children (afor [field fields]
                                                        (box :style (style :width 100
                                                                           :flex "none")
                                                             :children (array (text :text (for-display (aget row field))))))))]
                    (box :style (style :flex "none")
                         :children (array (box :style (style :background (:results-background colors)
                                                             :flex "none"
                                                             :padding "5px 10px"
                                                             :margin-bottom "5px"
                                                             :flex-direction "row")
                                               :children (afor [field fields]
                                                               (box :style (style :width 100
                                                                                  :flex "none")
                                                                    :children (array (text :text field)))))
                                          (box :style (style :overflow "auto")
                                               :children rows))))
                  (when (and active? (= :property current-focus))
                    (autocompleter :property
                                   (or (get-state grid-id :intermediate-property) (:property cell) "")
                                   (get-state grid-id :autocomplete-selection 0)))))))

(declare formula-grid-info)

(defmethod draw-cell "formula-token" [cell active?]
  (let [grid-id (:grid-id cell)
        info (assoc (formula-grid-info grid-id) :current-cell (:id cell))
        current-info ((:nodes info) (:id cell))]
    (box :style (style :flex 1
                       :justify-content "center")
         :children
         (if active?
           (array
             (input :style (style :font-size "10pt"
                                  :color (:text colors)
                                  :margin-top -1 ;@FIXME why is this necessary to active/inactive to line up?
                                  :padding-left 8)
                    :postRender auto-size-and-focus-once
                    :focus track-focus
                    :input store-intermediate
                    :keydown value-keys
                    :c "value"
                    :info {:cell cell
                           :field :value
                           :autocompleter :formula-token
                           :autocompleter-info info
                           :id (:id cell)}
                    :placeholder "value"
                    :value (or  (get-state grid-id :intermediate-value) (for-display (:value cell))))
             (autocompleter :formula-token
                            (or (get-state grid-id :intermediate-value)
                                (for-display (:value cell))
                                "")
                            (get-state grid-id :autocomplete-selection 0)
                            info))
           (array
             (text :style (style :font-size "10pt"
                                 :padding-left 8)
                   :text (for-display (if (= "function" (:token-type current-info))
                                        (:value cell)
                                        (or (:variable current-info) (:value cell))))))))))

(defn get-projected-name [node-name parent-symbol vars]
  (let [simple (symbol node-name)
        used (set vars)]
    (if-not (used simple)
      simple
      (let [with-parent (if parent-symbol
                          (symbol (str parent-symbol "." node-name))
                          simple)]
        (if-not (used with-parent)
          with-parent
          (loop [ix 2]
            (let [with-parent-and-ix (symbol (str with-parent ix))]
            (if-not (used with-parent-and-ix)
              with-parent-and-ix
              (recur (inc ix))))))))))

(defn walk-graph [nodes node parent-symbol query]
  (let [node-info (nodes node)]
    (cond
      (or (= :root node)
          (= (:token-type node-info) "reference")
          (= (:token-type node-info) "variable")) (let [parent-sym (if (= :root node)
                                                                      nil
                                                                      (:variable node-info))]
                                                     (reduce (fn [query child]
                                                               (walk-graph nodes child parent-sym query))
                                                             query
                                                             (:children node-info)))
      (= (:token-type node-info) "attribute") (let [node-sym (:variable node-info)
                                                    query (conj query `(fact-btu ~parent-symbol
                                                                                 ~(-> (nodes node)
                                                                                      (:name))
                                                                                 ~node-sym))]
                                                (reduce (fn [query child]
                                                          (walk-graph nodes child node-sym query))
                                                        query
                                                        (:children node-info)))

      (= (:token-type node-info) "tag") (let [node-sym (or parent-symbol (:variable node-info))
                                              query (conj query `(fact-btu ~node-sym "tag" ~(:value node-info)))]
                                          (reduce (fn [query child]
                                                    (walk-graph nodes child node-sym query))
                                                  query
                                                  (:children node-info)))
      (= (:token-type node-info) "not") (let [children (reduce (fn [query child]
                                                                 (walk-graph nodes child parent-symbol query))
                                                               '[]
                                                               (:children node-info))]
                                          (if-not (seq children)
                                            query
                                            (conj query `(not ~@children))))
      (= (:token-type node-info) "select") (let [child-nodes (:children node-info)
                                                 children (reduce (fn [query child]
                                                                    (walk-graph nodes child parent-symbol query))
                                                                  '[]
                                                                  child-nodes)
                                                 type (if (-> (first child-nodes)
                                                              (nodes)
                                                              (:token-type)
                                                              (= "or"))
                                                        'choose
                                                        'union)]
                                             (if-not (seq children)
                                               query
                                               (conj query `(~type ~(:vars node-info) ~@children))))
      (or (= (:token-type node-info) "or")
          (= (:token-type node-info) "and")) (let [children (reduce (fn [query child]
                                                                 (walk-graph nodes child nil query))
                                                               []
                                                               (:children node-info))]
                                          (if-not (seq children)
                                            query
                                            (conj query `(query ~@children))))
      ;; @TODO: handle non-infix functions
      (= (:token-type node-info) "function") (let [node-sym (:variable node-info)
                                                   [child] (:children node-info)]
                                               (if-not child
                                                 query
                                                 (let [child-info (nodes child)
                                                       param (condp = (:token-type child-info)
                                                               "value" (:value child-info)
                                                               "reference" (:variable child-info)
                                                               "variable" (:variable child-info)
                                                               false)
                                                       func-name (:value node-info)
                                                       func-symbol (symbol func-name)]
                                                   (if-not param
                                                     query
                                                     (if (INFIX func-name)
                                                       (if (FILTERS func-name)
                                                         (conj query `(~func-symbol ~parent-symbol ~param))
                                                         (conj query `(= ~node-sym (~func-symbol ~parent-symbol ~param))))
                                                       (conj query `(= ~node-sym (~func-symbol ~param))))))))
      (= (:token-type node-info) "draw") (let [[repeat-for] (filter #(= (:token-type (nodes %)) "repeat for") (:children node-info))
                                               ui-projection (vec (map #(-> % (nodes) (:variable)) (:children (nodes repeat-for))))
                                               children (reduce (fn [query child]
                                                                  (if (not= (:token-type child) "repeat for")
                                                                    (walk-graph nodes child nil query)
                                                                    query))
                                                                '[]
                                                                (:children node-info))]
                                           (if-not (seq children)
                                             query
                                             (conj query `(ui ~ui-projection ~@children))))
      (= (:token-type node-info) "repeat for") query
      (= (:token-type node-info) "html") (let [{:keys [attributes child-elems]} (reduce (fn [info child]
                                                                                          (cond
                                                                                            (= (:token-type child) "html attribute") (let [value-node (-> (:children child)
                                                                                                                                                          (first)
                                                                                                                                                          (nodes))
                                                                                                                                           value (if (= (:token-type value-node) "reference")
                                                                                                                                                   (:variable value-node)
                                                                                                                                                   (:value value-node))]
                                                                                                                                       (update-in info [:attributes] conj (keyword (:value child)) value))
                                                                                            (= (:token-type child) "html") (assoc info :child-elems (-> (:child-elems info)
                                                                                                                                                        (concat (walk-graph nodes (:id child) nil []))))
                                                                                            :else info))
                                                                                        {:child-elems []
                                                                                         :attributes [(symbol (:value node-info))]}
                                                                                        (map nodes (:children node-info)))
                                               parent-node (nodes (:parent node-info))
                                               attributes (if (= (:token-type parent-node) "html")
                                                            (conj attributes :parent (symbol (str "node" (:id parent-node))))
                                                            attributes)
                                               attributes (if (seq child-elems)
                                                            (conj attributes :id (symbol (str "node" (:id node-info))))
                                                            attributes)
                                               with-me (conj query (seq attributes))]
                                           (if (seq child-elems)
                                             (concat with-me child-elems)
                                             with-me))

      ;; otherwise, just return the query exactly as it is now
      :else query
      )))

(defn formula-grid-info [id]
  (let [cells (entities {:tag "cell" :grid-id id})
        sorted (sort-by (juxt :y :x) cells)
        edges (reduce (fn [{:keys [nodes cols] :as info} cell]
                        (let [{:keys [value x id token-type width]} cell
                              child-name (when value
                                           (or (:name (entity {:id value})) value))
                              ;; your parent is whatever is the first thing to the left and up
                              ;; if there isn't anything there, then you must be a root
                              parent (or (cols (dec x))
                                         :root)
                              parent-info (nodes parent)
                              negated? (or (= "not" token-type) (:negated parent-info))
                              query-context (cond
                                              (= token-type "select") id
                                              (= parent :root) :root
                                              :else (:query-context parent-info))
                              query-context-info (nodes query-context)
                              query-context-vars-set (:vars-set query-context-info #{})
                              {:keys [vars vars-set] :as root} (nodes :root)
                              [is-var? variable] (cond
                                                   (or (= "tag" token-type)
                                                       (= "attribute" token-type)) [(not negated?) (get-projected-name child-name (-> nodes :parent :variable) vars)]
                                                   (and (= "function" token-type)
                                                        (not (FILTERS value))) [(not negated?) (get-projected-name "result" nil vars)]
                                                   (= "variable" token-type) [true (symbol value)]
                                                   (= "reference" token-type) [true (symbol value)]
                                                   :else [false nil])
                              cell-info {:id id
                                         :name child-name
                                         :variable variable
                                         :negated negated?
                                         :value value
                                         :token-type token-type
                                         :query-context query-context
                                         :parent parent
                                         :children []}
                              cell-info (if (= query-context id)
                                          (assoc cell-info :vars [] :vars-set #{})
                                          cell-info)
                              nodes (if (and is-var? (not (query-context-vars-set variable)))
                                      (if (not= query-context :root)
                                        (-> nodes
                                            (update-in [query-context :vars-set] conj variable)
                                            (update-in [query-context :vars] conj variable)
                                            (assoc :root (if (not (vars-set variable))
                                                           (-> root
                                                               (update-in [:vars-set] conj variable)
                                                               (update-in [:vars] conj variable))
                                                           root)))
                                        (-> nodes
                                            (update-in [query-context :vars-set] conj variable)
                                            (update-in [query-context :vars] conj variable)))
                                     nodes)
                              nodes (assoc nodes id cell-info)
                              nodes (update-in nodes [parent :children] conj id)
                              ;; set this cell as the parent for all the columns taken up by this cell
                              cols (reduce (fn [cols width-modifier]
                                             (assoc cols (+ width-modifier x) id))
                                           cols
                                           (range width))]
                          {:nodes nodes
                           :vars vars
                           :cols cols}))
                      {:nodes {:root {:children []
                                      :vars []
                                      :vars-set #{}}}
                       :cols {}}
                      sorted)]
    edges))

(defn formula-grid->query [id]
  (let [{:keys [nodes]} (formula-grid-info id)
        clauses (walk-graph nodes :root nil [])
        query (query-string `(query ~(-> (nodes :root)
                                         (:vars)
                                         vec)
                          ~@clauses))]
    (println "QUERY:\n" query)
    query
    ))

;;---------------------------------------------------------
;; Code cell
;;---------------------------------------------------------

(defn codemirror-keys [node key elem]
  (let [{:keys [cell id]} (.-info elem)
        {:keys [grid-id]} cell]
    ;;  In Excel, shift-enter is just like enter but in the upward direction, so if shift
    ;;  is pressed then we move back up to the property field.
    (when (= key :shift-enter)
        (-> node (.-parentNode)
            (.. (querySelector ".property") (focus))))
    (when (= key :enter)
      ;; otherwise we submit this cell and move down
      (transaction context
                   (let [value (get-state grid-id :intermediate-value (:value cell))]
                     (update-entity! context (:id cell) {:property (get-state grid-id :intermediate-property (:property cell))
                                                         :value value})
                     (replace-and-send-query (:id cell) value)
                     (clear-intermediates! context grid-id)
                     (update-state! context grid-id :active-cell nil)
                     (move-selection! context grid-id :down)))
      (.focus (.querySelector js/document (str ".keys-" grid-id))))
    (when (= key :escape)
      (transaction context
                   (clear-intermediates! context grid-id)
                   (update-state! context grid-id :active-cell nil)))))

(defn transfer-focus-to-codemirror [event elem]
  (.focus (.-_editor (.-currentTarget event))))

(defn codemirror-postrender [node elem]
  (let [{:keys [focused?]} (.-info elem)]
    (when-not (.-_editor node)
      (let [editor (new js/CodeMirror node
                        #js {:mode "clojure"
                             :extraKeys #js {:Shift-Enter (fn []
                                                            (codemirror-keys node :shift-enter elem))
                                             :Cmd-Enter (fn []
                                                          (codemirror-keys node :enter elem))
                                             :Esc (fn []
                                                    (codemirror-keys node :escape elem))
                                             }})]
        (.on editor "change" (fn []
                               (store-intermediate #js{:value (.getValue editor)} elem)))
        (.on editor "focus" (fn []
                              (set! (.-focused editor) true)))
        (.on editor "blur" (fn []
                              (set! (.-focused editor) false)))
        (.setValue editor (or (.-value elem) ""))
        (.clearHistory editor)
        (set! (.-_editor node) editor)
        (when focused?
          (.focus editor))))
    (let [editor (.-_editor node)]
      (when (and (not (.-focused editor)) focused?)
        (.focus editor))
      )))

(defmethod draw-cell "code" [cell active?]
  (let [grid-id (:grid-id cell)
        current-focus (get-state grid-id :focus (if-not (:property cell)
                                                  :property
                                                  :value))
        property-element (draw-property cell active?)]
    (box :style (style :flex "1")
         :children
           (array property-element
                  (elem :style (style :font-size "10pt"
                                      :color (:text colors)
                                      :flex "1 0"
                                      :background "none"
                                      :border "none"
                                      :margin "0px 0 0 8px")
                        :postRender codemirror-postrender
                        :focus transfer-focus-to-codemirror
                        :tabindex "-1"
                        ; :input store-intermediate
                        ; :keydown value-keys
                        :c (str "value" (if (and (= current-focus :value)
                                                 active?)
                                          " focused"
                                          ""))
                        :info {:cell cell :field :value :id (:id cell) :focused? (and active? (= current-focus :value))}
                        :placeholder "value"
                        :value (or  (get-state grid-id :intermediate-value) (for-display (:value cell))))
                  (when (@id-to-query (:id cell))
                    (when-let [results (find (@id-to-query (:id cell)))]
                      (let [fields (if (seq results)
                                     (.keys js/Object (aget results 0))
                                     (array))
                            fields (.filter fields #(not= %1 "__id"))
                            rows (afor [row results]
                                       (box :style (style :flex-direction "row"
                                                          :flex "none"
                                                          :padding "5px 10px")
                                            :children (afor [field fields]
                                                            (box :style (style :width 100
                                                                               :flex "none")
                                                                 :children (array (text :text (aget row field)))))))]
                        (box :style (style :flex "1 0")
                             :children (array (box :style (style :background "#333"
                                                                 :flex "none"
                                                                 :padding "5px 10px"
                                                                 :margin-bottom "5px"
                                                                 :flex-direction "row")
                                                   :children (afor [field fields]
                                                                   (box :style (style :width 100
                                                                                      :flex "none")
                                                                        :children (array (text :text field)))))
                                              (box :style (style :overflow "auto")
                                                   :children rows))))))
                  (when (and active? (= :property current-focus))
                    (autocompleter :property (or (get-state grid-id :intermediate-property) (:property cell) "")  (get-state grid-id :autocomplete-selection 0))))
  )))

;;---------------------------------------------------------
;; Grid
;;---------------------------------------------------------

(defn max-cell-xy [cells]
  (reduce (fn [cur cell]
            {:x (.max js/Math (:x cur) (+ (:width cell) (:x cell)))
             :y (.max js/Math (:y cur) (+ (:height cell) (:y cell)))})
          {:x 0 :y 0}
          cells))

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
    (set! (.-strokeStyle ctx) (:grid-lines colors))
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
  (let [with-id (assoc cell :tag "cell" :id 'new-guy :grid-id grid-id)]
    (insert-facts! context with-id)
    with-id))

(defn set-selection [event elem]
  (when-not (.-defaultPrevented event)
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
                 (update-state! context id :extending-selection true)
                 (clear-intermediates! context id)
                 (update-state! context id :active-cell nil)
                 (prevent-default event)
                 ))))

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
                       (update-state! context id :extending-selection false)
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
                     (update-state! context id :extending-selection false))))
    (transaction context
                 (update-state! context id :extending-selection false))))

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

(defn clip-position [grid-info pos]
  (let [{:keys [x y]} pos
        {:keys [grid-width grid-height cell-size-x cell-size-y]} grid-info
        max-x (dec (.ceil js/Math (/ (dec grid-width) cell-size-x)))
        max-y (dec (.ceil js/Math (/ (dec grid-height) cell-size-y)))
        clipped-x (-> x (max 0) (min max-x))
        clipped-y (-> y (max 0) (min max-y))
        clipped-dir (cond
                      (< clipped-x x) :right
                      (> clipped-x x) :left
                      (< clipped-y y) :bottom
                      (> clipped-y y) :top
                      :else nil)]
    {:clipped-pos (assoc pos :x clipped-x :y clipped-y)
     :clipped-dir clipped-dir
     :clipped? (boolean clipped-dir)}))

(defn move-selection! [context grid-id direction & [grid-info]]
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
          clipped (if grid-info
                    (clip-position grid-info resized-pos)
                    {})
          resized-pos (or (:clipped-pos clipped) resized-pos)
          maybe-selected-cell (get-intersecting-cell resized-pos cells)
          offset (if maybe-selected-cell
                   {:x (- (:x resized-pos) (:x maybe-selected-cell))
                    :y (- (:y resized-pos) (:y maybe-selected-cell))}
                   {:x 0 :y 0})
          final (if maybe-selected-cell
                  (-> (select-keys maybe-selected-cell [:x :y :width :height])
                      (assoc :cell-id (:id maybe-selected-cell)))
                  resized-pos)]
      (when offset
        (update-state! context grid-id :x-offset (:x offset))
        (update-state! context grid-id :y-offset (:y offset)))
      (update-state! context grid-id :extending-selection false)
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
    (update-state! context grid-id :x-offset 0)
    (update-state! context grid-id :y-offset 0)
    (update-state! context grid-id :extending-selection true)
    (doseq [selection selections]
      (remove-facts! context selection))
    (insert-facts! context (select-keys updated-pos [:tag :x :y :width :height :grid-id]))))

(def directions {(KEYS :left) :left
                 (KEYS :up) :up
                 (KEYS :right) :right
                 (KEYS :down) :down})

(defn activate-cell! [grid-id cell grid-info]
  (if-not (:cell-id cell)
    (transaction context
                 (let [new-cell (add-cell! context grid-id (assoc cell :type (:default-cell-type grid-info "property")))
                       selections (get-selections grid-id)]
                   (dotimes [ix (count selections)]
                     (let [selection (aget selections ix)]
                       (remove-facts! context selection)))
                   (update-state! context grid-id :active-cell (:id new-cell))
                   (insert-facts! context {:tag "selection" :grid-id grid-id :cell-id (:id new-cell)})))
    (transaction context
                 (let [prev-active (entity {:tag "active" :grid-id grid-id})]
                   (update-state! context grid-id :active-cell (:cell-id cell))))))

(defn delete-selections! [context grid-id selections]
  (let [grid-entity (or (entity {:id grid-id}) {})]
    (doseq [selection selections]
      (when (:cell-id selection)
        ;; remove the associated attribute-value from the containing grid, but
        ;; only if there actually is a attribute-value and if the grid has it as
        ;; an attribute
        (when (and (:property selection)
                   (not (nil? (:value selection)))
                   (not (nil? (grid-entity (keyword (:property selection))))))
          (remove-facts! context {:id grid-id
                                  (keyword (:property selection)) (:value selection)}))
        (remove-facts! context (entity {:id (:cell-id selection)}))
        ;; @TODO: this probably shouldn't be here either
        (when (= (:type selection) "formula-token")
          ;; @FIXME: this assumes synchronous update, which may not be true at some
          ;; point and will lead to sadness
          (replace-and-send-query grid-id (formula-grid->query grid-id))))
      (remove-facts! context selection))))

(defn grid-keys [event elem]
  (when (= (.-currentTarget event) (.-target event))
    (let [{:keys [id cells default-cell-type] :as grid-info} (.-info elem)
          grid-id id
          selections (get-selections id)
          current-selection (last selections)
          key-code (.-keyCode event)
          shift? (.-shiftKey event)
          modified? (or (.-metaKey event) (.-ctrlKey event))
          direction (directions key-code)]
      (condp = key-code
        (:tab KEYS) (transaction context
                                 (if shift?
                                   (move-selection! context id :left grid-info)
                                   (move-selection! context id :right grid-info))
                                 (.preventDefault event))
        (:escape KEYS) (when (:parent grid-info)
                         (transaction context
                                      (update-state! context (:parent grid-info) :active-cell nil)))
        ;; when pressing enter, we either need to create a new cell or if we have a currently
        ;; selected cell we need to activate it
        (:enter KEYS) (if modified?
                        ;; try navigating
                        (when (and (:value current-selection)
                                   (entity {:id (:value current-selection)}))
                          (transaction context
                                       (navigate! context (:value current-selection))))
                        (activate-cell! id current-selection grid-info))
        ;; pressing backspace should nuke any selected cells
        (:backspace KEYS) (transaction context
                                       (delete-selections! context grid-id selections)
                                       (insert-facts! context (select-keys current-selection [:tag :grid-id :x :y :width :height])))
        ;; otherwise if you pressed an arrow key, we need to move or extend depending on
        ;; whether shift is being held
        (when direction
          (transaction context
            (cond
              shift? (extend-selection! context id direction)
              (and (= direction :left) modified?) (move-navigate-stack! context :back)
              (and (= direction :right) modified?) (move-navigate-stack! context :forward)
              :else (move-selection! context id direction grid-info)))
          (.preventDefault event))))))

(defn find-top-left-cell [cells]
  (first (sort-by (juxt :x :y) cells)))

(defn move-cells-to-selection! [context cells-to-move selections & [copy-or-cut]]
  (let [old-top-left (find-top-left-cell cells-to-move)
        new-top-left (find-top-left-cell selections)
        grid-id (:grid-id new-top-left)
        offset-x (- (:x new-top-left) (:x old-top-left))
        offset-y (- (:y new-top-left) (:y old-top-left))]
    ;; @TODO: some cell types aren't allowed on some grids.
    ;; @TODO: we need to remove any potential collisions after doing the move
    ;; otherwise it's possible to overlap/hide cells under the ones being
    ;; moved
    (doseq [cell cells-to-move]
      (let [new-location {:x (+ offset-x (:x cell))
                          :y (+ offset-y (:y cell))
                          :grid-id grid-id}
            cell-id (if (= copy-or-cut :cut)
                      (:id cell)
                      (gensym "new-cell-id"))]
        (if (= copy-or-cut :cut)
          ;; if this is a cut, just put them at the new position
          (update-entity! context (:id cell) new-location)
          ;; if this is a copy, we remove the id and merge the new position in to
          ;; create a new cell at the location.
          ;; @TODO: copying a cell does more than just copy the cell, it's also
          ;; supposed to copy the associated facts if there are any.
          (do
            (insert-facts! context (merge (assoc cell :id cell-id)
                                          new-location))
            ))
        ;; @TODO: set the selection to the cell that was just pasted
        (insert-facts! context {:tag "selection"
                                :grid-id grid-id
                                :cell-id cell-id})))
    ;; in either a cut or copy, the current selection needs to be removed
    (delete-selections! context grid-id selections)))

(defn grid-input [event elem]
  (let [node (.-currentTarget event)
        node-value (.-value node)]
    (when (and (= node (.-target event))
               (not= node-value ""))
      (let [{:keys [id cells default-cell-type]} (.-info elem)
            grid-id id
            selections (get-selections id)
            current-selection (last selections)
            type (or (:type current-selection) default-cell-type)
            current-value (-> event (.-currentTarget) (.-value))
            my-paste? (= (get-state grid-id :last-copy) current-value)
            maybe-cells (filter identity (map #(entity {:id %}) (string/split current-value #" ")))]
        ;; make sure the content gets selected again so that is overwritten in the
        ;; case of a second paste
        (.select node)
        (println "MAYBE CELLS" maybe-cells)
        (if (seq maybe-cells)
          (transaction context
                       (update-state! context grid-id :last-copy-cell nil)
                       (move-cells-to-selection! context maybe-cells selections
                                                 (if my-paste?
                                                   (keyword (get-state grid-id :last-copy-type :copy))
                                                   :copy)))
          (condp = type
            ;; if you don't have an already existing cell selected, then we need
            ;; to create a new cell with a property name starting with whatever
            ;; you've typed at this point
            "property" (if-not (:cell-id current-selection)
                         (transaction context
                                      (let [new-cell (add-cell! context id (assoc current-selection :type default-cell-type))
                                            new-cell-id (:id new-cell)]
                                        (doseq [selection selections]
                                          (remove-facts! context selection))
                                        (insert-facts! context {:tag "selection" :grid-id grid-id :cell-id new-cell-id})
                                        (update-state! context grid-id :active-cell (:id new-cell))
                                        (update-state! context grid-id :intermediate-property current-value)))
                         ;; otherwise if you are on an already existing cell, we need to
                         ;; activate that cell, and set its value to what you've typed so far
                         (transaction context
                                      (update-state! context grid-id :active-cell (:cell-id current-selection))
                                      (update-state! context grid-id :intermediate-value current-value)))
            ;; in the case that you try to type on a formula-grid cell,
            ;; just activate the cell for now
            "formula-grid" (transaction context
                                        (update-state! context grid-id :active-cell (:cell-id current-selection)))
            ;; with formula tokens, the only thing you have is a value, so just starting setting
            ;; that and activate the cell
            "formula-token" (if-not (:cell-id current-selection)
                              (transaction context
                                           (let [new-cell (add-cell! context id (assoc current-selection :type default-cell-type))
                                                 new-cell-id (:id new-cell)]
                                             (doseq [selection selections]
                                               (remove-facts! context selection))
                                             (insert-facts! context {:tag "selection" :grid-id grid-id :cell-id new-cell-id})
                                             (update-state! context grid-id :active-cell (:id new-cell))
                                             (update-state! context grid-id :intermediate-value current-value)))
                              ;; otherwise if you are on an already existing cell, we need to
                              ;; activate that cell, and set its value to what you've typed so far
                              (transaction context
                                           (update-state! context grid-id :active-cell (:cell-id current-selection))
                                           (update-state! context grid-id :intermediate-value current-value)))
            ;; otherwise... just activate the cell?
            (transaction context
                         (update-state! context id :active-cell (:cell-id current-selection)))
            ))))))

(defn grid-keys-up [event elem]
  ;; check for shift key if we were expanding
  (let [{:keys [id]} (.-info elem)
        grid-user-state (entity {:tag "grid-user-state" :grid-id id})]
    (when (and (= (:shift KEYS) (.-keyCode event))
               (:extending-selection grid-user-state))
      (transaction context
        (stop-selecting event elem)
        (update-state! context id :extending-selection false)))))

(defn is-actively-copied? [grid-id cell-id]
  (let [current (get-state grid-id :last-copy-cell)]
    (if (set? current)
      (current cell-id)
      (= current cell-id))))

(defn grid-copy-or-cut [event elem]
  (let [copy-or-cut (.-type event)
        info (.-info elem)
        value (.-value elem)
        grid-id (:id info)]
    (transaction context
                 (update-state! context grid-id :last-copy-cell (->> (map :cell-id (:selections info))
                                                                     (filter identity)
                                                                     (into #{})))
                 (update-state! context grid-id :last-copy value)
                 (update-state! context grid-id :last-copy-type copy-or-cut))))

(defn grid-paste [event elem]
  ;; @TODO: Do we need to do anything specific to a paste? All of this
  ;; is currently handled by grid-input
  )

(defn start-resize [event elem]
  (.stopPropagation event))

(defn transfer-focus-to-keyhandler [event elem]
  (-> (.-currentTarget event)
      (.querySelector (str ".keys-" (:id (.-info elem))))
      (.focus)))

(defn maybe-activate-cell [event elem]
  (let [{:keys [id] :as grid-info} (.-info elem)
        selected (last (entities {:tag "selection"
                                  :grid-id id}))]
    (activate-cell! id selected grid-info)))

(defn grid [info]
  (let [canvas (elem :t "canvas"
                     :info info
                     :dragstart prevent-default
                     :postRender draw-grid
                     :style (style :width (:grid-width info)
                                   :height (:grid-height info)))
        children (array canvas)
        {:keys [cells cell-size-x cell-size-y selections]} info
        active-cell (get-active-cell (:id info))
        grid-id (:id info)]
    (dotimes [cell-ix (count cells)]
      (let [{:keys [x y width height color id] :as cell} (aget cells cell-ix)
            is-active? (= (:id active-cell) id)
            width (if-not is-active?
                    width
                    (get-state grid-id :intermediate-width width))
            copied? (is-actively-copied? grid-id id)
            color (if (:parent info)
                    (:subcell-background colors))
            border-color (if copied?
                           (:cell-copied-border colors))]
        (.push children (box :id id
                             :style (style :width (- (* cell-size-x (or width 1)) 2)
                                           :height (- (* cell-size-y (or height 1)) 2)
                                           :position "absolute"
                                           :top (+ 1 (* y cell-size-y))
                                           :left (+ 1 (* x cell-size-x))
                                           :border (if border-color
                                                     (str "1px dashed " border-color)
                                                     "1px solid transparent")
                                           :background (or color (:cell-background colors)))
                             :children (array (draw-cell cell is-active?))))))
    (when (and (not (:inactive info))
               (or (not active-cell)
                   (= (:type active-cell) "property")))
      (dotimes [selection-ix (count selections)]
        (let [selection (aget selections selection-ix)
              ;; we have to normalize selections since while they're being expanded
              ;; they can have negative widths and heights
              {:keys [x y width height]} (normalize-cell-size selection)]
          (.push children (box :style (style :width (- (* cell-size-x width) 0)
                                             :height (- (* cell-size-y height) 0)
                                             :position "absolute"
                                             :top (* y cell-size-y)
                                             :left (* x cell-size-x)
                                             :pointer-events "none"
                                             :background (if (not active-cell)
                                                           (:selection-background colors))
                                             :border (str "1px solid " (:selection-border colors)))
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
                                                                    :background "none"))))))))
    (.push children (elem :t "input"
                          :c (str "keyhandler keys-" (:id info))
                          :key (and (not active-cell)
                                    (not (:inactive info)))
                          :postRender (if (and (not active-cell)
                                               (not (:inactive info)))
                                        auto-focus-and-select
                                        js/undefined)
                          :keydown grid-keys
                          :keyup grid-keys-up
                          :input grid-input
                          :cut grid-copy-or-cut
                          :copy grid-copy-or-cut
                          :paste grid-paste
                          :info info
                          :value (string/join " " (filter identity (map :cell-id selections)))
                          :style (style
                                   ; :width 0
                                        ; :height 0
                                   :z-index -1
                                        :padding 0
                                   :top 0
                                   :left 0
                                        :pointer-events "none"
                                        ; :visibility "hidden"
                                        :position "absolute"
                                        :background "transparent"
                                        :color "transparent")))
    (elem :children children
          :c (str (:id info) " noselect " (when-not active-cell "focused"))
          :info info
          :tabindex -1
          :focus transfer-focus-to-keyhandler
          :mousedown set-selection
          :dblclick maybe-activate-cell
          :mousemove mousemove-extend-selection
          :mouseup stop-selecting
          :style (style :position "relative"
                        :width (:grid-width info)
                        :height (:grid-height info)
                        :background (:grid-background colors)
                        :pointer-events (if-not (:inactive info)
                                          "auto"
                                          "none")))))

;;---------------------------------------------------------
;; Root
;;---------------------------------------------------------

(defn root []
  ;; @FIXME: this is a little weird to say that the state for determining the active grid
  ;; resides on the default grid. It should really probably be global.
  (let [active-grid-id @active-grid-id]
    (box :style (style :width "100vw"
                       :height "100vh"
                       :align-items "center"
                       :justify-content "center"
                       :color (:text colors)
                       :font-family "Lato")
         :children (array (grid {:grid-width (.-innerWidth js/window)
                                 :grid-height (.-innerHeight js/window)
                                 :selections (get-selections active-grid-id)
                                 :cells (entities {:tag "cell"
                                                   :grid-id active-grid-id})
                                 :default-cell-type "property"
                                 :cell-size-y (:height GRID-SIZE)
                                 :cell-size-x (:width GRID-SIZE)
                                 :id active-grid-id})))))

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
          (println "RENDER")
          (.render @renderer #js [ui])
          (set! (.-queued @renderer) false))))))

;;---------------------------------------------------------
;; Init
;;---------------------------------------------------------

(defn init []
  (when (not @renderer)
    (reset! renderer (new js/Renderer))
    (.appendChild (.-body js/document) (.-content @renderer))
    (global-dom-init)
    (websocket-init))
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
