(ns cassandra.core-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer [pprint]]
            [cassandra.core :refer :all]
            [jepsen [core :as jepsen]
             [report :as report]]))

(defn with-scylla-parameters
  [test]
  (assoc test 
         :scylla {  :executable    "/root/source/scylla/build/release/scylla"
                    :config-path  "/root/source/scylla/conf/scylla.yaml" }))

(defn run-test!
  [test]
  (flush) ; Make sure nothing buffered
  (let [test (jepsen/run! (with-scylla-parameters test))]
    (is (:valid? (:results test)))))

(deftest null-test
  (run-test! (cassandra-test nil {})))
