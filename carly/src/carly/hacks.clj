(ns carly.hacks
  (:require
    [clojure.tools.logging :as logging]
    [jepsen.store]))

(defmacro saferun
  [& body]
  (let [e# nil]
    `(try (logging/info "SAFERUN" '~body)
          ~@body
          (catch Exception e#
            (logging/info "exception of type" (class e#) "for:" '~body)))))

(defn hack-save!
  "Writes a test to disk and updates latest symlinks. Returns test."
  [test]
  (->> [(future (saferun (jepsen.store/write-results! test)))
        (future (saferun (jepsen.store/write-history! test)))
        (future (saferun (jepsen.store/write-fressian! test)))
        (future (saferun (jepsen.store/update-symlinks! test)))]
       (map deref)
       dorun)
  test)

(def original-snarf-logs! jepsen.core/snarf-logs!)

(defn safe-snarf-logs!
  [test]
  (saferun (original-snarf-logs! test)))

(defn hack
  [namespace symbol value]
  (logging/info "hack: replacing" (str namespace "/" symbol))
  (intern namespace symbol value))

(hack 'jepsen.store 'save! hack-save!)
(hack 'jepsen.core 'snarf-logs! safe-snarf-logs!)
