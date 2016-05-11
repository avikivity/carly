(ns scylla.configure)

(defn augment-command-line-arguments
  [arguments]
  (fn
    [text]
    (let [arguments-pattern #"(?m)^SCYLLA_ARGS=\"([^\"]+)\""
          arguments-line (re-find arguments-pattern text)
          new-argument-string (-> arguments-line
                                  second
                                  carly.core/command-line-arguments->map
                                  (merge arguments)
                                  carly.core/map->command-line-arguments)
          replacement (str "SCYLLA_ARGS=\"" new-argument-string "\"")]
      (clojure.string/replace text arguments-pattern replacement))))
