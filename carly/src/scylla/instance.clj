(ns scylla.instance
  (:require [jepsen.util]
            [jepsen.control :refer [*host*]]
            [carly.core]
            [carly.hooks]
            [clj-yaml.core :as yaml]
            [clojure.tools.logging :as logging]))

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

(defprotocol Instance
  (run-stop-command!  [instance])
  (start! [instance])
  (log-paths [instance]))

(defn stop!  [instance]
  (logging/info *host* "will stop scylla")
  (run-stop-command! instance) 
  (while (.contains (jepsen.control/exec :ps :-ef) "scylla")
    (Thread/sleep 1000)
    (logging/info *host* "scylla is still running"))
  (logging/info *host* "has stopped scylla"))

(defn wipe!  [instance]
  (stop! instance)
  (jepsen.util/meh (jepsen.control/exec :rm :-rf "/var/lib/scylla/"))
  (carly.hooks/signal-ready! *host*))
