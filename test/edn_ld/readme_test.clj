(ns edn-ld.readme-test
  (:require [clojure.test :refer :all]
            [clojure.string :as string]
            [edn-ld.core :refer :all]
            [edn-ld.common :refer :all]
            [edn-ld.rdfxml]))

;; Parse the README.md file for indented code blocks,
;; execute anything marked "user=>",
;; and compare it to the expected output.

(def prompt "user=> ")

(defn run-test
  [user expected]
  (let [command (string/replace user prompt "")
        actual  (-> command
                    read-string
                    eval
                    str
                    (string/replace #"^#'edn-ld.readme-test" "#'user"))]
    ;(println "C" command)
    ;(println "E" expected)
    ;(println "A" actual)
    ;(println (= actual expected))
    (is (= expected actual))))

(->> "README.md"
     slurp
     string/split-lines
     (filter #(.startsWith % "    "))
     (map #(string/replace % #"^    " ""))
     (remove #(re-find #"^(\-|\+|\*) " %)) ; remove nested unordered list items
     (remove #(re-find #"^(\$|;)" %)) ; remove shell prompts and comments
     (drop-while #(not (.startsWith % prompt)))
     (drop 4) ; ignore 'use' and 'require'
     (partition-by #(.startsWith % prompt))
     (map (partial string/join "\n"))
     (partition 2)
     (map (partial apply run-test))
     doall
     (apply = true))

