(ns scylla.distributions.fedora22rpms
  (:require [scylla.instance]
            [scylla.common]
            [jepsen.db]
            [jepsen.util]
            [jepsen.control]
            [jepsen.nemesis]
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


(def installed (atom #{}))

(defn install-scylla-rpms!
  [repository-url node]
  (if (@installed node)
    (logging/info node "scylla packages already installed, skipping installation"))
  (when-not (@installed node)
    (logging/info node "installing scylla packages")
    (jepsen.control/exec :curl :-o  "/etc/yum.repos.d/scylla.repo" repository-url)
    (jepsen.util/meh (jepsen.control/exec :dnf :remove :scylla-server :scylla-jmx :scylla-tools :-y))
    (jepsen.control/exec :dnf :install :scylla-server :scylla-jmx :scylla-tools :-y)
    (swap! installed (fn [old] (conj old node)))))

(defn factory
  [repository-url]
  (reify
    jepsen.db/DB
      (setup! [self test node]
        (logging/info node "SETUP")
        (scylla.instance/wipe! self)
        (jepsen.control/exec :dnf :install :sudo :libfaketime :psmisc :-y)
        (install-scylla-rpms! repository-url node)
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
        (scylla.instance/start! self)
        (scylla.common/sleep-grace-period node)
        (logging/info node "SETUP DONE"))

      (teardown! [self test node]
        (when-not (seq (System/getenv "LEAVE_CLUSTER_RUNNING"))
            (scylla.instance/wipe! self)))

    scylla.instance/Instance
      (run-stop-command! [instance]
        (jepsen.util/meh (jepsen.control/exec :systemctl :stop :scylla-jmx))
        (jepsen.util/meh (jepsen.control/exec :systemctl :stop :scylla-server)))

      (start!  [instance]
        (jepsen.control/exec :systemctl :start :scylla-server)
        (jepsen.control/exec :systemctl :start :scylla-jmx))))
