(ns cassandra.core-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer [pprint]]
            [cassandra.core :refer :all]
            [cassandra.batch]
            [carly.hooks]
            [carly.hacks]
            [carly.checkers]
            [carly.docker]
            [clojure.tools.logging :as logging]
            [jepsen [core :as jepsen]
             [generator]
             [report :as report]]))

(use-fixtures :each carly.docker/setup!)

(defn run-test!
  [test]
  (if (System/getenv "JUST_LIST")
    (logging/info (carly.hacks/testing-metadata-name))
    (do 
        (flush) ; Make sure nothing buffered
        (carly.hooks/start! test)
        (let [test-run (jepsen/run! test)]
          (is (:valid? (:results test-run)))))))

(deftest ^:sanity sanity-check
  (run-test! 
    (cassandra-test
      "sanity check" 
      {
       :client (cassandra.batch/batch-set-client)
       :generator (->> (jepsen.generator/clients (adds))
                       (jepsen.generator/delay 1)
                       (jepsen.generator/time-limit 2)) 
       :checker (jepsen.checker/compose 
                  {:happy  carly.checkers/happy
                   :verify-scylla-lives scylla.checkers/verify-scylla-lives})})))
