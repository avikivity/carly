(ns scylla.distributions.centos7docker
  (:require [scylla.instance]
            [scylla.common]
            [scylla.distributions.redhat :as redhat]
            [carly.hooks]
            [carly.hacks]
            [jepsen.db]
            [jepsen.util]
            [jepsen.control]
            [jepsen.nemesis]
            [jepsen.store]
            [jepsen.control.net]
            [clojure.tools.logging :as logging]
            [carly.core]))

(defn augment-command-line-arguments
  [arguments]
  (fn
    [text]
    (let [arguments-pattern #"(?m)^SCYLLA_ARGS=\"([^\"]+)\""
          arguments-line (re-find arguments-pattern text)
          new-argument-string (-> arguments-line
                                  second
                                  carly.core/command-line-arguments->map
                                  (merge arguments)
                                  carly.core/map->command-line-arguments)
          replacement (str "SCYLLA_ARGS=\"" new-argument-string "\"")]
        (clojure.string/replace text arguments-pattern replacement))))



(def timestamps (atom {}))
(defn record-time
  [label node]
  (let [time-string (jepsen.control/exec :date :-u "+%F %T")
        new-times (-> (node @timestamps)
                      (merge {})
                      (merge {label time-string}))]
    (logging/info node label "@" time-string)
    (swap! timestamps
           (fn [old]
             (merge old {node new-times})))))

(defn create-logs!
  [node]
  (let [{start :start finish :finish} (node @timestamps)
        LOG_FILE  "/tmp/scylla.log"]
    (logging/info node "writing logs to " LOG_FILE start "-" finish)
    (jepsen.control/exec :journalctl :-u "scylla-server" :--since start :--until finish :> LOG_FILE)
    [LOG_FILE]))

(defn first-teardown?
  [node]
  (not (->> @timestamps node :start)))

(defn factory
  [repository-url]
  (reify
    jepsen.db/DB
    (setup! [self test node]
      (logging/info node "setup")
      (scylla.instance/wipe! self)
      (carly.hooks/wait-for-all-nodes-ready)
      (scylla.instance/install! self :libfaketime :psmisc :epel-release)
      (jepsen.control/exec :yum :-y :clean :expire-cache)
      (jepsen.control/exec :yum :-y :update)
      (scylla.instance/install-scylla-rpms! self repository-url node)
      (jepsen.control/exec :systemctl :enable :scylla-server)
      (jepsen.control/exec :systemctl :enable :scylla-jmx)
      (jepsen.control/exec :yum :-y :clean :all)
      (jepsen.control/exec :mkdir :-p "/var/lib/scylla")
      (jepsen.control/exec :chown "scylla.scylla" "/var/lib/scylla")
      (carly.core/transform-file "/etc/sysconfig/scylla-server"
                                 (augment-command-line-arguments { :developer-mode "1"
                                                                  :memory "8G"
                                                                  :cpuset (scylla.common/cpuset! node)}))
      (logging/info node "deleted data files")
      (let [config-path "/etc/scylla/scylla.yaml"]
        (scylla.instance/configure! node test config-path))
      (jepsen.nemesis/set-time! 0)
      (jepsen.control.net/fast-force)
      (record-time :start node)
      (scylla.instance/start! self)
      (scylla.common/sleep-grace-period node)
      (logging/info node "setup done"))

    (teardown! [self test node]
      (when-not (first-teardown? node)
        (carly.hacks/saferun
          (record-time :finish node)
          (let [logfile (create-logs! node)
                local-store (str (jepsen.store/path! test (name node) "scylla.log")) ]
            (jepsen.control/download logfile local-store)))))

    scylla.instance/Instance
    (run-stop-command! [instance]
      (jepsen.util/meh (jepsen.control/exec :systemctl :stop :scylla-jmx))
      (jepsen.util/meh (jepsen.control/exec :systemctl :stop :scylla-server)))

    (start! [instance]
      (jepsen.control/exec :systemctl :start :scylla-server)
      (jepsen.control/exec :systemctl :start :scylla-jmx))
    
    (package-manager [instance] :yum)

    (retrieve-repository! [instance url]
      (redhat/retrieve-repository! url))))
