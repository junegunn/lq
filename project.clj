(defproject lq "0.1.0-SNAPSHOT"
  :description "A simple HTTP server for queuing lines of text"
  :url "https://github.com/junegunn/lq"
  :license {:name "MIT"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [ring/ring-core "1.5.1"]
                 [ring/ring-jetty-adapter "1.5.1"]
                 [ring/ring-defaults "0.2.3"]
                 [compojure "1.5.2"]
                 [org.clojure/tools.logging "0.3.1"]
                 [org.slf4j/slf4j-log4j12 "1.7.25"]]
  :plugins [[lein-ring "0.9.7"]]
  :main lq.core
  :ring {:handler lq.core/api
         :nrepl   {:start? true}}
  :uberjar-name "lq.jar"
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
