(defproject edn-ld "0.3.0-SNAPSHOT"
  :description "A simple linked data tool"
  :url "https://github.com/ontodev/edn-ld"
  :license {:name "BSD 3-Clause License"
            :url "http://opensource.org/licenses/BSD-3-Clause"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [prismatic/schema "0.4.2"]
                 [org.apache.jena/jena-arq "3.0.1"]
                 [org.codehaus.woodstox/woodstox-core-asl "4.3.0"]]
  :plugins [[lein-cljfmt "0.1.10"]])
