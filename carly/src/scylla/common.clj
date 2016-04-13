(ns scylla.common
  (:require [clojure.tools.logging :as logging]))

(def cpu-allocation (atom {}))
(defn allocate-cpus
  [current-allocation node]
  (let [CPUS_PER_CONTAINER 4
        low (* CPUS_PER_CONTAINER (count current-allocation))
        high (+ low CPUS_PER_CONTAINER -1)]
    (->> (str low "-" high)
         (assoc current-allocation node)))) 

(defn cpuset!
  [node]
  (or (@cpu-allocation node)
      (do
        (swap! cpu-allocation allocate-cpus node)
        (@cpu-allocation node))))

(defn sleep-grace-period
  [node]
  (logging/info node "sleep grace period")
  (Thread/sleep 10000))
