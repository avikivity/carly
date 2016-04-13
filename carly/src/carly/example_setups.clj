(ns carly.setups
  (:require [carly.core :as core]
            [scylla.distributions.fedora22rpms]))

(def SETUPS
  {
    :fedora22rpms 
    { 
      :nodes (core/lxd-containers-ips)
      :ssh   {:username "root" :strict-host-key-checking false :private-key-path "private_key_rsa"}
      :db    (scylla.distributions.fedora22rpms/factory "https://s3.amazonaws.com/downloads.scylladb.com/rpm/unstable/fedora/master/149/scylla.repo")
    }
  })

(def default (SETUPS :fedora22rpms))
