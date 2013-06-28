(ns cascalog.parse
  (:require [clojure.string :refer (join)]
            [clojure.set :refer (difference intersection union subset?)]
            [clojure.walk :refer (postwalk)]
            [jackknife.core :refer (throw-illegal throw-runtime)]
            [jackknife.seq :as s]
            [cascalog.vars :as v]
            [cascalog.util :as u]
            [cascalog.zip :as zip]
            [clojure.zip :as czip]
            [cascalog.predicate :as p]
            [cascalog.options :as opts]
            [cascalog.fluent.types :refer (generator generator? map->ClojureFlow)]
            [cascalog.fluent.def :as d :refer (bufferop? aggregateop?)]
            [cascalog.fluent.fn :refer (search-for-var)]
            [cascalog.fluent.flow :refer (to-memory graph)]
            [cascalog.fluent.operations :as ops]
            [cascalog.fluent.tap :as tap]
            [cascalog.fluent.cascading :refer (uniquify-var)])
  (:import [jcascalog Predicate PredicateMacro PredicateMacroTemplate]
           [cascalog.predicate Operation FilterOperation Aggregator
            Generator GeneratorSet]
           [clojure.lang IPersistentVector]))

(defprotocol IRawPredicate
  (normalize [_]
    "Returns a sequence of RawPredicate instances."))

;; Raw Predicate type.

(defrecord RawPredicate [op input output]
  IRawPredicate
  (normalize [p] [p]))

;; ## Predicate Macro Building Functions

;; TODO: expand should return a vector of RawPredicate instances.
;;
;; "expand" is called from "normalize" in cascalog.parse. The parsing
;;  code takes care of the recursive expansion needed on the results
;;  of a call to "expand".

(defprotocol IPredMacro
  (expand [_ input output]
    "Returns a sequence of vectors suitable to feed into
    cascalog.parse/normalize."))

(extend-protocol p/ICouldFilter
  cascalog.parse.IPredMacro
  (filter? [_] true))

(extend-protocol IPredMacro
  ;; Predicate macro templates really should just extend this protocol
  ;; directly. getCompiledPredMacro calls into build-predmacro below
  ;; and returns a reified instance of IPredMacro.
  PredicateMacroTemplate
  (expand [p input output]
    ((.getCompiledPredMacro p) input output))

  ;; TODO: jCascalog shold just use these interfaces directly. If this
  ;; were the case, we wouldn't have to extend the protocol here.
  PredicateMacro
  (expand [p input output]
    (letfn [(to-fields [fields]
              (jcascalog.Fields. (or fields [])))]
      (-> p (.getPredicates (to-fields input)
                            (to-fields output))))))

(defn predmacro? [o]
  (satisfies? IPredMacro o))

;; kind of a hack, simulate using pred macros like filters

(defn use-as-filter?
  "If a predicate macro had a single output variable defined and you
  try to use it with no output variables, the predicate macro acts as
  a filter."
  [input-decl output-decl outvars]
  (and (empty? outvars)
       (sequential? input-decl)
       (= 1 (count output-decl))))

(defn predmacro*
  "Functional version of predmacro. See predmacro for details."
  [fun]
  (reify IPredMacro
    (expand [_ invars outvars]
      (fun invars outvars))))

(defmacro predmacro
  "A more general but more verbose way to create predicate macros.

   Creates a function that takes in [invars outvars] and returns a
   list of predicates. When making predicate macros this way, you must
   create intermediate variables with gen-nullable-var(s). This is
   because unlike the (<- [?a :> ?b] ...) way of doing pred macros,
   Cascalog doesn't have a declaration for the inputs/outputs.

   See https://github.com/nathanmarz/cascalog/wiki/Predicate-macros
  "
  [& body]
  `(predmacro* (fn ~@body)))

(defn validate-declarations!
  "Assert that the same variables aren't used on input and output when
  defining a predicate macro."
  [input-decl output-decl]
  (when (seq (intersection (set input-decl)
                           (set output-decl)))
    ;; TODO: ignore destructuring characters and check that no
    ;; constants are present.
    (throw-runtime (format
                    (str "Cannot declare the same var as "
                         "an input and output to predicate macro: %s %s")
                    input-decl output-decl))))

(defn build-predmacro
  "Build a predicate macro via input and output declarations. This
  function takes a sequence of declared inputs, a seq of declared
  outputs and a sequence of raw predicates. Upon use, any variable
  name not in the input or output declarations will be replaced with a
  random Cascalog variable (uniqued by appending a suffix, so nullable
  vs non-nullable will be maintained)."
  [input-decl output-decl raw-predicates]
  (validate-declarations! input-decl output-decl)
  (reify IPredMacro
    (expand [_ invars outvars]
      (let [outvars (if (use-as-filter? input-decl output-decl outvars)
                      [true]
                      outvars)
            replacement-m (u/mk-destructured-seq-map input-decl invars
                                                     output-decl outvars)
            update (memoize (fn [v]
                              (if (v/cascalog-var? v)
                                (replacement-m (str v) (uniquify-var v))
                                v)))]
        (mapcat (fn [pred]
                  (map (fn [{:keys [input output] :as p}]
                         (-> p
                             (assoc :input  (postwalk update input))
                             (assoc :output (postwalk update output))))
                       (normalize pred)))
                raw-predicates)))))

;; ## Variable Parsing

;; TODO: Note that this is the spot where we'd go ahead and add new
;; selectors to Cascalog. For example, say we wanted the ability to
;; pour the results of a query into a vector directly; :>> ?a. This is
;; the place.

;; TODO: validation on the arg-m. We shouldn't ever have the sugar arg
;; and the non-sugar arg. Move the examples out to tests.

(defn desugar-selectors
  "Accepts a map of cascalog input or output symbol (:< or :>, for
  example) to var sequence, a <sugary input or output selector> and a
  <full vector input or output selector> and either destructures the
  non-sugary input or moves the sugary input into its proper
  place. For example:

 (desugar-selectors {:>> ([\"?a\"])} :> :>>)
 ;=> {:>> [\"?a\"]}

 (desugar-selectors {:> [\"?a\"] :<< [[\"?b\"]]} :> :>> :< :<<)
 ;=>  {:>> [\"?a\"], :<< [\"?b\"]}"
  [arg-m & sugar-full-pairs]
  (letfn [(desugar [m [sugar-k full-k]]
            (if-not (some m #{sugar-k full-k})
              m
              (-> m
                  (dissoc sugar-k)
                  (assoc full-k
                    (or (first (m full-k))
                        (m sugar-k))))))]
    (reduce desugar arg-m
            (partition 2 sugar-full-pairs))))

(defn expand-positional-selector
  "Accepts a map of cascalog selector to var sequence and, if the map
  contains an entry for Cascalog's positional selector, expands out
  the proper number of logic vars and replaces each entry specified
  within the positional map. This function returns the updated map."
  [arg-m]
  (if-let [[var-count selector-map] (:#> arg-m)]
    (let [expanded-vars (reduce (fn [v [pos var]]
                                  (assoc v pos var))
                                (vec (v/gen-nullable-vars var-count))
                                selector-map)]
      (-> arg-m
          (dissoc :#>)
          (assoc :>> expanded-vars)))
    arg-m))

(defn unweave
  "[1 2 3 4 5 6] -> [[1 3 5] [2 4 6]]"
  [coll]
  [(take-nth 2 coll) (take-nth 2 (rest coll))])

(defn to-map
  "Accepts a sequence of alternating [single-k, many-values] pairs and
  returns a map of k -> vals."
  [k? elems]
  (let [[keys vals] (unweave (partition-by k? elems))]
    (zipmap (map first keys) vals)))

(defn parse-variables
  "parses variables of the form ['?a' '?b' :> '!!c'] and returns a map
   of input variables, output variables, If there is no :>, defaults
   to selector-default."
  [vars default-selector]
  {:pre [(contains? #{:> :<} default-selector)]}
  (let [vars (cond (v/selector? (first vars)) vars
                   (some v/selector? vars) (cons :< vars)
                   :else (cons default-selector vars))
        {input :<< output :>>} (-> (to-map v/selector? vars)
                                   (desugar-selectors :> :>>
                                                      :< :<<)
                                   (expand-positional-selector))]
    {:input  (v/sanitize input)
     :output (v/sanitize output)}))

(defn default-selector
  "Default selector (either input or output) for this
  operation. Dispatches based on type."
  [op]
  (if (p/filter? op) :< :>))

(defn split-outvar-constants
  "Accepts a sequence of output variables and returns a 2-vector:

  [new-outputs, [seq-of-new-raw-predicates]]

  By creating a new output predicate for every constant in the output
  field."
  [output]
  (reduce (fn [[new-output pred-acc] v]
            (if (v/cascalog-var? v)
              [(conj new-output v) pred-acc]
              (let [newvar (v/gen-nullable-var)]
                [(conj new-output newvar)
                 (conj pred-acc
                       (map->RawPredicate (if (or (fn? v) (u/multifn? v))
                                            {:op v :input [newvar]}
                                            {:op = :input [v newvar]})))])))
          [[] []]
          output))

(extend-protocol IRawPredicate
  Predicate
  (normalize [p]
    (normalize (into [] (.toRawCascalogPredicate p))))

  IPersistentVector
  (normalize [[op & rest]]
    (let [default (default-selector op)
          {:keys [input output]} (parse-variables rest (default-selector op))
          [output preds] (split-outvar-constants output)]
      (concat preds (if (predmacro? op)
                      (mapcat normalize (expand op input output))
                      [(->RawPredicate op
                                       (not-empty input)
                                       (not-empty output))])))))

;; ## Unground Var Validation

(defn unground-outvars
  "For the supplied sequence of RawPredicate instances, returns a seq
  of all ungrounding vars in the output position."
  [predicates]
  (mapcat (comp (partial filter v/unground-var?) :output)
          predicates))

(defn unground-assertions!
  "Performs various validations on the supplied set of parsed
  predicates. If all validations pass, returns the sequence
  unchanged."
  [gens ops]
  (let [gen-outvars (unground-outvars gens)
        extra-vars  (difference (set (unground-outvars ops))
                                (set gen-outvars))
        dups (s/duplicates gen-outvars)]
    (when (not-empty extra-vars)
      (throw-illegal (str "Ungrounding vars must originate within a generator. "
                          extra-vars
                          " violate(s) the rules.")))
    (when (not-empty dups)
      (throw-illegal (str "Each ungrounding var can only appear once per query."
                          "The following are duplicated: "
                          dups)))))

(defn aggregation-assertions! [buffers aggs options]
  (when (and (not-empty aggs)
             (not-empty buffers))
    (throw-illegal "Cannot use both aggregators and buffers in same grouping"))
  ;; TODO: Move this into the fluent builder?
  (when (and (empty? aggs) (empty? buffers) (:sort options))
    (throw-illegal "Cannot specify a sort when there are no aggregators"))
  (when (> (count buffers) 1)
    (throw-illegal "Multiple buffers aren't allowed in the same subquery.")))

;; Output of the subquery, the predicates it contains and the options
;; in the subquery.
;;
;; TODO: Have this thing extend IGenerator.
(defrecord RawSubquery [fields predicates options])

;; Printing Methods
;;
;; The following methods allow a predicate to print properly.

(defmethod print-method RawPredicate
  [{:keys [op input output]} ^java.io.Writer writer]
  (binding [*out* writer]
    (let [op (if (ifn? op)
               (let [op (or (:cascalog.fluent.def/op (meta op)) op)]
                 (or (search-for-var op)
                     op))
               op)]
      (print (str "(" op " "))
      (doseq [v (join " " input)]
        (print v))
      (when (not-empty output)
        (print " :> ")
        (doseq [v (join " " output)]
          (print v)))
      (println ")"))))

(defmethod print-method RawSubquery
  [{:keys [fields predicates options]} ^java.io.Writer writer]
  (binding [*out* writer]
    (println "(<-" (vec fields))
    (doseq [pred predicates]
      (print "    ")
      (print-method pred writer))
    (doseq [[k v] options :when (not (nil? v))]
      (println (format "    (%s %s)" k v)))
    (println "    )")))

(defn validate-predicates! [preds options]
  (let [grouped (group-by (fn [x]
                            (condp #(%1 %2) (:op x)
                              generator? :gens
                              bufferop? :buffers
                              aggregateop? :aggs
                              :ops))
                          preds)]
    (unground-assertions! (:gens grouped)
                          (:ops grouped))
    (aggregation-assertions! (:buffers grouped)
                             (:aggs grouped)
                             options)))

(defn query-signature?
  "Accepts the normalized return vector of a Cascalog form and returns
  true if the return vector is from a subquery, false otherwise. (A
  predicate macro would trigger false, for example.)"
  [vars]
  (not (some v/selector? vars)))

(comment
  (v/with-logic-vars
    (parse-subquery [?x ?y ?z]
                    [[[[1 2 3]] ?x]
                     [* ?x ?x :> ?y]
                     [* ?x ?y :> ?z]])))

;; ## Predicate Parsing
;;
;;
;; Before compilation, all predicates are normalized down to clojure
;; predicates.
;;
;; Query compilation steps are as follows:
;;
;; 1. Desugar all of the argument selectors (remember positional!)
;; 2. Normalize all predicates
;; 3. Expand predicate macros
;;
;; The result of this is a RawSubquery instance with RawPredicates
;; only inside.

(defn parse-subquery
  "Parses predicates and output fields and returns a proper subquery."
  [output-fields raw-predicates]
  (let [output-fields (v/sanitize output-fields)
        [raw-options raw-predicates] (s/separate opts/option? raw-predicates)
        option-map (opts/generate-option-map raw-options)
        raw-predicates (mapcat normalize raw-predicates)]
    (if (query-signature? output-fields)
      (do (validate-predicates! raw-predicates option-map)
          (->RawSubquery output-fields
                         raw-predicates
                         option-map))
      (let [parsed (parse-variables output-fields :<)]
        (build-predmacro (:input parsed)
                         (:output parsed)
                         raw-predicates)))))

(defmacro <-
  "Constructs a query or predicate macro from a list of
  predicates. Predicate macros support destructuring of the input and
  output variables."
  [outvars & predicates]
  `(v/with-logic-vars
     (parse-subquery ~outvars [~@(map vec predicates)])))

;; TODO: Implement IGenerator here.

;; this is the root of the tree, used to account for all variables as
;; they're built up.
(defrecord TailStruct [node ground? available-fields operations]
  zip/TreeNode
  (branch? [_] true)
  (children [_] [node])
  (make-node [_ children]
    (->TailStruct (first children) ground? available-fields operations)))

;; ExistenceNode is the same as a GeneratorSet, basically.
(defrecord ExistenceNode [source output-field]
  zip/TreeNode
  (branch? [_] true)
  (children [_] [source])
  (make-node [_ children]
    (->ExistenceNode (first children) output-field)))

;; For function applications.
(defrecord Application [source operation]
  zip/TreeNode
  (branch? [_] true)
  (children [_] [source])
  (make-node [_ children]
    (->Application (first children) operation)))

(defrecord Projection [source fields]
  zip/TreeNode
  (branch? [_] true)
  (children [_] [source])
  (make-node [_ children]
    (->Projection (first children) fields)))

;; For filters.
(defrecord FilterApplication [source filter]
  zip/TreeNode
  (branch? [_] true)
  (children [_] [source])
  (make-node [_ children]
    (->FilterApplication (first children) filter)))

;; Potentially add aggregations into the join. This node combines many
;; sources.
(defrecord Join [sources join-fields]
  zip/TreeNode
  (branch? [_] true)
  (children [_] sources)
  (make-node [_ children]
    (->Join children join-fields)))

;; Build one of these from many aggregators.
(defrecord Grouping [source aggregators grouping-fields options]
  zip/TreeNode
  (branch? [_] true)
  (children [_] [source])
  (make-node [_ children]
    (->Grouping (first children) aggregators grouping-fields options)))

(defn existence-field
  "Returns true if this location directly descends from an
  ExistenceNode, false otherwise. Short-circuits at any merge."
  [node]
  (loop [loc (zip/cascalog-zip node)]
    (if (czip/branch? loc)
      (let [node (czip/node loc)]
        (if (instance? ExistenceNode node)
          (:output-field node)
          (let [child-count (count (czip/children loc))]
            (if-not (> child-count 1)
              (and (czip/down loc)
                   (recur (czip/down loc))))))))))

(def existence-branch?
  (comp boolean existence-field))

;; ## Operation Application

(defn op-allowed?
  "An operation can be applied to a tail if all of the following
  conditions apply:

  - It only consumes fields that are available in the supplied
  TailStruct,

  - It's a filter (or the branch is NOT a GeneratorSet)

  - It only consumes ground variables (or the generator itself is
  ground)"
  [{:keys [ground? available-fields node]} op]
  (let [set-branch?   (existence-branch? node)
        available-set (set available-fields)
        infields-set  (set (filter v/cascalog-var? (:input op)))
        all-ground?   (every? v/ground-var? infields-set)]
    (and (or (instance? FilterOperation op)
             (not set-branch?))
         (subset? infields-set available-set)
         (or all-ground? ground?))))

(defprotocol IApplyToTail
  (accept? [this tail]
    "Returns true if this op can be applied to the current tail")

  (apply-to-tail [this tail]
    "Accepts a tail and performs some modification on that tail,
    returning a new tail."))


(defn apply-equality-ops
  "Accepts a TailStruct instance and a sequence of pairs of input
  variables, and applies an equality filter for every pair."
  [tail equality-pairs]
  (reduce (fn [tail equality-pair]
            (apply-to-tail tail (p/->FilterOperation = equality-pair)))
          tail
          equality-pairs))

(defn prepare-operation
  "When an operation produces fields that are already present in the
  tail, this is interpreted as an implicit filter against the existing
  values. This function accepts an operation and a TailStruct and
  returns a sequence of all pairs of output variable substitutions,
  plus a new operation with output fields swapped as necessary"
  [op tail]
  (let [duplicates (not-empty
                    (intersection (set (:output op))
                                  (set (:available-fields tail))))]
    (let [[eq-pairs output]
          (s/unweave (mapcat (fn [v]
                               (if-not (contains? duplicates v)
                                 [[] v]
                                 (let [uniqued (uniquify-var v)]
                                   [[v uniqued] uniqued])))
                             (:output op)))]
      [(filter not-empty eq-pairs)
       (assoc op :output output)])
    [[] op]))

(defn chain [tail f]
  (update-in tail [:node] f))

(extend-protocol IApplyToTail
  Object
  (accept? [_ tail] false)

  Operation
  (accept? [op tail] (op-allowed? tail op))
  (apply-to-tail [op tail]
    (let [[eq-pairs op] (prepare-operation op tail)]
      (-> tail
          (chain #(->Application % op))
          (update-in [:available-fields] #(concat % (:output op)))
          (apply-equality-ops eq-pairs))))

  FilterOperation
  (accept? [op tail] (op-allowed? tail op))
  (apply-to-tail [op tail]
    (chain tail #(->FilterApplication % op))))

(comment
  "TODO: Make a test."
  (let [good-op (p/->FilterOperation = [10 "?a"])
        bad-op  (p/->FilterOperation = [10 "?b"])
        node (-> [[1] [2]]
                 (->ExistenceNode "fuck")
                 (->Application (p/->Operation * "a" "b")))
        tail (map->TailStruct {:ground? true
                               :available-fields ["?a" "!z"]
                               :node node})]
    (prn (accept? good-op tail))
    (prn (accept? bad-op tail))))

(defn prefer-filter [op]
  (if (instance? FilterOperation op)
    -1 0))

(defn add-ops-fixed-point
  "Adds operations to tail until can't anymore. Returns new tail and
  any unapplied operations."
  [tail]
  (let [[candidates failed] (s/separate #(accept? % tail)
                                        (:operations tail))]
    (if-not (seq candidates)
      tail
      (let [[operation & remaining] (sort-by prefer-filter candidates)]
        (recur (apply-to-tail operation (assoc tail :operations
                                               (concat remaining failed))))))))

;; ## Join Field Detection

(defn tail-fields-intersection [& tails]
  (->> tails
       (map (comp set :available-fields))
       (apply intersection)))

(defn joinable?
  "Returns true if the supplied tail can be joined with the supplied
  join fields, false otherwise.

  A join works if the join fields are all available in the given tail
  AND the tail's either fully ground, or every non-join variable is
  unground."
  [tail joinfields]
  (let [join-set   (set joinfields)
        tailfields (set (:available-fields tail))]
    (and (subset? join-set tailfields)
         (or (:ground? tail)
             (every? v/unground-var?
                     (difference tailfields join-set))))))

(defn find-join-fields [l r]
  (let [join-set (tail-fields-intersection l r)]
    (if (and (joinable? l join-set)
             (joinable? r join-set))
      join-set
      [])))

(defn maximal-join
  "Returns the between the two generators with the largest
  intersection of joinable fields."
  [tail-seq]
  (let [join-fields (map (fn [[t1 t2]] (find-join-fields t1 t2))
                         (u/all-pairs tail-seq))]
    (apply max-key count join-fields)))

(defn select-join
  "Returns the join fields that will join the maximum number of fields
  at a time. If the search fails, select-join throws.

   This is unoptimal. It's better to rewrite this as a search problem
   to find optimal joins."
  [tails]
  (or (not-empty (maximal-join tails))
      (throw-illegal "Unable to join predicates together")))

(defn attempt-join
  "Attempt to reduce the supplied set of tails by joining."
  [tails]
  (let [max-join (select-join tails)
        [join-set remaining] (s/separate #(joinable? max-join %) tails)
        ;; All join fields survive from normal generators; from
        ;; generator-as-set generators, only the field we need to
        ;; filter gets through.
        available-fields (distinct
                          (mapcat (fn [tail]
                                    (or [(existence-field tail)]
                                        (:available-fields tail)))
                                  join-set))
        projected (map (comp #(->Projection % max-join) :node) join-set)
        join-node (->Join projected (vec max-join))
        new-ops (->> (map (comp set :operations) join-set)
                     (apply intersection))]
    (into remaining (->TailStruct join-node
                                  (s/some? :ground? join-set)
                                  available-fields
                                  (vec new-ops)))))

;; ## Aggregation Helpers

;; ## Aggregation Operations
;;
;; The following operations deal with Cascalog's aggregations. I think
;; we can replace all of this by delegating out to the GroupBy
;; implementation in operations.

(defn grouping-input
  "These are the operations that go into the aggregators."
  [aggs sort-fields]
  (->> aggs
       (map #(set (:input %)))
       (apply union (set sort-fields))
       (vec)))

(defn grouping-output
  "Returns the union of all grouping fields and all outputs for every
  aggregation field. These are the only fields available after the
  aggregation."
  [aggs grouping-fields]
  (->> aggs
       (map #(set (:output %)))
       (apply union (set grouping-fields))
       (vec)))

(defn validate-aggregation!
  "Makes sure that all fields are available for the aggregation."
  [tail aggs options]
  (let [required-input (grouping-input aggs (:sort options))]
    (when-let [missing-fields (seq
                               (difference (set required-input)
                                           (set (:available-fields tail))))]
      (throw-runtime "Can't apply all aggregators. These fields are missing: "
                     missing-fields))))

(defn unique-aggregator [fields options]
  #(->Grouping % [(p/->Aggregator (constantly (ops/unique-aggregator))
                                  fields
                                  fields)]
               fields
               options))

(defn build-agg-tail
  [tail aggs grouping-fields options]
  (if (empty? aggs)
    (if (:distinct options)
      (let [fields (:available-fields tail)]
        (chain tail (unique-aggregator fields options)))
      tail)
    (let [total-fields (grouping-output aggs grouping-fields)]
      (validate-aggregation! tail aggs options)
      (-> tail
          (chain #(->Projection % total-fields))
          (chain #(->Grouping % aggs grouping-fields options))
          (assoc :available-fields total-fields)))))

(defn merge-tails
  "The first call begins with a bunch of generator tails, each with a
   list of operations that could be applied. Based on the op-allowed
   logic, these tails try to consume as many operations as possible
   before giving up at a fixed point."
  [tails]
  (if (= 1 (count tails))
    (add-ops-fixed-point (assoc (first tails) :ground? true))
    (let [tails (map add-ops-fixed-point tails)]
      (recur (attempt-join tails)))))

(defn initial-tails
  "Builds up a sequence of tail structs from the supplied generators
  and operations."
  [generators operations]
  (->> generators
       (map (fn [gen]
              (let [node (if (instance? GeneratorSet gen)
                           (->ExistenceNode (:generator gen)
                                            (:join-set-var gen))
                           gen)]
                (->TailStruct node
                              (v/fully-ground? (:fields gen))
                              (:fields gen)
                              operations))))))

(defn validate-projection!
  [remaining-ops needed available]
  (when-not (empty? remaining-ops)
    (throw-runtime (str "Could not apply all operations: " (pr-str remaining-ops))))
  (let [want-set (set needed)
        have-set (set available)]
    (when-not (subset? want-set have-set)
      (let [inter (intersection have-set want-set)
            diff  (difference have-set want-set)]
        (throw-runtime (str "Only able to build to " (vec inter)
                            " but need " (vec needed)
                            ". Missing " (vec diff)))))))

(defn build-rule
  "TODO: Get options back into the mix."
  [{:keys [fields predicates options] :as input}]
  (let [grouped (->> predicates
                     (map p/build-predicate)
                     (group-by type))
        generators (concat (grouped Generator)
                           (grouped GeneratorSet))
        operations (concat (grouped Operation)
                           (grouped FilterOperation))
        aggs       (grouped Aggregator)
        tails      (initial-tails generators operations)
        joined     (merge-tails tails)
        grouping-fields (seq (intersection
                              (set (:available-fields joined))
                              (set fields)))
        agg-tail (build-agg-tail joined aggs grouping-fields options)
        {:keys [operations available-fields] :as tail} (add-ops-fixed-point agg-tail)]
    (validate-projection! operations fields available-fields)
    (chain tail #(->Projection % fields))))

;; ## Query Execution

(defprotocol IRunner
  (to-generator [item]))

(extend-protocol IRunner
  cascalog.predicate.Generator
  (to-generator [{:keys [source-map trap-map pipe fields]}]
    (-> (map->ClojureFlow {:source-map source-map
                           :trap-map trap-map
                           :pipe pipe})
        (ops/rename* fields)))

  Application
  (to-generator [{:keys [source operation]}]
    (let [{:keys [assembly input output]} operation]
      (assembly source input output)))

  FilterApplication
  (to-generator [{:keys [source filter]}]
    (let [{:keys [assembly input]} filter]
      (assembly source input)))

  Grouping
  (to-generator [{:keys [source aggregators grouping-fields options]}]
    (let [aggs (map (fn [{:keys [op input output]}]
                      (op input output))
                    aggregators)
          opts (->> options
                    (filter (comp (complement nil?) second))
                    (u/flatten))]
      (apply ops/group-by* source grouping-fields aggs opts)))

  Projection
  (to-generator [{:keys [source fields]}]
    (-> source
        (ops/select* fields)))

  TailStruct
  (to-generator [item]
    (:node item)))

(defn compile-query [query]
  (zip/postwalk-edit
   (zip/cascalog-zip (build-rule query))
   identity
   (fn [x _] (to-generator x))))

(comment
  (let [sq (<- [?squared ?squared-minus ?x ?sum]
               ([1 2 3] ?x)
               (* ?x ?x :> ?squared)
               (- ?squared 1 :> ?squared-minus)
               ((d/parallelagg* +) ?squared :> ?sum))]
    (to-memory (compile-query sq)))

  (time (run sq))

  (let [x (<- [?x ?y :> ?z]
              (* ?x ?x :> 10)
              (* ?x ?y :> ?z))
        sq (<- [?a ?b ?z]
               ([[1 2 3]] ?a)
               (x ?a ?a :> 4)
               ((d/bufferop* +) ?a :> ?z)
               ((cascalog.fluent.def/mapcatop* +) ?a 10 :> ?b))]
    (clojure.pprint/pprint (build-rule sq))))