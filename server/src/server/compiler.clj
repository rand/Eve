
(ns server.compiler
  (:require
   [server.db :as db]
   [server.edb :as edb]
   [server.exec :as exec]
   [clojure.set :as set]))

(def initial-register 5)

;; cardinality changing operations should set a flag so we know
;; should also be able to retire registers that aren't being referenced
;; anymore...also add the unique flag for downedges that have already
;; been explicitly or implicitly deltad


;; environment should keep an identifier on the delta currently in effect
;; what about non-unique conditions?

(defn bset [e & key]
  (let [c (count key)]
    (if (= c 2)
      (swap! e assoc (first key) (second key))
      (swap! e assoc (first key) (apply bset (@e (first key)) (rest key))))))


(defn bget [e & key]
  (let [internal (fn internal [e key]
                   (let [k0 (first key)]
                     (cond
                       (= e nil) nil
                       (and (= (count key) 1) (= (type k0) clojure.lang.PersistentArrayMap)) (keys k0)
                       (= (count key) 1) (e k0)
                       :default (internal (e k0) (rest key)))))]
    (internal @e key)))


(defn partition-2 [pred coll]
  ((juxt
    (partial filter pred)
    (partial filter (complement pred)))
     coll))


(defn new-bindings [] (atom {}))

;; wrap a handler
(defn compile-error [& thingy]
  (apply println "compiler error" thingy)
  (throw thingy))

;; lookup happens in the emit stage, it returns either a constant value, or the register
;; which is currently holding that value
(defn lookup [f name]
  (if (or (symbol? name) (keyword? name))
    (bget f 'bound name)
    name))

(defn add-dependencies [e & n]
  (let [z (filter symbol? n)]
    (swap! e (fn [x] (assoc x 'dependencies (if-let [b (x 'dependencies)]
                                              (set/union b z)
                                              (set z)))))))

;; where vals is a map
(defn bind-names [e n]
  (swap! e (fn [x] (assoc x 'bound (if-let [b (x 'bound)] (merge b n) n)))))

;; this overflow register set probably isn't the best plan, but its
;; likely better than the 'overwrite the registers on startup with
;; a sufficiently large set
(defn allocate-register [e name]
  (let [bound (- exec/basic-register-frame 1)
        r (if-let [r (bget e 'register)] r initial-register)]
    (if (> r (- bound 1))
      (let [r (if-let [r (bget e 'overflow)] r 0)]
        (bind-names e {name [bound r]})
        (bset e 'overflow (+ r 1))
        [bound r])
      (do
        (bset e 'register (+ r 1))
        (bind-names e {name [r]})
        r))))

;; a generator is a null-adic function which spits out weasel using
;; the (possibly updated) environment that was captured at the
;; time it was compiled
(defn compose [& gens]
  (fn [] (apply concat (map (fn [x] (x)) gens))))

;; is lookup always the right thing here?
;; term is a fragment i guess, to shortcut some emits (?)
(defn term [e op & terms]
  (fn []
    (list (conj (map (fn [x] (lookup e x)) terms) op))))


(defn generate-send [e channel arguments]
  (let [out (gensym 'tuple)]
    (allocate-register e out)
    (apply add-dependencies e channel arguments)
    (let [cycle-filters (map (fn [x] (term e 'filter x))
                             (set/difference (bget e 'cycles)
                                             (bget e 'cycle-heads)))]
      (apply compose 
             (concat cycle-filters
                     (list (apply term e 'tuple out (map (fn [x] (bget e 'bound x)) arguments)))
                     (list (term e 'send channel out)))))))

(defn generate-bind [e inside-block block-name projection
                     target-block-name target-block-params] ;; send construction
  (let [ei (new-bindings)
        [bound free] (if projection
                       (partition-2 (fn [x] (is-bound? e (amap x))) projection)
                       ;; we dont have a handle on the free variables in the empty projection here
                       [#{(values (bget e 'bound))} #{}))
        tuple-target-name (gensym 'closure-tuple)
        input-map (zipmap inputs (range (count inputs)))
        body (compile-conjunction inner body
                                  (fn [] (generate-send target-block-name target-block-params)))
        tuple-names (reduce
                     (fn [b x]
                       (if (bget e 'bound x)
                         (do
                           (bind-names ei {x [input-register (count b)]})
                           (concat b (list x)))
                         b))
                     ()
                     (bget ei 'dependencies))]
    
    ;; limit dependencies to the projection
    (bset e 'dependencies (set/union (bget e 'dependencies) (bget ei 'dependencies)))
    (if-let [over (bget e 'overflow)]
      (compose body (term e 'tuple [(- exec/basic-register-frame 1)] (repeat over nil))))
    (bset e 'blocks (conj (bget e 'blocks  ('bind block-name tuple-target-name (body)))))
    tuple-names))


(defn compile-union [e terms down]
  (let [[union projection clauses] terms

        (apply compose
               (concat (map
                        (fn [x]
                          ;; new child environment
                          (generate-bind e down signature cid))))
               
  (compose )))
              
(declare compile-conjunction)

(defn compile-return [e terms down]
  (compose
   (generate-send e 'return-channel (if-let [k (second terms)] k ()))
   (down e)))

(defn compile-simple-primitive [e terms down]
  (let [argmap (apply hash-map (rest terms))
        simple [(argmap :return) (argmap :a) (argmap :b)]
        ins (map (fn [x] (bget e 'bound x)) simple)]
    (if (some not (rest ins))
      ;; handle the [b*] case by blowing out a temp
      (do
        (allocate-register e (first simple))
        (compose
         (apply term e (first terms) simple)
         (down e)))
      (compile-error (str "unhandled bound signature in" terms)))))
          

(defn generate-binary-filter [e terms down]
  (let [argmap (apply hash-map (rest terms))]
    (apply add-dependencies e terms)
    (compose 
     (term e (first terms) tmp-register (argmap :a) ( argmap :b))
     (term e 'filter tmp-register)
     (down e))))

;; really the same as lookup...fix
(defn is-bound? [e name]
  (if (or (symbol? name) (keyword? name))
    (bget e 'bound name)
    name))

(defn compile-equal [e terms down]
  (let [argmap (apply hash-map (rest terms))
        simple [(argmap :a) (argmap :b)]
        a (is-bound? e (argmap :a))
        b (is-bound? e (argmap :b))
        rebind (fn [s d]
                 (bind-names e {d s})
                 (down e))]
    (cond (and a b) (generate-binary-filter e terms down)
          a (rebind a (argmap :b))
          b (rebind b (argmap :a))
          :else
          (compile-error "reordering necessary, not implemented"))))
    

(defn indirect-bind [slot m]
  (zipmap (vals m) (map (fn [x] [slot x]) (keys m))))


(defn generate-scan [e terms down collapse]
  (let [signature [:entity :attribute :value :bag :tick :user]
        pmap (zipmap signature (range (count signature)))
        amap (apply hash-map (rest terms))
        ;; bound and free are in fact keyword space
        [bound free] (partition-2 (fn [x] (is-bound? e (amap x))) signature)
        [specoid index-inputs index-outputs] [edb/full-scan-oid () signature]
        filter-terms (set/intersection (set index-outputs) (set bound))
        target-reg-name (gensym 'target)
        target-reg (allocate-register e target-reg-name)
        body (reduce (fn [b t]
                       (fn [e]
                         (generate-binary-filter
                          e
                          (list '= :a [target-reg (pmap t)] :b (amap t))
                          b)))
                     down filter-terms)]
    
    ;; if we have flappies (= (amap free_i) nil) then we are no longer unique
    (bind-names e (indirect-bind target-reg (zipmap (map pmap free) (map amap free))))

    (if collapse
      (compose
       ;; needs to take a projection set for the indices
       (term e 'scan specoid tmp-register [])
       (term e 'delta-e target-reg-name tmp-register)
       (body e))
      (compose
       (term e 'scan specoid tmp-register [])
       (body e)))))



;; unification across the keyword-value bindings (?)
(defn compile-implication [e terms down]
  (let [relname (name (first terms))
        dekey (fn [x] (symbol (name x)))
        callmap (apply hash-map (rest terms))
        arms (atom ())
        ibinds (reduce-kv
                (fn [b k v] (if-let [v (lookup e v)] (assoc b k v) b))
                {} callmap)
        outputs (set/difference (set (keys callmap)) (set (keys ibinds)))
        army (fn [parameters body]
               (fn [down]
                 (let [internal (new-bindings)]
                   (bind-names internal (zipmap (map dekey (keys ibinds)) (vals ibinds)))
                   (compile-query
                   (compile-conjunction
                    internal body
                    (fn [tail]
                      (apply add-dependencies tail (bget internal 'dependencies))
                      ;; mapping out into the incorrect injunction of inner, e0 and eb
                      (bind-names tail (zipmap (map callmap outputs)
                                               (map (fn [x] (bget tail 'bound (dekey x))) outputs)))
                      (let [x (down tail)]
                        ;; fack
                        (bset e 'overflow (bget internal 'overflow))
                        x))))))]

    ;; validate the parameters as both a proper superset of the input
    ;; and conformant across the union legs
    (db/for-each-implication (bget e 'db) relname 
                             (fn [parameters body]
                               (swap! arms conj (army parameters body))))
    (generate-union e outputs @arms down)))


;; could probably collpase more with compile-edb given a clue
(defn compile-insert [env terms cont]
  (let [bindings (apply hash-map (rest terms))
        e (if-let [b (bindings :entity)] b nil)
        a (if-let [b (bindings :attribute)] b nil)
        v (if-let [b (bindings :value)] b nil)
        b (if-let [b (bindings :bag)] b [2]) ; default bag
        out (if-let [b (bindings :tick)] (let [r (allocate-register env (gensym 'insert-output))]
                                           (bind-names env {b [r 4]})
                                           [r]) [])]
    (compose
     (term env 'tuple tmp-register e a v b)
     (term env 'scan edb/insert-oid out tmp-register)
     (cont env))))



(defn compile-query [e terms cont]
  ;; this has a better formulation in the new world? what about export
  ;; of solution? what about its projection?
  ;; the bindings of the tail need to escape, but not the
  ;; control edge (cardinality)
  (let [body (rest (rest terms)) ;; smil - (if (vector? (second terms)) (rest terms) terms))
        out (compile-conjunction e body (fn [e] (fn [] ())))
        down (cont e)]
    (fn []
      ((compose 

        down)))))

(defn compile-expression [e terms down]
  (let [commands {'+ compile-simple-primitive
                  '* compile-simple-primitive
                  '/ compile-simple-primitive
                  '- compile-simple-primitive
                  '< generate-binary-filter
                  '> generate-binary-filter
                  'sort compile-simple-primitive ;; ascending and descending

                  'str compile-simple-primitive
                  'insert-fact-btu! compile-insert
                  'fact-btu (fn [e terms down]
                              (generate-scan e terms down true))
                  'full-fact-btu (fn [e terms down]
                                   (generate-scan e terms down true))
                  'range compile-simple-primitive
                  '= compile-equal
                  'not-equal generate-binary-filter
                  'union compile-union
                  'return compile-return
                  'query compile-query}
        relname (first terms)]
    (if-let [c (commands relname)]
      (c e terms down)
      (compile-implication e terms down))))

(defn compile-conjunction [e terms down]
  (if (empty? terms) (down e)
      (compile-expression e (first terms)
                          (fn [ed] (compile-conjunction ed (rest terms) down)))))


;; multiple kv, no deep keys
(defn bset2 [e & key]
  (when (not (empty? key))
    (bset e (first key) (second key))
    (apply bset2 e (rest (rest key)))))


(defn compile-dsl [d bid terms]
  (let [e (new-bindings)
        ;; side effecting
        z (bset2 e
                 'db d
                 'register initial-register
                 'bid bid
                 'default-bag [2]
                 'empty [])
        _ (bind-names e {'return-channel [1]
                         'op [0]})
        p (compile-conjunction e terms (fn [e] (fn [] ())))]
    ()))
    ;; emit blocks
    ;; wrap the main block

