(ns cassandra.core-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer [pprint]]
            [cassandra.core :refer :all]
            [jepsen [core :as jepsen]
             [report :as report]]))


(defn run-test!
  [test]
  (flush) ; Make sure nothing buffered
  (let [test (jepsen/run! test)]
    (is (:valid? (:results test)))))

(deftest null-test
  (run-test! (cassandra-test "do-nothing" {})))
(deftest null-test-2
  (run-test! (cassandra-test "do-nothing-2" {})))
