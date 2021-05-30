(defproject seed "0.1.0"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url  "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [com.jcraft/jsch "0.1.55"]
                 [integrant "0.8.0"]
                 [org.clojure/tools.logging "1.1.0"]
                 [com.climate/claypoole "1.1.4"]]
  :repl-options {:init-ns seed.core}
  :profiles {:uberjar {:aot          :all
                       :omit-source  true
                       :uberjar-name "seed-standalone.jar"}}
  :main seed.core)
