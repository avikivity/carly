(ns carly.utility
   (:require [carly.core :as core]
             [carly.setups :as setups]))

(defn node-subset
  [size]
  (let [default-setup (setups/default)]
    (core/choose size (default-setup :nodes))))
