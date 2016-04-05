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
            [clojurewerkz.cassaforte.cql :as cql]
            [clj-yaml.core :as yaml]
            )
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

(defn configure! [node test]
  (info node "configuring ScyllaDB")
  (let [config-path (->> test :scylla :config-path)
        config (control/exec "cat" config-path)
        seeds (-> test :nodes first dns-resolve)
        commitlog-compression-options (when (compressed-commitlog?)
                                          {:commitlog_compression 
                                            [{:class_name "LZ4Compressor"}]})
        new-config  (-> config
                      yaml/parse-string
                      (merge {:cluster_name "jepsen"
                              :row_cache_size_in_mb 20
                              :seed_provider 
                                 [ { :class_name "org.apache.cassandra.locator.SimpleSeedProvider"
                                     :parameters 
                                          [{:seeds seeds}] 
                                  }] 
                              :listen_address (dns-resolve node)
                              :rpc_address (dns-resolve node) 
                              :internode_compression (str (disable-hints?))
                              :commitlog_sync "batch"
                              :commitlog_sync_batch_window_in_ms 1
                              :commitlog_sync_preiod_in_ms 10000
                              :phi_convict_threshold (phi-level)
                              :auto_bootstrap (-> test :bootstrap deref node boolean) }

                              commitlog-compression-options)
                      (dissoc :commitlog_sync_period_in_ms)
                      yaml/generate-string) ] 
        (control/exec :echo new-config :> config-path)
        (info node "configure! finished")))

(def cpuset-counter (atom 0))
(defn cpuset!
  []
  (let [CPUS_PER_CONTAINER 4
        low (* CPUS_PER_CONTAINER @cpuset-counter)
        high (+ low CPUS_PER_CONTAINER -1)]
    (swap! cpuset-counter inc)
    (str low "-" high)))

(defn scylla-command!
  [test]
  (let [executable (->> test :scylla :executable)
        config-path (->> test :scylla :config-path)]
       [ executable
         "--log-to-syslog" "0"
         "--options-file"  config-path
         "--log-to-stdout" "1"
         "--default-log-level" "info"
         "--network-stack" "posix"
         "--memory" "8G"
         "--collectd" "0"
         "--poll-mode"
         "--developer-mode" "1"
         "--cpuset" (cpuset!) 
         ">&" (:logfile test) "&"
         ]  ))

(defn start!
  "Starts ScyllaDB"
  [node test]
  (info node "starting ScyllaDB")
  (nemesis/set-time! 0)
  (net/fast-force)
  (control/exec "bash" "/tmp/go.sh")
  (info node "Started scylla")
  (meh (control/exec :service :scylla-jmx :start))
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
  (meh (control/exec :pkill :scylla))
  (while (.contains (control/exec :ps :-ef) "scylla")
    (Thread/sleep 1000)
    (info node "scylla is still running"))
  (info node "has stopped ScyllaDB"))

(defn wipe!
  [node]
  (stop! node)
  (meh (control/exec :rm :-rf "/var/lib/scylla/"))
  (info node "deleted data and log files"))

(defn put-scylla-script [node test]
  (let [command (scylla-command! test)]
    (control/exec :echo command :> "/tmp/go.sh")))

(defn db
  "New ScyllaDB run"
  [version]
  (reify db/DB
    (setup! [_ test node]
      (info node "SETUP")
      (control/exec :dnf :install :sudo :-y)
      (put-scylla-script node test)
      (control/exec :rm :-f (:logfile test))
      (wipe! node)
      (configure! node test)
      (start! node test)
      (info node "SETUP DONE"))

    (teardown! [_ test node]
      (when-not (seq (System/getenv "LEAVE_CLUSTER_RUNNING"))
          (wipe! node)))

    db/LogFiles
    (log-files [db test node]
      [(:logfile test)])))

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

(defn get-nodes
  []
  (let [node-names (-> (get (System/getenv) "NODES") 
                    (clojure.string/split #" "))]
      (mapv keyword node-names)))

(defn cassandra-test
  [name opts]
  (merge tests/noop-test
         {:name    (str "cassandra " name)
          :os      jepsen.os/noop
          :nodes   (get-nodes)
          :logfile "/var/log/scylla.log"
          :ssh   {:username "root" :strict-host-key-checking false :private-key-path "private_key_rsa"}
          :db      (db "2.1.8")
          :bootstrap (atom #{})
          :decommission (atom #{})}
         opts))
