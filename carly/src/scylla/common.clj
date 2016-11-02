(ns scylla.common
  (:require [clojure.tools.logging :as logging]))


(def CPUS_PER_CONTAINER
  (if-let [environment-spec (System/getenv "CPUS_PER_CONTAINER")]
    (Integer. environment-spec)
    4))

(def cpu-allocation (atom {}))
(defn allocate-cpus
  [current-allocation node]
  (let [low (* CPUS_PER_CONTAINER (count current-allocation))
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
  (Thread/sleep 30000))
