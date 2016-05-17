(ns scylla.checkers
    (:require [jepsen.checker]
              [clojure.tools.logging :as logging]
              [clj-ssh.ssh]))

(defn scylla-running!
  [host]
  (let  [agent  (clj-ssh.ssh/ssh-agent  {})]
    (clj-ssh.ssh/add-identity agent {:private-key-path "private_key_rsa"})
    (let  [session  (clj-ssh.ssh/session agent
                                         (name host)
                                         {:username "root"
                                          :strict-host-key-checking :no})]
      (clj-ssh.ssh/with-connection session
        (let  [pgrep (clj-ssh.ssh/ssh session  {:cmd "pgrep scylla"})]
          (= 0 (:exit pgrep)))))))

(defn scylla-server-status!
  [nodes]
  (->> nodes
       (map (fn [host] [host (scylla-running! host)]))))

(intern 'jepsen.store 'nonserializable-keys  (conj  jepsen.store/nonserializable-keys :bootstrapped))

(def verify-scylla-lives
  (reify jepsen.checker/Checker
    (check  [self test model history]
      (logging/info "verifying scyllas are alive. bootstrapped nodes are" @(:bootstrapped test))
      (let [bootstrapped? (:bootstrapped test)
            status (->> test
                        :nodes
                        (remove @bootstrapped?)
                        scylla-server-status!) 
            valid (every? second status)]
            { :valid? valid
              :scylla-running status}))))
