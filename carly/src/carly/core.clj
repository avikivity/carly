(ns carly.core
  (:require [jepsen.control]
            [clojure.walk])
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
