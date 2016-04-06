(ns carly.core
  (:require [jepsen.control])
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
