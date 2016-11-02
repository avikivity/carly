(ns carly.lxd
  (:require [clojure.tools.logging :as logging]))

(defn all-containers-up
 []
 (let [lxc-list-output (:out (clojure.java.shell/sh "lxc" "list"))
       lines (count (clojure.string/split-lines lxc-list-output))
       containers (/  (- lines 3) 2)
       ips (vec (re-seq #"(?m)\d+\.\d+\.\d+\.\d+" lxc-list-output))
       ]
   (= (count ips) containers)))

(defn wait-for-containers-up
  []
  (while (not (all-containers-up))
             (logging/info "waiting for containers")
             (Thread/sleep 1000)))

(defn container-ips
  []
  (wait-for-containers-up)
  (let [result (->>  (clojure.java.shell/sh "lxc" "list")
                     :out
                     (re-seq #"(?m)\d+\.\d+\.\d+\.\d+")
                     (map keyword)
                     vec)]
    (logging/info "found" (count result) "containers at" result)
    result))
