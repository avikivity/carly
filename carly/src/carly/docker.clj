(ns carly.docker
  (:require 
    [clojure.string]
    [clj-yaml.core :as yaml]
    [clojure.tools.logging :as logging]
    [carly.core :as core]))

(def HOW-MANY 5)

(defn docker!
  [command containers]
  (->> (concat [:docker command] containers)
       (apply core/shell!)))

(defn container-names
  []
  (->> (core/shell! :docker :ps :--format "{{.Names}}")
       :out
       clojure.string/split-lines
       (filter #(re-find #"carly" %))))

(defn build-containers!
  [how-many image]
  (let [build-one! 
        (fn [number] 
          (let [name (str "carly" number)]
            (logging/info "building container" name)
            (core/shell!
              :docker
              :run
              :--privileged
              :-d
              :--name
              name
              image)
            (core/shell!
              :docker :cp :-L "public_key_rsa" (str name ":/root/.ssh/authorized_keys"))
            ))]
    (dorun (map build-one! (range how-many)))))

(defn destroy-containers!
  []
  (let [current-containers (container-names)]
    (when-not (empty? current-containers)
      (logging/info "destroy containers:" current-containers)
      (docker! :stop current-containers)
      (docker! :rm :--volumes current-containers))))

(defn setup!
  [test]
  (destroy-containers!)
  (build-containers! HOW-MANY "haarcuba/fromscylla:p2")
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
