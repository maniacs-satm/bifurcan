(ns bifurcan.benchmark-test
  (:require
   [proteus :refer [let-mutable]]
   [potemkin :as p :refer (doary doit)]
   [byte-streams :as bs]
   [clojure.test :refer :all]
   [clojure.test.check.generators :as gen]
   [bifurcan.test-utils :as u]
   [criterium.core :as c]
   [clojure.set :as set]
   [clojure.pprint :refer (pprint)]
   [clojure.java.shell :as sh]
   [clojure.java.io :as io])
  (:import
   [java.util.function
    ToIntFunction
    BiPredicate]
   [java.util.concurrent
    ThreadLocalRandom]
   [java.util
    Map$Entry
    HashMap
    HashSet
    ArrayList
    ArrayDeque
    Collection
    Iterator]
   [io.lacuna.bifurcan
    IntMap
    Map
    List
    IMap
    IList
    ISet
    Set
    ISplittable
    LinearList
    LinearMap
    LinearSet
    IMap$IEntry]))

(set! *warn-on-reflection* true)

(def clojure-hash
  (reify ToIntFunction
    (applyAsInt [_ k]
      (clojure.lang.Util/hasheq k))))

(def clojure-eq
  (reify BiPredicate
    (test [_ a b]
      (clojure.lang.Util/equiv a b))))

;;;

(defn construct-set [^ISet s vs]
  (let [s (.linear s)]
    (doary [v vs]
      (.add s v))
    s))

(defn construct-list [^IList l vs]
  (let [l (.linear l)]
    (doary [v vs]
      (.addLast l v))
    l))

(defn construct-map [^IMap m vs]
  (let [m (.linear m)]
    (doary [v vs]
      (.put m v nil))
    m))

(defn construct-int-map [^IntMap m vs]
  (let [m (.linear m)]
    (doary [v vs]
      (.put m (long v) nil))
    m))

(defn lookup-set [^ISet s vs]
  (doary [v vs]
    (.contains s v)))

(defn lookup-list [^IList l ks]
  (doary [k ks]
    (.nth l k)))

(defn lookup-map [^IMap m ks]
  (doary [k ks]
    (.get m k nil)))

(defn lookup-int-map [^IntMap m ks]
  (doary [k ks]
    (.get m (long k) nil)))

(defn map-union [^IMap a ^IMap b]
  (.union a b))

(defn set-union [^ISet a ^ISet b]
  (.union a b))

(defn iterator [^Iterable c]
  (.iterator c))

(defn map-intersection [^IMap a ^IMap b]
  (.intersection a b))

(defn set-intersection [^ISet a ^ISet b]
  (.intersection a b))

(defn map-difference [^IMap a ^IMap b]
  (.difference a b))

(defn set-difference [^ISet a ^ISet b]
  (.difference a b))

(defn split [^ISplittable c ^long parts]
  (.split c parts))

(defn consume-iterator [^Iterator it]
  (loop [x nil]
    (if (.hasNext it)
      (recur (or (.next it) x))
      x)))

(defn consume-entry-iterator [^Iterator it]
  (loop [x nil]
    (if (.hasNext it)
      (recur (or (.key ^IMap$IEntry (.next it)) x))
      x)))

(defn consume-java-entry-iterator [^Iterator it]
  (loop [x nil]
    (if (.hasNext it)
      (recur (or (.getKey ^Map$Entry (.next it)) x))
      x)))

;;;

(defn construct-java-list [^java.util.List l vs]
  (doary [v vs]
    (.add l v))
  l)

(defn construct-clojure-vector [v vs]
  (let-mutable [l (transient v)]
    (doary [v vs]
      (set! l (conj! l v)))
    (persistent! l)))

(defn concat-clojure-vectors [a b]
  (let-mutable [l (transient a)]
    (doit [x b]
      (set! l (conj! l x)))
    (persistent! l)))

(defn construct-hash-map [^HashMap m vs]
  (doary [v vs]
    (.put m v nil))
  m)

(defn union-hash-maps [^HashMap a ^HashMap b]
  (let [^HashMap a (.clone a)]
    (.putAll a b)
    a))

(defn diff-hash-maps [^HashMap a ^HashMap b]
  (let [^HashMap a (.clone a)]
    (.removeAll (.keySet a) (.keySet b))
    a))

(defn intersect-hash-maps [^HashMap a ^HashMap b]
  (let [^HashMap m (.clone a)]
    (doit [k (.keySet a)]
      (when-not (.containsKey b k)
        (.remove m k)))
    m))

(defn construct-clojure-map [m vs]
  (let-mutable [m (transient m)]
    (doary [v vs]
      (set! m (assoc! m v nil)))
    (persistent! m)))

(defn construct-clojure-set [s vs]
  (let-mutable [s (transient s)]
    (doary [v vs]
      (set! s (conj! s v)))
    (persistent! s)))

(defn lookup-hash-set [^HashSet s vs]
  (doary [v vs]
    (.contains ^HashSet s v)))

(defn lookup-clojure-set [s vs]
  (doary [v vs]
    (contains? s v)))

(defn lookup-java-list [^java.util.List l ks]
  (doary [k ks]
    (.get l k)))

(defn lookup-clojure-vector [v ks]
  (doary [k ks]
    (nth v k)))

(defn lookup-hash-map [^HashMap m ks]
  (doary [k ks]
    (.get m k)))

(defn lookup-clojure-map [m ks]
  (doary [k ks]
    (get m k)))

(defn construct-hash-set [^HashSet s vs]
  (doary [v vs]
    (.add s v))
  s)

(defn intersect-hash-sets [^HashSet a ^HashSet b]
  (let [^HashSet m (.clone a)]
    (doit [x b]
      (when-not (.contains a x)
        (.remove m x)))
    m))

;;;

;; a simple object that exists to provide minimal overhead within a hashmap
(deftype Obj [^int hash]
  Object
  (hashCode [_] (int hash))
  (equals [this o] (identical? this o)))

(defn generate-entries [n]
  (->> #(Obj. (rand-int Integer/MAX_VALUE)) (repeatedly n) into-array))

(defn generate-numbers [n]
  (->> n range shuffle into-array))

;;;

(defn base-collection [label class]
  {:label label
   :base (eval `(fn [] (new ~class)))})

(defn base-map [label class]
  (merge
    (base-collection label class)
    {:construct construct-map
     :entries generate-entries
     :lookup lookup-map
     :consume consume-entry-iterator
     :iterator iterator
     :union map-union
     :difference map-difference
     :intersection map-intersection
     :split split
     :add #(.put ^IMap %1 %2 nil)
     :remove #(.remove ^IMap %1 %2)}))

(defn base-set [label class]
  (merge
    (base-collection label class)
    {:construct construct-set
     :entries generate-entries
     :lookup lookup-set
     :consume consume-iterator
     :iterator iterator
     :union set-union
     :difference set-difference
     :intersection set-intersection
     :split split
     :add #(.add ^ISet %1 %2)
     :remove #(.remove ^ISet %1 %2)}))

(defn base-list [label class]
  (merge
    (base-collection label class)
    {:construct construct-list
     :entries generate-numbers
     :lookup lookup-list
     :consume consume-iterator
     :iterator iterator
     :concat #(.concat ^IList %1 %2)
     :split split}))

(def linear-map
  (merge
    (base-map "LinearMap" LinearMap)
    {:clone #(.clone ^LinearMap %)}))

(def bifurcan-map
  (merge
    (base-map "Map" Map)
    {:clone #(.clone ^Map %)}))

(def int-map
  (merge
    (base-map "IntMap" IntMap)
    {:construct construct-int-map
     :lookup lookup-int-map
     :entries generate-numbers
     :clone #(.clone ^IntMap %)}))

(def java-hash-map
  (let [o {:tag HashMap}]
    (merge (base-collection "java.util.HashMap" HashMap)
      {:entries generate-entries
       :construct construct-hash-map
       :lookup lookup-hash-map
       :clone #(.clone ^HashMap %)
       :iterator #(-> ^HashMap % .entrySet .iterator)
       :consume consume-java-entry-iterator
       :union union-hash-maps
       :difference diff-hash-maps
       :intersection intersect-hash-maps
       :add #(doto ^HashMap %1 (.put %2 nil))
       :remove #(doto ^HashMap %1 (.remove %2))})))

(def clojure-map
  {:label "clojure.lang.PersistentHashMap"
   :base (constantly {})
   :entries generate-entries
   :construct construct-clojure-map
   :lookup lookup-clojure-map
   :iterator iterator
   :consume consume-java-entry-iterator
   :union merge
   :difference #(apply dissoc %1 (keys %2))
   :intersection #(select-keys %1 (keys %2))
   :add #(assoc %1 %2 nil)
   :remove dissoc})

(def linear-set
  (merge
    (base-set "LinearSet" LinearSet)
    {:clone #(.clone ^LinearSet %)}))

(def bifurcan-set
  (merge
    (base-set "Set" Set)
    {:clone #(.clone ^Set %)}))

(def java-hash-set
  (merge (base-collection "java.util.HashSet" HashSet)
    {:construct construct-hash-set
     :lookup lookup-hash-set
     :entries generate-entries
     :iterator iterator
     :consume consume-iterator
     :clone #(.clone ^HashSet %)
     :union #(doto ^HashSet (.clone ^HashSet %1) (.addAll %2))
     :difference #(doto ^HashSet (.clone ^HashSet %1) (.removeAll %2))
     :intersection intersect-hash-sets
     :add #(doto ^HashSet %1 (.add %2))
     :remove #(doto ^HashSet %1 (.remove %2))}))

(def clojure-set
  {:label "clojure.lang.PersistentHashSet"
   :base (constantly #{})
   :construct construct-clojure-set
   :entries generate-entries
   :iterator iterator
   :consume consume-iterator
   :lookup lookup-clojure-set
   :union set/union
   :difference set/difference
   :intersection set/intersection
   :add conj
   :remove disj})

(def linear-list (base-list "LinearList" LinearList))

(def bifurcan-list (base-list "List" List))

(def java-array-list
  {:label "java.util.ArrayList"
   :base #(ArrayList.)
   :entries generate-numbers
   :construct construct-java-list
   :lookup lookup-java-list
   :iterator iterator
   :consume consume-iterator
   :clone #(.clone ^ArrayList %)
   :concat #(doto ^ArrayList (.clone ^ArrayList %) (.addAll %2))})

(def clojure-vector
  {:label "clojure.lang.PersistentVector"
   :base (constantly [])
   :entries generate-numbers
   :construct construct-clojure-vector
   :iterator iterator
   :consume consume-iterator
   :lookup lookup-clojure-vector
   :concat concat-clojure-vectors})

;;;

(def ^:dynamic *warmup* false)

(defn benchmark [n f]
  (-> (c/quick-benchmark* f
        (merge
          {:samples (long (max 30 (/ 80 (Math/log10 n))))
           :target-execution-time 5e7}
          (if *warmup*
            {:samples 6
             :warmup-jit-period 1e10
             :target-execution-time 1e9}
            {:warmup-jit-period 1e8})))
    :mean
    first
    (* 1e9)
    long))

(defn benchmark-construct [n {:keys [base entries construct]}]
  (let [s (entries n)]
    (benchmark n #(do (construct (base) s) nil))))

(defn benchmark-lookup [n {:keys [base entries construct lookup]}]
  (let [s (entries n)
        c (construct (base) s)
        s (-> s seq shuffle into-array)]
    (benchmark n #(lookup c s))))

(defn benchmark-clone [n {:keys [base entries construct clone]}]
  (let [c (construct (base) (entries n))]
    (benchmark n #(do (clone c) nil))))

(defn benchmark-iteration [n {:keys [base entries construct iterator consume] :as m}]
  (let [c (construct (base) (entries n))]
    (benchmark n #(consume (iterator c)))))

(defn benchmark-concat [n {:keys [base entries construct concat]}]
  (let [c (construct (base) (entries (/ n 2)))]
    (benchmark n #(do (-> (base) (concat c) (concat c)) nil))))

(defn benchmark-equals [n {:keys [label base entries construct add remove iterator]}]
  (let [n (long n)
        s (entries n)
        a (construct (base) s)
        b (construct (base) s)
        int-map? (= "IntMap" label)]
    (benchmark n
      #(let [e (aget ^objects s (rand-int n))
             e' (if int-map?
                  (long (rand-int Integer/MAX_VALUE))
                  (Obj. (rand-int Integer/MAX_VALUE)))
             b (-> b (remove e) (add e'))]
         (.equals ^Object a b)
         (-> b (remove e') (add e))
         nil))))

(defn benchmark-union [n {:keys [base entries construct union clone]}]
  (let [s-a (entries n)
        s-b (into-array
              (concat
                (->> s-a (take (/ n 2)) shuffle)
                (->> (entries (* n 1.5)) (drop n))))
        a (construct (base) s-a)
        b (construct (base) s-b)]
    (benchmark n #(do (union a b) nil))))

(defn benchmark-difference [n {:keys [base entries construct difference clone]}]
  (let [s-a (entries n)
        s-b (into-array
              (concat
                (->> s-a (take (/ n 2)) shuffle)
                (->> (entries (* n 1.5)) (drop n))))
        a (construct (base) s-a)
        b (construct (base) s-b)]
    (benchmark n #(do (difference a b) nil))))

(defn benchmark-intersection [n {:keys [base entries construct intersection clone]}]
  (let [s-a (entries n)
        s-b (into-array
              (concat
                (->> s-a (take (/ n 2)) shuffle)
                (->> (entries (* n 1.5)) (drop n))))
        a (construct (base) s-a)
        b (construct (base) s-b)]
    (benchmark n #(do (intersection a b) nil))))

;;;

(def maps [linear-map bifurcan-map java-hash-map clojure-map int-map])

(def sets [linear-set bifurcan-set java-hash-set clojure-set])

(def lists [linear-list bifurcan-list java-array-list clojure-vector])

(def all-colls (concat maps sets lists))

(def bench->types
  {:construct    [benchmark-construct
                  all-colls]
   :lookup       [benchmark-lookup
                  all-colls]
   :clone        [benchmark-clone
                  [linear-map java-hash-map linear-set java-hash-set]]
   :iteration    [benchmark-iteration
                  all-colls]
   :concat       [benchmark-concat
                  lists]
   :union        [benchmark-union
                  (concat maps sets)]
   :difference   [benchmark-difference
                  (concat maps sets)]
   :intersection [benchmark-intersection
                  (concat maps sets)]
   :equals       [benchmark-equals
                  (concat maps sets)]
   })

(defn run-benchmarks [n coll]
  (let [bench->types bench->types #_(select-keys bench->types [:iteration])]
    (println "benchmarking:" n)
    (->> bench->types
      (map (fn [[k [f colls]]] [k (when (-> colls set (contains? coll)) (f n coll))]))
      (into {}))))

(defn run-benchmark-suite [n log-step coll]
  (let [log10 (Math/log10 n)
        sizes (->> log10 (* log-step) inc (range log-step) (map #(Math/pow 10 (/ % log-step))) (map long))]
    (prn (:label coll) sizes)
    (println "warming up...")
    (binding [*warmup* true]
      (run-benchmarks 10 coll))
    (println "warmed up")
    (zipmap sizes (map #(run-benchmarks % coll) sizes))))

;;;

(defn extract-csv [coll->n->benchmark->nanos benchmark colls scale]
  (let [sizes          (-> coll->n->benchmark->nanos first val keys sort)
        coll->n->nanos (->> colls
                         (map :label)
                         (select-keys coll->n->benchmark->nanos)
                         (map (fn [[coll n->benchmark->nanos]]
                                [coll
                                 (zipmap
                                   sizes
                                   (->> sizes
                                     (map #(get n->benchmark->nanos %))
                                     (map #(get % benchmark))))]))
                         (into {}))]
    (apply str
      "size," (->> colls (map :label) (interpose ",") (apply str)) "\n"
      (->> sizes
        (map
          (fn [size]
            (->> colls
              (map :label)
              (map #(get-in coll->n->nanos [% size]))
              (map #(scale % size))
              (interpose ",")
              (apply str size ","))))
        (#(interleave % (repeat "\n")))
        (apply str)))))

(def benchmark-csv
  {"clone" [:clone [linear-map linear-set java-hash-map java-hash-set]]

   "map_construct" [:construct maps]
   "list_construct" [:construct lists]
   "set_construct" [:construct sets]

   "map_lookup" [:lookup maps]
   "list_lookup" [:lookup lists]
   "set_lookup" [:lookup sets]

   "map_iterate" [:iteration maps]
   "list_iterate" [:iteration lists]
   "set_iterate" [:iteration sets]

   "concat" [:concat lists]

   "map_union" [:union maps]
   "set_union" [:union sets]

   "map_difference" [:difference maps]
   "set_difference" [:difference sets]

   "map_intersection" [:intersection maps]
   "set_intersection" [:intersection sets]

   "map_equals" [:equals maps]
   "set_equals" [:equals sets]

   })

(defn write-out-csvs [descriptor]
  (doseq [[file [benchmark colls]] benchmark-csv]
    (spit (str "benchmarks/data/" file ".csv")
      (extract-csv descriptor benchmark colls
        (fn [x n] (when x (float (/ x n))))))))

;;;

(defn benchmark-collection [n step idx]
  (-> (sh/sh "sh" "-c"
        (str "lein run -m bifurcan.benchmark-test benchmark-collection " n " " step " " idx))
    :out
    bs/to-line-seq
    last
    read-string))

(defn -main [task & args]
  (case task
    "benchmark-collection"
    (let [[n step idx] args]
      (prn
        (run-benchmark-suite
          (read-string n)
          (read-string step)
          (nth all-colls (read-string idx)))))

    "benchmark"
    (let [[n step] args
          descriptor (->> (range (count all-colls))
                       (map (fn [idx]
                              (let [coll (-> all-colls (nth idx) :label)]
                                (println "benchmarking" coll)
                                [coll (benchmark-collection n step idx)])))
                       (into {}))]
      (spit "benchmarks/benchmarks.edn" (pr-str descriptor))
      (write-out-csvs descriptor)))

  (flush)
  (Thread/sleep 100)
  (System/exit 0))
