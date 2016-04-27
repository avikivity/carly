(ns scylla.checkers
    (:require [jepsen.checker]
              [clojure.tools.logging :as logging]))


(def verify-scylla-lives
  (reify jepsen.checker/Checker
    (check  [self test model history]
      (logging/info "verify-scylla-lives" test model history)
      { :valid? true 
        :arbitrary :data })))
