(ns carly.core
  (:require [jepsen.control]
            [clojure.walk]
            [clojure.tools.logging :as logging])
  (:import (java.net InetAddress)))

(defn dns-resolve
  [hostname]
  (.getHostAddress (InetAddress/getByName (name hostname))))

(defn disable-hints?
  "Returns true if Jepsen tests should run without hints"
  []
  (not (System/getenv "JEPSEN_DISABLE_HINTS")))

(defn phi-level
  "Returns the value to use for phi in the failure detector"
  []
  (or (System/getenv "JEPSEN_PHI_VALUE")
      8))

(defn compressed-commitlog?
  "Returns whether to use commitlog compression"
  []
  (= (some-> (System/getenv "JEPSEN_COMMITLOG_COMPRESSION") (clojure.string/lower-case))
     "false"))

(defn write-file
  [path text]
  (jepsen.control/exec :echo text :> path))

(defn transform-file
  "read the text from the file at `path`, then transform it, and write it back to the same place"
  [path transformer]
  (let [write-back
          (fn [text]
             (write-file path text))]
    (->> path
         (jepsen.control/exec "cat")
         transformer
         write-back)))

(defn command-line-arguments->map
  [arguments-string]
  (->> arguments-string
       (re-seq #"--?([^ ]+) ([^ ]+)")
       (map rest)
       flatten
       (apply hash-map)
       (clojure.walk/keywordize-keys)))

(defn map->command-line-arguments
  [dictionary]
  (let [prepend-prefix (fn [thing]
                        (let [string (name thing)
                              prefix (if (= 1 (.length string)) "-" "--")]
                          (str prefix string)))
        pair->argument (fn [[first second]] (str (prepend-prefix first) " " second))]
    (->> dictionary
         seq
         (map pair->argument)
         (clojure.string/join " "))))

(defn all-containers-up
 []
 (let [lxc-list-output (:out (clojure.java.shell/sh "lxc" "list"))
       lines (count (clojure.string/split-lines lxc-list-output))
       containers (/  (- lines 3) 2)
       ips (vec (re-seq #"(?m)\d+\.\d+\.\d+\.\d+" lxc-list-output))
       ]
   (= (count ips) containers)))

(defn lxd-wait-for-containers-up
  []
  (while (not (all-containers-up))
             (logging/info "waiting for containers")
             (Thread/sleep 1000)))

(defn lxd-containers-ips
  []
  (lxd-wait-for-containers-up)
  (let [result (->>  (clojure.java.shell/sh "lxc" "list")
                     :out
                     (re-seq #"(?m)\d+\.\d+\.\d+\.\d+")
                     (map keyword)
                     vec)]
    (logging/info "found" (count result) "containers at" result)
    result))

(defn choose
  [k collection]
  (let [size collection
        result (atom #{})]
    (while (< (count @result) k)
      (swap! result (fn [old] (conj old (rand-nth collection)))))
    @result))
