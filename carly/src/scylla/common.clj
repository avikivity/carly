(ns scylla.common
  (:require [clojure.tools.logging :as logging]))

(def cpuset-counter (atom 0))
(defn cpuset!
  []
  (let [CPUS_PER_CONTAINER 4
        low (* CPUS_PER_CONTAINER @cpuset-counter)
        high (+ low CPUS_PER_CONTAINER -1)]
    (swap! cpuset-counter inc)
    (str low "-" high)))

(defn sleep-grace-period
  [node]
  (logging/info node "sleep grace period")
  (Thread/sleep 10000))
