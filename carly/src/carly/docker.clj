(ns carly.docker
  (:require 
    [clojure.string]
    [clj-yaml.core :as yaml]
    [clojure.tools.logging :as logging]
    [carly.core :as core]
    ))

(defn docker!
  [command containers]
  (->> (concat [:docker command] containers)
       (apply core/shell!)))

(defn container-names
  []
  (->> (core/shell! :docker :ps :--format "{{.Names}}")
       :out
       clojure.string/split-lines))

(defn reset-containers!
  []
  (let [current-containers (container-names)]
    (docker! :stop current-containers)
    (docker! :rm current-containers)))

(defn build-containers!
  [how-many]
  (let [build-one! 
        (fn [] 
          (core/shell!
            :docker
            :run
            :--privileged
            :-d
            "haarcuba/centos_ssh_systemd_scyllaports"))]
    (dorun (repeatedly how-many build-one!))))

(defn container-ips
  []
  (->> (container-names)
       (docker! :inspect)
       :out
       (yaml/parse-string)
       (map #(->> % :NetworkSettings :IPAddress))
       (map keyword)
       vec))
