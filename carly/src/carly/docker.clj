(ns carly.docker
  (:require 
    [clojure.string]
    [clj-yaml.core :as yaml]
    [clojure.tools.logging :as logging]
    [again.core :as again]
    [carly.hacks :as hacks]
    [carly.core :as core]))

(def HOW-MANY
  (if-let [environment-spec (System/getenv "CONTAINERS")]
    (Integer. environment-spec)
    5))

(defn docker!
  [& arguments]
  (let [docker-args (butlast arguments)
        containers (last arguments) ]
    (->> (concat [:docker] docker-args containers)
         (apply core/shell!))))

(defn container-names
  []
  (->> (core/shell! :docker :ps :--format "{{.Names}}")
       :out
       clojure.string/split-lines
       (filter #(re-find #"carly" %))))

(defn setup-ssh!
  [container]
  (core/shell! :docker :exec container :mkdir "/root/.ssh/")
  (core/shell! :docker :cp :-L "public_key_rsa" (str container ":/root/.ssh/authorized_keys"))
  (core/shell! :docker :exec container :chmod :600 "/root/.ssh/")
  (core/shell! :docker :exec container :yum :-y :install :openssh-server)
  (core/shell! :docker :exec container :sshd-keygen)
  (core/shell! :docker :exec container "/usr/sbin/sshd"))

(defn build-container!
  [image number]
  (again/with-retries [500 500]
    (let [container (str "carly" number)]
      (hacks/saferun
        (core/shell! :docker :rm :-f :--volumes container))
      (logging/info "building container" container)
      (core/shell! :docker :run :--privileged :-d :--name container image)
      (setup-ssh! container))))

(defn build-containers!
  [how-many image]
  (dorun (map #(build-container! image %) (range how-many))))

(defn destroy-containers!
  []
  (let [current-containers (container-names)]
    (when-not (empty? current-containers)
      (logging/info "destroy containers:" current-containers)
      (docker! :rm :-f :--volumes current-containers))))

(defn setup!
  [test]
  (destroy-containers!)
  (build-containers! HOW-MANY "scylladb/scylla")
  (Thread/sleep 5000)
  (test))

(defn container-ips
  []
  (->> (container-names)
       (docker! :inspect)
       :out
       (yaml/parse-string)
       (map #(->> % :NetworkSettings :IPAddress))
       (map keyword)
       vec))
