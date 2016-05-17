(ns carly.checkers
    (:require [jepsen.checker]
              [clojure.tools.logging :as logging]))

(def happy
  (reify jepsen.checker/Checker
    (check  [self test model history]
      (logging/info "happy checker always happy :)")
      { :valid? true :happy true })))
