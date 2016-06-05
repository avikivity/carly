(ns scylla.instance
  (:require [jepsen.util]
            [jepsen.control :refer [*host*]]
            [carly.core]
            [carly.hooks]
            [carly.hacks]
            [scylla.distributions.redhat :as redhat]
            [clj-yaml.core :as yaml]
            [clojure.tools.logging :as logging]))

(defprotocol Instance
  (run-stop-command!  [instance])
  (start! [instance])
  (log-paths [instance])
  (package-manager [instance])
  (retrieve-repository! [instance url]))

(defn package!
  [instance action packages]
  (logging/info "package!" action packages)
  (let [package-manager (package-manager instance)
        arguments       (concat [package-manager action :-y] packages)]
    (apply jepsen.control/exec arguments)))

(def installed (atom #{}))
(defn install!
  [instance & packages]
  (package! instance :install packages))

(defn uninstall!
  [instance & packages]
  (carly.hacks/saferun
    (package! instance :remove packages)))

(defn install-scylla-rpms!
  [instance repository-url node]
    (if (@installed node)
      (logging/info node "scylla packages already installed, skipping installation"))
    (when-not (@installed node)
      (logging/info node "installing scylla packages")
      (retrieve-repository! instance repository-url)
      (uninstall! instance :scylla-server :scylla-jmx :scylla-tools)
      (install! instance :scylla-server :scylla-jmx :scylla-tools)
      (swap! installed conj node)))

(defn stop! 
  [instance]
  (logging/info *host* "will stop scylla")
  (run-stop-command! instance) 
  (while (.contains (jepsen.control/exec :ps :-ef) "scylla")
    (Thread/sleep 1000)
    (logging/info *host* "scylla is still running"))
  (logging/info *host* "has stopped scylla"))

(defn wipe! 
  [instance]
  (stop! instance)
  (carly.hacks/saferun (jepsen.control/exec :rm :-rf "/var/lib/scylla/"))
  (carly.hooks/signal-ready! *host*))

(defn configure! [node test config-path]
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
