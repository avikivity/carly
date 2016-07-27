(ns carly.hooks
  (:require [clojure.tools.logging :as logging]
            [carly.hacks :as hacks]
            [scylla.checkers]))

(def ^:private test-nodes (ref #{}))
(def ^:private ready-nodes (ref #{}))
(def ^:private all-nodes-ready (ref nil))

(add-watch ready-nodes
           :ready
           (fn [_ _ _ nodes]
             (when (= nodes @test-nodes)
               (logging/info "all nodes are ready")
               (deliver all-nodes-ready true))))

(defn signal-ready!
  [host]
  (dosync
    (alter ready-nodes conj (keyword host))))

(defn wait-for-all-nodes-ready
  []
  @@all-nodes-ready)

(defn start!
  [test]
  (dosync
    (let [nodes (->> test :nodes (map keyword) set)]
      (logging/info (str  "starting test '" (:name test) "'") "a.k.a" (hacks/testing-metadata-name))
      (logging/info "nodes for this test are" nodes)
      (logging/info "scylla service on nodes:" (scylla.checkers/scylla-server-status! (:nodes test)))
      (ref-set test-nodes nodes)
      (ref-set ready-nodes #{})
      (ref-set all-nodes-ready (promise)))))
