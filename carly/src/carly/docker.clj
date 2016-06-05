(ns carly.docker
  (:require 
    [clojure.string]
    [clj-yaml.core :as yaml]
    [clojure.tools.logging :as logging]))


(defn container-names
  []
  (->> (clojure.java.shell/sh "docker" "ps" "--format" "{{.Names}}")
       :out
       clojure.string/split-lines))

(defn container-ips
  []
  (->> (container-names)
       (concat ["docker" "inspect"])
       (apply clojure.java.shell/sh)
       :out
       (yaml/parse-string)
       (map #(->> % :NetworkSettings :IPAddress))
       (map keyword)
       vec))
