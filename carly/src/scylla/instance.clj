(ns scylla.instance
  (:require [jepsen.util]
            [jepsen.control :refer [*host*]]
            [clojure.tools.logging :as logging]))

(defprotocol Instance
  (run-stop-command!  [instance])
  (start! [instance])
  (log-paths [instance]))

(defn stop!  [instance]
  (logging/info *host* "stopping ScyllaDB")
  (run-stop-command! instance) 
  (while (.contains (jepsen.control/exec :ps :-ef) "scylla")
    (Thread/sleep 1000)
    (logging/info *host* "scylla is still running"))
  (logging/info *host* "has stopped ScyllaDB"))

(defn wipe!  [instance]
  (stop! instance)
  (jepsen.util/meh (jepsen.control/exec :rm :-rf "/var/lib/scylla/")))
