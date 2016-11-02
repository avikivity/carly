(ns cassandra.batch-test
  (:require [clojure.test :refer :all]
            [clojure.pprint :refer [pprint]]
            [cassandra.batch :refer :all]
            [cassandra.core-test :refer :all]
            [carly.docker]
            [jepsen [core :as jepsen]
             [report :as report]]))

(use-fixtures :each carly.docker/setup!)

;; Steady state cluster tests
(deftest ^:first-in-file ^:batch ^:steady batch-bridge
  (run-test! (bridge-test nil)))

(deftest ^:batch ^:steady batch-isolate-node
  (run-test! (isolate-node-test nil)))

(deftest ^:batch ^:steady batch-halves
  (run-test! (halves-test nil)))

(deftest ^:batch ^:steady batch-crash-subset
  (run-test! (crash-subset-test)))

(deftest ^:batch ^:steady batch-flush-compact
  (run-test! (flush-compact-test)))

(deftest ^:clock batch-clock-drift
  (run-test! (clock-drift-test)))

;; Bootstrapping tests
(deftest ^:batch ^:bootstrap batch-bridge-bootstrap
  (run-test! (bridge-test-bootstrap nil)))

(deftest ^:batch ^:bootstrap batch-isolate-node-bootstrap
  (run-test! (isolate-node-test-bootstrap nil)))

(deftest ^:batch ^:bootstrap batch-halves-bootstrap
  (run-test! (halves-test-bootstrap nil)))

(deftest ^:batch ^:bootstrap batch-crash-subset-bootstrap
  (run-test! (crash-subset-test-bootstrap nil)))

(deftest ^:clock ^:bootstrap batch-clock-drift-bootstrap
  (run-test! (clock-drift-test-bootstrap)))

(deftest ^:batch ^:bootstrap crash-subset-bootstrap-stress
  (run-test! (crash-subset-test-bootstrap-stress)))

;; Decommission tests
(deftest ^:batch ^:decommission batch-bridge-decommission
  (run-test! (bridge-test-decommission nil)))

(deftest ^:batch ^:decommission batch-isolate-node-decommission
  (run-test! (isolate-node-test-decommission nil)))

(deftest ^:batch ^:decommission batch-halves-decommission
  (run-test! (halves-test-decommission nil)))

(deftest ^:batch ^:decommission batch-crash-subset-decommission
  (run-test! (crash-subset-test-decommission)))

(deftest ^:clock batch-clock-drift-decommission
  (run-test! (clock-drift-test-decommission)))

;;; slow network tests
;; Steady state cluster tests
(deftest ^:batch ^:steady ^:slow-network batch-bridge-slow-net
  (run-test! (bridge-test-slow-net)))

(deftest ^:batch ^:steady ^:slow-network batch-isolate-node-slow-net
  (run-test! (isolate-node-test-slow-net)))

(deftest ^:batch ^:steady ^:slow-network batch-halves-slow-net
  (run-test! (halves-test-slow-net)))

;; Bootstrapping tests
(deftest ^:batch ^:bootstrap ^:slow-network batch-bridge-bootstrap-slow-net
  (run-test! (bridge-test-bootstrap-slow-net)))

(deftest ^:batch ^:bootstrap ^:slow-network batch-isolate-node-bootstrap-slow-net
  (run-test! (isolate-node-test-bootstrap-slow-net)))

(deftest ^:batch ^:bootstrap ^:slow-network batch-halves-bootstrap-slow-net
  (run-test! (halves-test-bootstrap-slow-net)))

;; Decommission tests
(deftest ^:batch ^:decommission ^:slow-network batch-bridge-decommission-slow-net
  (run-test! (bridge-test-decommission-slow-net)))

(deftest ^:batch ^:decommission ^:slow-network batch-isolate-node-decommission-slow-net
  (run-test! (isolate-node-test-decommission-slow-net)))

(deftest ^:batch ^:decommission ^:slow-network batch-halves-decommission-slow-net
  (run-test! (halves-test-decommission-slow-net)))
