(defproject org.cyverse/clj-icat-direct "2.9.8-SNAPSHOT"
  :description "A Clojure library for accessing the iRODS ICAT database directly."
  :url "https://github.com/cyverse-de/clj-icat-direct"
  :license {:name "BSD Standard License"
            :url "https://cyverse.org/license"}
  :deploy-repositories [["releases" :clojars]
                        ["snapshots" :clojars]]
  :plugins [[jonase/eastwood "1.4.3"]
            [lein-ancient "0.7.0"]
            [test2junit "1.4.4"]]
  :profiles {:repl {:dependencies [[cheshire "5.13.0"]]
                    :source-paths ["repl"]}}
  :dependencies [[org.clojure/clojure "1.11.3"]
                 [com.github.seancorfield/honeysql "2.6.1147"]
                 [korma "0.4.3"]
                 [org.postgresql/postgresql "42.7.3"]]
  :eastwood {:exclude-linters [:def-in-def :unlimited-use]})
