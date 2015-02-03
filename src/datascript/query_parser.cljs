(ns datascript.query-parser
  (:require
    [datascript.pull-parser :as dp])
  (:require-macros
    [datascript :refer [raise]]))

;; find-spec        = ':find' (find-rel | find-coll | find-tuple | find-scalar)
;; find-rel         = find-elem+
;; find-coll        = [find-elem '...']
;; find-scalar      = find-elem '.'
;; find-tuple       = [find-elem+]
;; find-elem        = (variable | pull-expr | aggregate | custom-aggregate) 
;; pull-expr        = ['pull' src-var? variable pull-pattern] 
;; pull-pattern     = (constant | variable)
;; variable         = symbol starting with "?"
;; aggregate        = ([aggregate-fn-name fn-arg+] | ['aggregate' variable fn-arg+])
;; fn-arg           = (variable | constant | src-var)
;; src-var          = symbol starting with "$"
;; constant         = any non-variable data literal

(defprotocol IElements
  (-elements [this]))

(defprotocol IVars
  (-vars [this]))

(defrecord Variable [symbol]
  IVars (-vars [_] [symbol]))
(defrecord SrcVar [symbol]
  IVars (-vars [_] []))
(defrecord BuiltInAggr [symbol]
  IVars (-vars [_] []))
(defrecord Constant [value]
  IVars (-vars [_] []))
(defrecord Aggregate [fn args]
  IVars (-vars [_] (-vars (last args))))
(defrecord Pull [source var pattern]
  IVars (-vars [_] (-vars var)))

(defrecord FindRel    [elements]
  IElements (-elements [_] elements))
(defrecord FindColl   [element]
  IElements (-elements [_] [element]))
(defrecord FindScalar [element]
  IElements (-elements [_] [element]))
(defrecord FindTuple  [elements]
  IElements (-elements [_] elements))

(defn parse-seq [parse-el form]
  (when (sequential? form)
    (reduce #(if-let [parsed (parse-el %2)]
               (conj %1 parsed)
               (reduced nil))
            [] form)))

(defn parse-variable [form]
  (when (and (symbol? form)
             (= (first (name form)) "?"))
    (Variable. form)))

(defn parse-src-var [form]
  (when (and (symbol? form)
             (= (first (name form)) "$"))
    (SrcVar. form)))

(defn parse-constant [form]
  (when (not (symbol? form))
    (Constant. form)))

(defn parse-builtin [form]
  (when (and (symbol? form)
             (not (parse-variable form))
             (not (parse-src-var form)))
    (BuiltInAggr. form)))

(defn parse-fn-arg [form]
  (or (parse-variable form)
      (parse-constant form)
      (parse-src-var form)))

(defn parse-aggregate [form]
  (when
    (and (sequential? form)
         (>= (count form) 2))
    (let [[fn & args] form
          fn*   (parse-builtin fn)
          args* (parse-seq parse-fn-arg args)]
      (when (and fn* args*)
        (Aggregate. fn* args*)))))

(defn parse-aggregate-custom [form]
  (when (and (sequential? form)
             (= (first form) 'aggregate))
    (if (>= (count form) 3)
      (let [[_ fn & args] form
            fn*   (parse-variable fn)
            args* (parse-seq parse-fn-arg args)]
        (if (and fn* args*)
          (Aggregate. fn* args*)
          (raise "Cannot parse custom aggregate call, expect ['aggregate' variable fn-arg+]"
                 {:error :query/parser, :fragment form})))
      (raise "Cannot parse custom aggregate call, expect ['aggregate' variable fn-arg+]"
             {:error :query/parser, :fragment form}))))

(defn parse-pull-expr [form]
  (when (and (sequential? form)
             (= (first form) 'pull))
    (if (<= 3 (count form) 4)
      (let [long?         (= (count form) 4)
            src           (if long? (nth form 1) '$)
            [var pattern] (if long? (nnext form) (next form))
            src*          (parse-src-var src)                    
            var*          (parse-variable var)
            pattern*      (or (parse-variable pattern)
                              (parse-constant pattern))]
        (if (and src* var* pattern*)
          (Pull. src* var* pattern*)
          (raise "Cannot parse pull expression, expect ['pull' src-var? variable (constant | variable)]"
             {:error :query/parser, :fragment form})))
      (raise "Cannot parse pull expression, expect ['pull' src-var? variable (constant | variable)]"
             {:error :query/parser, :fragment form}))))

(defn parse-find-elem [form]
  (or (parse-variable form)
      (parse-pull-expr form)
      (parse-aggregate-custom form)
      (parse-aggregate form)))

(defn parse-find-rel [form]
  (some->
    (parse-seq parse-find-elem form)
    (FindRel.)))

(defn parse-find-coll [form]
  (when (and (sequential? form)
             (= (count form) 1))
    (let [inner (first form)]
      (when (and (sequential? inner)
                 (= (count inner) 2)
                 (= (second inner) '...))
        (some-> (parse-find-elem (first inner))
                (FindColl.))))))

(defn parse-find-scalar [form]
  (when (and (sequential? form)
             (= (count form) 2)
             (= (second form) '.))
    (some-> (parse-find-elem (first form))
            (FindScalar.))))

(defn parse-find-tuple [form]
  (when (and (sequential? form)
             (= (count form) 1))
    (let [inner (first form)]
      (some->
        (parse-seq parse-find-elem inner)
        (FindTuple.)))))

(defn parse-find [form]
  (or (parse-find-rel form)
      (parse-find-coll form)
      (parse-find-scalar form)
      (parse-find-tuple form)
      (raise "Cannot parse :find, expected: (find-rel | find-coll | find-tuple | find-scalar)"
             {:error :query/parser, :fragment form})))

(defn aggregate? [element]
  (instance? Aggregate element))

(defn pull? [element]
  (instance? Pull element))

(defn elements [find]
  (-elements find))

(defn vars [find]
  (mapcat -vars (-elements find)))
