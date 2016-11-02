(ns scylla.distributions.redhat
  (:require [jepsen.control]))

(defn retrieve-repository!
  [repository-url]
  (jepsen.control/exec :curl :-o  "/etc/yum.repos.d/scylla.repo" repository-url))
