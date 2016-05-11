(ns carly.setups
  (:require [carly.core :as core]
            [scylla.distributions.fedora22rpms]
            [scylla.distributions.centos7rpms]
            [clojure.tools.logging :as logging]))

(def SETUPS
  {
    :fedora22rpms
    {
      :nodes (core/lxd-containers-ips)
      :os    jepsen.os/noop
      :ssh   {:username "root" :strict-host-key-checking false :private-key-path "private_key_rsa"}
      :db    (scylla.distributions.fedora22rpms/factory "https://s3.amazonaws.com/downloads.scylladb.com/rpm/unstable/fedora/master/149/scylla.repo")
      :cassandra-stress-executable "/home/ubuntu/work/scylla-tools-java/tools/bin/cassandra-stress"
    }

    :centos7rpms
    {
      :nodes (core/lxd-containers-ips)
      :os    scylla.distributions.centos7rpms/centos7
      :ssh   {:username "root" :strict-host-key-checking false :private-key-path "private_key_rsa"}
      :db    (scylla.distributions.centos7rpms/factory "http://downloads.scylladb.com/rpm/centos/scylla.repo")
      :cassandra-stress-executable "/home/ubuntu/work/scylla-tools-java/tools/bin/cassandra-stress"
    }

  }
)

(def default (SETUPS :fedora22rpms))

; override the default setup using the file "setup.clj"
(try
  (load-file "setup.clj")
  (catch java.io.FileNotFoundException e))

(logging/info "default test setup:" default)
