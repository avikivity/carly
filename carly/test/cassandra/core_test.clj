(ns cassandra.core-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer [pprint]]
            [cassandra.core :refer :all]
            [cassandra.batch]
            [carly.hooks]
            [carly.checkers]
            [jepsen [core :as jepsen]
             [generator]
             [report :as report]]))


(defn run-test!
  [test]
  (flush) ; Make sure nothing buffered
  (carly.hooks/start! test)
  (let [test-run (jepsen/run! test)]
    (is (:valid? (:results test-run)))))

(deftest null-test
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
