(defproject edn-ld "0.3.0-SNAPSHOT"
  :description "A simple linked data tool"
  :url "https://github.com/ontodev/edn-ld"
  :license {:name "BSD 3-Clause License"
            :url "http://opensource.org/licenses/BSD-3-Clause"}
  :dependencies [[org.clojure/clojure "1.7.0-beta3"]
                 [prismatic/schema "0.4.2"]
                 [org.apache.jena/jena-arq "2.13.0"]
                 [org.codehaus.woodstox/woodstox-core-asl "4.3.0"]]
  :plugins [[lein-cljfmt "0.1.10"]])
