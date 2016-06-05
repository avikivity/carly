(ns carly.setups
  (:require [carly.core :as core]
            [carly.lxd :as lxd]
            [carly.docker :as docker]
            [scylla.distributions.fedora22rpms]
            [scylla.distributions.centos7docker]
            [clojure.tools.logging :as logging]))

(defn SETUPS
  [setup-name]
  (case setup-name

    :fedora22rpms
    {
     :nodes (lxd/container-ips)
     :ssh   {:username "root" :strict-host-key-checking false :private-key-path "private_key_rsa"}
     :db    (scylla.distributions.fedora22rpms/factory "https://s3.amazonaws.com/downloads.scylladb.com/rpm/unstable/fedora/master/149/scylla.repo")
     :cassandra-stress-executable "/home/ubuntu/work/scylla-tools-java/tools/bin/cassandra-stress"
     }

    :centos7docker
    {
     :nodes (docker/container-ips)
     :ssh   {:username "root" :strict-host-key-checking false :private-key-path "private_key_rsa"}
     :db    (scylla.distributions.centos7docker/factory "http://downloads.scylladb.com/rpm/unstable/centos/master/latest/scylla.repo")
     :cassandra-stress-executable "/home/ubuntu/work/scylla-tools-java/tools/bin/cassandra-stress"
     }))

; override the default setup using the file "setup.clj"
(try
  (load-file "setup.clj")
  (catch java.io.FileNotFoundException e))

(when-not (resolve 'default)
  (def default (SETUPS :fedora22rpms)))

(logging/info "default test setup:" default)
