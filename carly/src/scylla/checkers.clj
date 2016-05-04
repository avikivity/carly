(ns scylla.checkers
    (:require [jepsen.checker]
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

(intern 'jepsen.store 'nonserializable-keys  (conj  jepsen.store/nonserializable-keys :bootstrapped))

(def verify-scylla-lives
  (reify jepsen.checker/Checker
    (check  [self test model history]
      (let [bootstrapped? (:bootstrapped test)
            status (->> test
                        :nodes
                        (remove @bootstrapped?)
                        (map (fn [host] [host (scylla-running! host)])))
            valid (every? second status)]
            { :valid? valid
              :scylla-running status}))))
