(ns com.wsscode.pathom.connect.run-graph
  (:require [clojure.spec.alpha :as s]
            [edn-query-language.core :as eql]))

(def pc-sym :com.wsscode.pathom.connect/sym)
(def pc-attr :com.wsscode.pathom.connect/attribute)

(defn merge-io [a b]
  (com.wsscode.pathom.connect/merge-io a b))

(s/def ::node-id pos-int?)
(s/def ::root ::node-id)
(s/def ::run-next ::node-id)
(s/def ::run-and (s/coll-of ::node-id :kind vector?))
(s/def ::run-or (s/coll-of ::node-id :kind vector?))
(s/def ::nodes (s/map-of ::node-id (s/keys :req [::node-id])))
(s/def ::provides :com.wsscode.pathom.connect/io-map)
(s/def ::requires :com.wsscode.pathom.connect/io-map)
(s/def ::dead-keys (s/coll-of :com.wsscode.pathom.connect/attribute :kind set?))
(s/def ::available-data :com.wsscode.pathom.connect/io-map)
(s/def ::index-syms (s/map-of :com.wsscode.pathom.connect/sym (s/keys :req [::node-id])))

(defn next-node-id [{::keys [id-counter]}]
  (swap! id-counter inc))

(defn get-node [out node-id]
  (get-in out [::nodes node-id]))

(defn get-root-node [{::keys [root] :as out}]
  (get-node out root))

(defn compute-root-or [out env {::keys [node-id]}]
  (let [root-node (get-in out [::nodes (::root out)])]
    (cond
      (not root-node)
      (assoc out ::root node-id)

      (::run-or root-node)
      (update-in out [::nodes (::root out) ::run-or] conj node-id)

      :else
      (let [or-node-id (next-node-id env)
            or-node    {::node-id  or-node-id
                        ::requires (::requires (get-root-node out))
                        ::run-or   [(::root out) node-id]}]
        (-> out
            (assoc-in [::nodes or-node-id] or-node)
            (assoc ::root or-node-id))))))

(defn compute-root-and [out env {::keys [node-id]}]
  (let [root-node (get-in out [::nodes (::root out)])]
    (cond
      (not root-node)
      (assoc out ::root node-id)

      (::run-and root-node)
      (update-in out [::nodes (::root out) ::run-and] conj node-id)

      :else
      (let [or-node-id (next-node-id env)
            or-node    {::node-id  or-node-id
                        ::requires (merge-io
                                     (::requires (get-root-node out))
                                     (get-in out [::nodes node-id ::requires]))
                        ::run-and  [(::root out) node-id]}]
        (-> out
            (assoc-in [::nodes or-node-id] or-node)
            (assoc ::root or-node-id))))))

(defn create-sym-node
  [{::keys                           [run-next provides requires]
    :com.wsscode.pathom.connect/keys [attribute sym index-resolvers]
    :as                              env}]
  (let [sym-provides (get-in index-resolvers [sym :com.wsscode.pathom.connect/provides])
        node         (cond->
                       {pc-sym     sym
                        ::node-id  (next-node-id env)
                        ::requires (merge-io requires {attribute {}})
                        ::provides (merge-io provides sym-provides)}

                       run-next
                       (assoc ::run-next run-next))]
    node))

(defn apply-provides-requires-to-root [{::keys [root requires provides] :as out}]
  (update-in out [::nodes root] assoc
    ::provides provides
    ::requires requires))

(defn merge-dep-chain [out {::keys [root nodes]}]
  (-> out
      (assoc ::root root)
      (update ::nodes merge nodes)))

(defn include-node [out {::keys [node-id] :as node}]
  (-> out
      (assoc-in [::nodes node-id] node)))

(defn compute-run-graph*
  [{::keys                           [available-data index-syms run-next]
    ::eql/keys                       [query]
    :edn-query-language.ast/keys     [node]
    :com.wsscode.pathom.connect/keys [index-oir]
    :as                              env}]
  (-> (reduce
        (fn [out attr]
          (if (contains? index-oir attr)
            ; inputs loop
            (-> (reduce-kv
                  (fn [out inputs resolvers]
                    (let [missing inputs]
                      (as-> out <>

                        ; resolvers loop
                        (reduce
                          (fn [out resolver]
                            (if (::new-entry? out)
                              (if (contains? (::provides (get-root-node out)) attr)
                                (-> out
                                    (assoc-in [::nodes (::root out) ::requires attr] {}))

                                (let [node (create-sym-node (assoc env pc-sym resolver pc-attr attr))]
                                  (-> out
                                      (include-node node)
                                      (compute-root-and env node))))

                              (let [node (create-sym-node (assoc env pc-sym resolver pc-attr attr))]
                                (-> out
                                    (include-node node)
                                    (compute-root-or env node)))))
                          <>
                          resolvers)

                        ; missing loop
                        ((fn [out missing]
                           (if (seq missing)
                             (let [root-node (get-in out [::nodes (::root out)])
                                   graph     (compute-run-graph*
                                               (assoc env ::eql/query missing
                                                 ::run-next (::root out)
                                                 ::provides (::provides root-node)
                                                 ::requires (::requires root-node)))]
                               (clojure.pprint/pprint graph)
                               (merge-dep-chain out
                                 graph))
                             out))
                          <>
                          missing))))
                  out
                  (get index-oir attr))
                (assoc ::new-entry? true))
            out))
        {::nodes {}}
        query)
      (dissoc ::new-entry?)))

(defn compute-run-graph [{}]
  )
