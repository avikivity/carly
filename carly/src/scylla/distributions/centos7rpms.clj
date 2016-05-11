(ns scylla.distributions.centos7rpms
  (:require [scylla.instance]
            [scylla.common]
            [scylla.configure]
            [carly.hooks]
            [jepsen.db]
            [jepsen.os]
            [jepsen.util]
            [jepsen.control]
            [jepsen.nemesis]
            [jepsen.control.net]
            [clojure.tools.logging :as logging]
            [carly.core]))

(def installed (atom #{}))
(def PACKAGE_MANAGER :yum)

(def centos7
  (reify jepsen.os/OS
    (setup! [self test node]
      (logging/info node "CENTOS 7 SETUP")
      (jepsen.control/exec :rm :-f  "/etc/yum.repos.d/scylla.repo")
      (jepsen.control/exec PACKAGE_MANAGER :remove :abrt :-y)
      (jepsen.control/exec PACKAGE_MANAGER :install :epel-release :wget :-y))

    (teardown! [self test node]
      (logging/info node "CENTOS 7 TEARDOWN"))))

(defn install-scylla-rpms!
  [repository-url node]
  (if (@installed node)
    (logging/info node "scylla packages already installed, skipping installation"))
  (when-not (@installed node)
    (logging/info node "installing scylla packages")
    (jepsen.control/exec :curl :-o  "/etc/yum.repos.d/scylla.repo" repository-url)
    (jepsen.util/meh (jepsen.control/exec PACKAGE_MANAGER :remove :scylla-server :scylla-jmx :scylla-tools :-y))
    (jepsen.control/exec PACKAGE_MANAGER :install :scylla-server :scylla-jmx :scylla-tools :-y)
    (swap! installed (fn [old] (conj old node)))))

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

(defn factory
  [repository-url]
  (reify
    jepsen.db/DB
    (setup! [self test node]
      (scylla.instance/wipe! self)
      (carly.hooks/wait-for-all-nodes-ready)
      (logging/info node "setup")
      (jepsen.control/exec PACKAGE_MANAGER :install :sudo :libfaketime :psmisc :-y)
      (install-scylla-rpms! repository-url node)
      (jepsen.control/exec :mkdir :-p "/var/lib/scylla")
      (jepsen.control/exec :chown "scylla.scylla" "/var/lib/scylla")
      (carly.core/transform-file "/etc/sysconfig/scylla-server"
         (scylla.configure/augment-command-line-arguments 
           {:developer-mode "1"
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
      (record-time :finish node))

    jepsen.db/LogFiles
    (log-files [db test node]
      (create-logs! node))

    scylla.instance/Instance
    (run-stop-command! [instance]
      (jepsen.util/meh (jepsen.control/exec :systemctl :stop :scylla-jmx))
      (jepsen.util/meh (jepsen.control/exec :systemctl :stop :scylla-server)))

    (start!  [instance]
      (jepsen.control/exec :systemctl :enable :scylla-jmx)
      (jepsen.control/exec :systemctl :start :scylla-server)
      (jepsen.control/exec :systemctl :start :scylla-jmx))))
