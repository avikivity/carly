(ns cassandra.batch
  (:use [clojure.java.shell :only [sh]])
  (:require [clojure [pprint :refer :all]
             [string :as str]
             [set :as set]]
            [carly.utility]
            [carly.setups]
            [clojure.java.io :as io]
            [clojure.tools.logging :refer [debug info warn]]
            [jepsen [core      :as jepsen]
             [codec     :as codec]
             [db        :as db]
             [util      :as util :refer [meh timeout]]
             [control   :as c :refer [| lit]]
             [client    :as client]
             [checker   :as checker]
             [model     :as model]
             [generator :as gen]
             [nemesis   :as nemesis]
             [store     :as store]
             [report    :as report]
             [tests     :as tests]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.control [net :as net]
             [util :as net/util]]
            [jepsen.os.debian :as debian]
            [knossos.core :as knossos]
            [jepsen.net :as Net]
            [clojurewerkz.cassaforte.client :as cassandra]
            [clojurewerkz.cassaforte.query :refer :all]
            [clojurewerkz.cassaforte.policies :refer :all]
            [clojurewerkz.cassaforte.cql :as cql]
            [cassandra.core :refer :all]
            [cassandra.checker :as extra-checker]
            [cassandra.conductors :as conductors])
  (:import (clojure.lang ExceptionInfo)
           (com.datastax.driver.core ConsistencyLevel)
           (com.datastax.driver.core.exceptions UnavailableException
                                                WriteTimeoutException
                                                ReadTimeoutException
                                                NoHostAvailableException)))

(defrecord BatchSetClient [conn]
  client/Client
  (setup! [_ test node]
    (locking setup-lock
      (let [conn (cassandra/connect (->> test :nodes (map name)))]
        (cql/create-keyspace conn "jepsen_keyspace"
                             (if-not-exists)
                             (with {:replication
                                    {:class "SimpleStrategy"
                                     :replication_factor 3}}))
        (cql/use-keyspace conn "jepsen_keyspace")
        (cql/create-table conn "a"
                          (if-not-exists)
                          (column-definitions {:id :int
                                               :value :int
                                               :primary-key [:id]})
                          (with {:compaction
                                 {:class (compaction-strategy)}}))
        (cql/create-table conn "b"
                          (if-not-exists)
                          (column-definitions {:id :int
                                               :value :int
                                               :primary-key [:id]})
                          (with {:compaction
                                 {:class (compaction-strategy)}}))
        (->BatchSetClient conn))))
  (invoke! [this test op]
    (case (:f op)
      :add (try (let [value (:value op)]
                  (with-consistency-level ConsistencyLevel/QUORUM
                    (cql/atomic-batch conn (queries
                                            (insert-query "a" {:id value
                                                               :value value})
                                            (insert-query "b" {:id (- value)
                                                               :value value})))))
                (assoc op :type :ok)
                (catch UnavailableException e
                  (assoc op :type :fail :value (.getMessage e)))
                (catch WriteTimeoutException e
                  (assoc op :type :info :value :timed-out))
                (catch NoHostAvailableException e
                  (info "All nodes are down - sleeping 2s")
                  (Thread/sleep 2000)
                  (assoc op :type :fail :value (.getMessage e))))
      :read (try (let [value-a (->> (with-retry-policy aggressive-read
                                      (with-consistency-level ConsistencyLevel/ALL
                                        (cql/select conn "a")))
                                    (map :value)
                                    (into (sorted-set)))
                       value-b (->> (with-retry-policy aggressive-read
                                      (with-consistency-level ConsistencyLevel/ALL
                                        (cql/select conn "b")))
                                    (map :value)
                                    (into (sorted-set)))]
                   (if-not (= value-a value-b)
                     (assoc op :type :fail :value [value-a value-b])
                     (assoc op :type :ok :value value-a)))
                 (catch UnavailableException e
                   (info "Not enough replicas - failing")
                   (assoc op :type :fail :value (.getMessage e)))
                 (catch ReadTimeoutException e
                   (assoc op :type :fail :value :timed-out))
                 (catch NoHostAvailableException e
                   (info "All nodes are down - sleeping 2s")
                   (Thread/sleep 2000)
                   (assoc op :type :fail :value (.getMessage e))))))
  (teardown! [_ _]
    (info "Tearing down client with conn" conn)
    (cassandra/disconnect! conn)))

(defn batch-set-client
  "A set implemented using batched inserts"
  []
  (->BatchSetClient nil))

(defn run-cassandra-stress
  "Run a cassandra-stress test as a sidekick background process"
  [test]
  (let [cass_log_name "cassandra-stress.log"
        cass_log_tmp  (str "/tmp/" cass_log_name)
        cass_log_store (.getCanonicalPath (store/path! test cass_log_name))
        shell-arguments [(:cassandra-stress-executable test) "write" "no-warmup" "duration=5m" "-rate" "threads=500" "-mode" "native" "cql3" "-node" (dns-resolve (first (:nodes test))) "-log" (str "file=" cass_log_tmp)]
        ]
    
    (info "running stress test:" shell-arguments)
    (apply sh shell-arguments)
    (info (str "Copying " cass_log_tmp " to " cass_log_store))
    (sh "cp" cass_log_tmp cass_log_store)))


(defn batch-set-test
  [name opts]
  (merge (cassandra-test (str "batch set " name)
                         {:client (batch-set-client)
                          :model (model/set)
                          :generator (gen/phases
                                      (->> (gen/clients (adds))
                                           (gen/delay 1)
                                           std-gen)
                                      (gen/delay 65
                                                 (read-once)))
                          })
         (merge-with merge {:conductors {:replayer (conductors/replayer)}} opts)))

;; iptables based tests
(defn bridge-test
  [opts]
  (batch-set-test "bridge"
           (merge {:conductors {:nemesis (nemesis/partitioner (comp nemesis/bridge shuffle))}}
                  opts)))

(defn halves-test
  [opts]
  (batch-set-test "halves"
           (merge {:conductors {:nemesis (nemesis/partition-random-halves)}}
                  opts)))

(defn isolate-node-test
  [opts]
  (batch-set-test "isolate node"
           (merge {:conductors {:nemesis (nemesis/partition-random-node)}}
                  opts)))

(defn crash-subset-test
  []
  (batch-set-test "crash"
                  {:conductors {:nemesis (crash-nemesis)}}))

(defn clock-drift-test
  []
  (batch-set-test "clock drift"
                  {:conductors {:nemesis (nemesis/clock-scrambler 10000)}}))

(defn flush-compact-test
  []
  (batch-set-test "flush and compact"
                  {:conductors {:nemesis (conductors/flush-and-compacter)}}))

(defn bridge-test-bootstrap
  [opts]
  (batch-set-test "bridge bootstrap"
          (merge  {:bootstrap (atom (carly.utility/node-subset 2))
                   :conductors {:nemesis (nemesis/partitioner (comp nemesis/bridge shuffle))
                                :bootstrapper (conductors/bootstrapper)}}
             opts)))

(defn halves-test-bootstrap
  [opts]
  (batch-set-test "halves bootstrap"
          (merge  {:bootstrap (atom (carly.utility/node-subset 2))
                   :conductors {:nemesis (nemesis/partition-random-halves)
                                :bootstrapper (conductors/bootstrapper)}}
             opts)))

(defn isolate-node-test-bootstrap
  [opts]
  (batch-set-test "isolate node bootstrap"
          (merge  {:bootstrap (atom (carly.utility/node-subset 2))
                   :conductors {:nemesis (nemesis/partition-random-node)
                                :bootstrapper (conductors/bootstrapper)}}
             opts)))

(defn crash-subset-test-bootstrap
  [opts]
  (batch-set-test "crash bootstrap"
          (merge  {:bootstrap (atom (carly.utility/node-subset 2))
                   :conductors {:nemesis (crash-nemesis)
                                :bootstrapper (conductors/bootstrapper)}}
             opts)))

(defn clock-drift-test-bootstrap
  []
  (batch-set-test "clock drift bootstrap"
                  {:bootstrap (atom (carly.utility/node-subset 2))
                   :conductors {:nemesis (nemesis/clock-scrambler 10000)
                                :bootstrapper (conductors/bootstrapper)}}))

(defn bridge-test-decommission
  [opts]
  (batch-set-test "bridge decommission"
           (merge {:conductors {:nemesis (nemesis/partitioner (comp nemesis/bridge shuffle))
                                :decommissioner (conductors/decommissioner)}}
                  opts)))

(defn halves-test-decommission
  [opts]
  (batch-set-test "halves decommission"
           (merge {:conductors {:nemesis (nemesis/partition-random-halves)
                                :decommissioner (conductors/decommissioner)}}
                  opts)))

(defn isolate-node-test-decommission
  [opts]
  (batch-set-test "isolate node decommission"
           (merge {:conductors {:nemesis (nemesis/partition-random-node)
                                :decommissioner (conductors/decommissioner)}}
                  opts)))

(defn crash-subset-test-decommission
  []
  (batch-set-test "crash decommission"
                  {:conductors {:nemesis (crash-nemesis)
                                :decommissioner (conductors/decommissioner)}}))

(defn clock-drift-test-decommission
  []
  (batch-set-test "clock drift decommission"
                  {:conductors {:nemesis (nemesis/clock-scrambler 10000)
                                :decommissioner (conductors/decommissioner)}}))

(defn crash-subset-test-bootstrap-stress
  []
  (let [my_test (crash-subset-test-bootstrap {:sidekick run-cassandra-stress})]
     (assoc my_test :name (str (:name my_test) " stress"))))

;; tc-slow-net based tests
(defn slow-net-test
  [test]
    (let [my_test (test {
                  :net Net/tc-slow-net
                  :sidekick run-cassandra-stress})]
      (assoc my_test :name (str (:name my_test) " slow network"))))


(defn bridge-test-slow-net
  []
  (slow-net-test bridge-test))

(defn halves-test-slow-net
  []
  (slow-net-test halves-test))

(defn isolate-node-test-slow-net
  []
  (slow-net-test isolate-node-test))

(defn bridge-test-bootstrap-slow-net
  []
  (slow-net-test bridge-test-bootstrap))

(defn halves-test-bootstrap-slow-net
  []
  (slow-net-test halves-test-bootstrap))

(defn isolate-node-test-bootstrap-slow-net
  []
  (slow-net-test isolate-node-test-bootstrap))

(defn bridge-test-decommission-slow-net
  []
  (slow-net-test bridge-test-decommission))

(defn halves-test-decommission-slow-net
  []
  (slow-net-test halves-test-decommission))

(defn isolate-node-test-decommission-slow-net
  []
  (slow-net-test isolate-node-test-decommission))

