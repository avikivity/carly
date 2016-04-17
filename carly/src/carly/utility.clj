(ns carly.utility
   (:require [carly.core :as core]
             [carly.setups :as setups]))

(defn node-subset
  [size]
  (core/choose size (setups/default :nodes)))
