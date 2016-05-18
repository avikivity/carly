(defproject carly "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [ [org.clojure/clojure "1.7.0"]
                 [org.clojure/java.jmx "0.3.1"]
                 [clj-yaml "0.4.0"]
                 [clojurewerkz/cassaforte "2.1.0-beta1"]
                 [clj-ssh "0.5.14"]
                 [jkni/jepsen "0.0.7-SNAPSHOT"] ]
  :plugins      [ [test2junit "1.2.2"] ]
  :test2junit-output-dir "junit-report"
  :test2junit-run-ant true
  :test-selectors {:steady :steady
                   :bootstrap :bootstrap
                   :map :map
                   :set :set
                   :mv :mv
                   :batch :batch
                   :lwt :lwt
                   :decommission :decommission
                   :counter :counter
                   :clock :clock
                   :slow-network :slow-network
                   :sanity :sanity
                   :no-bootstrap (fn
                                   [metadata]
                                   (->> metadata
                                       :name
                                       str
                                       (re-find #"bootstrap")
                                       not))
                   :all (constantly true)})
