(ns cassandra.core
  (:require [clojure [pprint :refer :all]
             [string :as str]]
            [clojure.java.io :as io]
            [clojure.java.jmx :as jmx]
            [clojure.set :as set]
            [clojure.tools.logging :refer [debug info warn]]
            [jepsen [core      :as jepsen]
             [os]
             [db        :as db]
             [util      :as util :refer [meh timeout]]
             [control   :as control :refer [| lit]]
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
            [knossos.core :as knossos]
            [clojurewerkz.cassaforte.client :as cassandra]
            [clojurewerkz.cassaforte.query :refer :all]
            [clojurewerkz.cassaforte.policies :refer :all]
            [clojurewerkz.cassaforte.cql :as cql])
  (:import (clojure.lang ExceptionInfo)
           (com.datastax.driver.core ConsistencyLevel)
           (com.datastax.driver.core.policies RetryPolicy
                                              RetryPolicy$RetryDecision)
           (java.net InetAddress)))

(defn scaled
  "Applies a scaling factor to a number - used for durations
  throughout testing to easily scale the run time of the whole
  test suite. Accepts doubles."
  [v]
  (let [factor (or (some-> (System/getenv "JEPSEN_SCALE") (Double/parseDouble))
                   1)]
    (Math/ceil (* v factor))))

(defn compaction-strategy
  "Returns the compaction strategy to use"
  []
  (or (System/getenv "JEPSEN_COMPACTION_STRATEGY")
      "SizeTieredCompactionStrategy"))

(defn compressed-commitlog?
  "Returns whether to use commitlog compression"
  []
  (= (some-> (System/getenv "JEPSEN_COMMITLOG_COMPRESSION") (clojure.string/lower-case))
     "false"))

(defn coordinator-batchlog-disabled?
  "Returns whether to disable the coordinator batchlog for MV"
  []
  (boolean (System/getenv "JEPSEN_DISABLE_COORDINATOR_BATCHLOG")))

(defn phi-level
  "Returns the value to use for phi in the failure detector"
  []
  (or (System/getenv "JEPSEN_PHI_VALUE")
      8))

(defn disable-hints?
  "Returns true if Jepsen tests should run without hints"
  []
  (not (System/getenv "JEPSEN_DISABLE_HINTS")))

(defn wait-for-recovery
  "Waits for the driver to report all nodes are up"
  [timeout-secs conn]
  (timeout (* 1000 timeout-secs)
           (throw (RuntimeException.
                   (str "Driver didn't report all nodes were up in "
                        timeout-secs "s - failing")))
           (while (->> (cassandra/get-hosts conn)
                       (map :is-up) and not)
             (Thread/sleep 500))))

(defn dns-resolve
  "Gets the address of a hostname"
  [hostname]
  (.getHostAddress (InetAddress/getByName (name hostname))))

(defn live-nodes
  "Get the list of live nodes from a random node in the cluster"
  [test]
  (set (some (fn [node]
               (try (jmx/with-connection {:host (name node) :port 7199}
                      (jmx/read "org.apache.cassandra.db:type=StorageService"
                                :LiveNodes))
                    (catch Exception e
                      (info "Couldn't get status from node" node))))
             (-> test :nodes set (set/difference @(:bootstrap test))
                 (#(map (comp dns-resolve name) %)) set (set/difference @(:decommission test))
                 shuffle))))

(defn joining-nodes
  "Get the list of joining nodes from a random node in the cluster"
  [test]
  (set (mapcat (fn [node]
                 (try (jmx/with-connection {:host (name node) :port 7199}
                        (jmx/read "org.apache.cassandra.db:type=StorageService"
                                  :JoiningNodes))
                      (catch Exception e
                        (info "Couldn't get status from node" node))))
               (-> test :nodes set (set/difference @(:bootstrap test))
                   (#(map (comp dns-resolve name) %)) set (set/difference @(:decommission test))
                   shuffle))))

(defn nodetool
  "Run a nodetool command"
  [node & args]
  (control/on node (apply control/exec (lit "nodetool") args)))

; This policy should only be used for final reads! It tries to
; aggressively get an answer from an unstable cluster after
; stabilization
(def aggressive-read
  (proxy [RetryPolicy] []
    (onReadTimeout [statement cl requiredResponses
                    receivedResponses dataRetrieved nbRetry]
      (if (> nbRetry 100)
        (RetryPolicy$RetryDecision/rethrow)
        (RetryPolicy$RetryDecision/retry cl)))
    (onWriteTimeout [statement cl writeType requiredAcks
                     receivedAcks nbRetry]
      (RetryPolicy$RetryDecision/rethrow))
    (onUnavailable [statement cl requiredReplica aliveReplica nbRetry]
      (info "Caught UnavailableException in driver - sleeping 2s")
      (Thread/sleep 2000)
      (if (> nbRetry 100)
        (RetryPolicy$RetryDecision/rethrow)
        (RetryPolicy$RetryDecision/retry cl)))))

(def setup-lock (Object.))

(defn cached-install?
  [src]
  (try (control/exec :grep :-s :-F :-x (lit src) (lit ".download"))
       true
       (catch RuntimeException _ false)))

(defn install!
  "Installs ScyllaDB on the given node."
  [node version])

(defn configure!
  "Uploads configuration files to the given node."
  [node test]
  (info node "configuring ScyllaDB")
  (control/exec :cp :-f "/var/lib/scylla/conf/scylla.yaml.orig" "/var/lib/scylla/conf/scylla.yaml")
  (doseq [rep (into ["\"s/cluster_name: .*/cluster_name: 'jepsen'/g\""
                     "\"s/row_cache_size_in_mb: .*/row_cache_size_in_mb: 20/g\""
                     (str "\"s/seeds: .*/seeds: '" (dns-resolve :n1) "," (dns-resolve :n2) "'/g\"")
                     (str "\"s/listen_address: .*/listen_address: " (dns-resolve node)
                          "/g\"")
                     (str "\"s/rpc_address: .*/rpc_address: " (dns-resolve node) "/g\"")
                     (str "\"s/broadcast_rpc_address: .*/broadcast_rpc_address: "
                          (net/local-ip) "/g\"")
                     "\"s/internode_compression: .*/internode_compression: none/g\""
                     (str "\"s/hinted_handoff_enabled:.*/hinted_handoff_enabled: "
                          (disable-hints?) "/g\"")
                     "\"s/commitlog_sync: .*/commitlog_sync: batch/g\""
                     (str "\"s/# commitlog_sync_batch_window_in_ms: .*/"
                          "commitlog_sync_batch_window_in_ms: 1/g\"")
                     "\"s/commitlog_sync_period_in_ms: .*/#/g\""
                     (str "\"s/# phi_convict_threshold: .*/phi_convict_threshold: " (phi-level)
                          "/g\"")
                     "\"/auto_bootstrap: .*/d\""]
                    (when (compressed-commitlog?)
                      ["\"s/#commitlog_compression.*/commitlog_compression:/g\""
                       (str "\"s/#   - class_name: LZ4Compressor/"
                            "    - class_name: LZ4Compressor/g\"")]))]
    (control/exec :sed :-i (lit rep) "/var/lib/scylla/conf/scylla.yaml"))
  (control/exec :echo (str "auto_bootstrap: " (-> test :bootstrap deref node boolean))
                :>> "/var/lib/scylla/conf/scylla.yaml"))

(defn start!
  "Starts ScyllaDB"
  [node test]
  (info node "starting ScyllaDB")
  (nemesis/set-time! 0)
  (net/fast-force)
  ;   (control/exec :service :scylla-server :start)
  (control/exec "/root/scylla-run.sh" :--log-to-syslog :0 :--log-to-stdout :1 :--default-log-level :info :--network-stack :posix :-m :8G :--collectd :0 :--poll-mode :--developer-mode :1)
  (info node "Started scylla")
  (control/exec :service :scylla-jmx :start)
  (info node "Started scylla-jmx"))

(defn guarded-start!
  "Guarded start that only starts nodes that have joined the cluster already
  through initial DB lifecycle or a bootstrap. It will not start decommissioned
  nodes."
  [node test]
  (let [bootstrap (:bootstrap test)
        decommission (:decommission test)]
    (when-not (or (node @bootstrap) (->> node name dns-resolve (get decommission)))
      (start! node test))))

(defn stop!
  "Stops ScyllaDB"
  [node]
  (info node "stopping ScyllaDB")
  (meh (control/exec :service :scylla-jmx :stop))
  (while (.contains (control/exec :ps :-ef) "java")
    (Thread/sleep 1000)
    (info node "java is still running"))
  (meh (control/exec :killall :scylla))
  (while (.contains (control/exec :ps :-ef) "scylla")
    (Thread/sleep 1000)
    (info node "scylla is still running"))
  (info node "has stopped ScyllaDB"))

(defn wipe!
  "Shuts down Cassandra and wipes data."
  [node]
  (stop! node)
  (info node "deleting data files")
  (meh (control/exec "/root/wipe.sh"))
  (info node "deleted data files"))

(defn db
  "New ScyllaDB run"
  [version]
  (reify db/DB
    (setup! [_ test node]
      (when (seq (System/getenv "LEAVE_CLUSTER_RUNNING"))
        (wipe! node))
      (doto node
        (install! version)
        (configure! test)
        (guarded-start! test)))

    (teardown! [_ test node]
      (when-not (seq (System/getenv "LEAVE_CLUSTER_RUNNING"))
          (wipe! node)))

    db/LogFiles
    (log-files [db test node]
      ["/var/lib/scylla/system.log"])
      ))

(defn recover
  "A generator which stops the nemesis and allows some time for recovery."
  []
  (gen/nemesis
   (gen/phases
    (gen/once {:type :info, :f :stop})
    (gen/sleep 10))))

(defn bootstrap
  "A generator that bootstraps nodes into the cluster with the given pause
  and routes other :op's onward."
  [pause src-gen]
  (gen/conductor :bootstrapper
                 (gen/seq (cycle [(gen/sleep pause)
                                  {:type :info :f :bootstrap}]))
                 src-gen))

(defn std-gen
  "Takes a client generator and wraps it in a typical schedule and nemesis
  causing failover."
  ([gen] (std-gen 400 gen))
  ([duration gen]
   (gen/phases
    (->> gen
         (gen/nemesis
          (gen/seq (cycle [(gen/sleep (scaled 20))
                           {:type :info :f :start}
                           (gen/sleep (scaled 60))
                           {:type :info :f :stop}])))
         (bootstrap 120)
         (gen/conductor :decommissioner
                        (gen/seq (cycle [(gen/sleep (scaled 100))
                                         {:type :info :f :decommission}])))
         (gen/time-limit (scaled duration)))
    (recover)
    (gen/clients
     (->> gen
          (gen/time-limit (scaled 40)))))))

(def add {:type :invoke :f :add :value 1})
(def sub {:type :invoke :f :add :value -1})
(def r {:type :invoke :f :read})
(defn w [_ _] {:type :invoke :f :write :value (rand-int 5)})
(defn cas [_ _] {:type :invoke :f :cas :value [(rand-int 5) (rand-int 5)]})

(defn adds
  "Generator that emits :add operations for sequential integers."
  []
  (->> (range)
       (map (fn [x] {:type :invoke, :f :add, :value x}))
       gen/seq))

(defn assocs
  "Generator that emits :assoc operations for sequential integers,
  mapping x to (f x)"
  [f]
  (->> (range)
       (map (fn [x] {:type :invoke :f :assoc :value {:k x
                                                     :v (f x)}}))
       gen/seq))

(defn read-once
  "A generator which reads exactly once."
  []
  (gen/clients
   (gen/once r)))

(defn safe-mostly-small-nonempty-subset
  "Returns a subset of the given collection, with a logarithmically decreasing
  probability of selecting more elements. Always selects at least one element.
      (->> #(mostly-small-nonempty-subset [1 2 3 4 5])
           repeatedly
           (map count)
           (take 10000)
           frequencies
           sort)
      ; => ([1 3824] [2 2340] [3 1595] [4 1266] [5 975])"
  [xs test]
  (-> xs
      count
      inc
      Math/log
      rand
      Math/exp
      long
      (take (shuffle xs))
      set
      (set/difference @(:bootstrap test))
      (#(map (comp dns-resolve name) %))
      set
      (set/difference @(:decommission test))
      shuffle))

(defn test-aware-node-start-stopper
  "Takes a targeting function which, given a list of nodes, returns a single
  node or collection of nodes to affect, and two functions `(start! test node)`
  invoked on nemesis start, and `(stop! test node)` invoked on nemesis stop.
  Returns a nemesis which responds to :start and :stop by running the start!
  and stop! fns on each of the given nodes. During `start!` and `stop!`, binds
  the `jepsen.control` session to the given node, so you can just call `(control/exec
  ...)`.

  Re-selects a fresh node (or nodes) for each start--if targeter returns nil,
  skips the start. The return values from the start and stop fns will become
  the :values of the returned :info operations from the nemesis, e.g.:

      {:value {:n1 [:killed \"java\"]}}"
  [targeter start! stop!]
  (let [nodes (atom nil)]
    (reify client/Client
      (setup! [this test _] this)

      (invoke! [this test op]
        (locking nodes
          (assoc op :type :info, :value
                 (case (:f op)
                   :start (if-let [ns (-> test :nodes (targeter test) util/coll)]
                            (if (compare-and-set! nodes nil ns)
                              (control/on-many ns (start! test (keyword control/*host*)))
                              (str "nemesis already disrupting " @nodes))
                            :no-target)
                   :stop (if-let [ns @nodes]
                           (let [value (control/on-many ns (stop! test (keyword control/*host*)))]
                             (reset! nodes nil)
                             value)
                           :not-started)))))

      (teardown! [this test]))))

(defn crash-nemesis
  "A nemesis that crashes a random subset of nodes."
  []
  (test-aware-node-start-stopper
   safe-mostly-small-nonempty-subset

   (fn start [test node]
     (meh (control/exec :service :scylla-jmx :stop))
     (while (.contains (control/exec :ps :-ef) "java")
       (Thread/sleep 100))
     (meh (control/exec :killall :-9 :scylla)))

   (fn stop  [test node] (meh (guarded-start! node test)) [:restarted node])))

(defn cassandra-test
  [name opts]
  (merge tests/noop-test
         {:name    (str "cassandra " name)
          :os      jepsen.os/noop
          :nodes (clojure.string/split (get (System/getenv) "NODES") #" ")
          :ssh   {:username "root" :strict-host-key-checking false :private-key-path "private_key_rsa"}
          :db      (db "2.1.8")
          :bootstrap (atom #{})
          :decommission (atom #{})}
         opts))
