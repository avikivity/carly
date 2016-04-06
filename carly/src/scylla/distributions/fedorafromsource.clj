(ns scylla.distributions.fedorafromsource
  (:require [scylla.instance] 
            [jepsen.db]
            [jepsen.util]
            [jepsen.control]
            [jepsen.nemesis]
            [jepsen.control.net]
            [clojure.tools.logging :as logging]
            [clj-yaml.core :as yaml]
            [carly.core]))
(def log-file "/var/log/scylla.log")
(def config-path "/root/source/scylla/conf/scylla.yaml")
(def cpuset-counter (atom 0))
(defn- cpuset!
  []
  (let [CPUS_PER_CONTAINER 4
        low (* CPUS_PER_CONTAINER @cpuset-counter)
        high (+ low CPUS_PER_CONTAINER -1)]
    (swap! cpuset-counter inc)
    (str low "-" high)))

(defn- scylla-command!
    [instance]
    [ "/root/source/scylla/build/release/scylla" 
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
           ">&" log-file "&" ])

(defn- put-scylla-script [instance]
  (let [command (scylla-command! instance)]
    (jepsen.control/exec :echo command :> "/tmp/go.sh")))

(defn- configure! [node test]
  (logging/info node "configuring ScyllaDB")
  (let [config (jepsen.control/exec "cat" config-path)
        seeds (-> test :nodes first carly.core/dns-resolve)
        commitlog-compression-options (when (carly.core/compressed-commitlog?)
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
                              :listen_address (carly.core/dns-resolve node)
                              :rpc_address (carly.core/dns-resolve node) 
                              :internode_compression (str (carly.core/disable-hints?))
                              :commitlog_sync "batch"
                              :commitlog_sync_batch_window_in_ms 1
                              :commitlog_sync_preiod_in_ms 10000
                              :phi_convict_threshold (carly.core/phi-level)
                              :auto_bootstrap (-> test :bootstrap deref node boolean) }

                              commitlog-compression-options)
                      (dissoc :commitlog_sync_period_in_ms)
                      yaml/generate-string) ] 
        (jepsen.control/exec :echo new-config :> config-path)
        (logging/info node "configure! finished")))

(defn- sleep-grace-period
  [node]
  (logging/info node "sleep grace period")
  (Thread/sleep 10000))

(defn fedora-from-source
  []
  (reify
    jepsen.db/DB
      (setup! [self test node]
        (logging/info node "SETUP")
        (jepsen.control/exec :dnf :install :sudo :-y)
        (put-scylla-script self)
        (jepsen.control/exec :rm :-f log-file)
        (scylla.instance/wipe! self)
        (logging/info node "deleted data and log files")
        (configure! node test)
        (jepsen.nemesis/set-time! 0)
        (jepsen.control.net/fast-force)
        (scylla.instance/start! self)
        (sleep-grace-period node)
        (logging/info node "SETUP DONE"))

      (teardown! [self test node]
        (when-not (seq (System/getenv "LEAVE_CLUSTER_RUNNING"))
            (scylla.instance/wipe! self)))

    jepsen.db/LogFiles
      (log-files [self test node]
        (scylla.instance/log-paths self))

    scylla.instance/Instance
      (run-stop-command! [instance]
        (jepsen.util/meh (jepsen.control/exec :pkill :scylla)))

      (start!  [instance] 
        (jepsen.control/exec "bash" "/tmp/go.sh"))

      (log-paths [instance]
        [log-file])))
