(ns edn-ld.jena-test
  (:require [clojure.test :refer :all]
            [clojure.string :as string]
            [edn-ld.jena :refer :all]
            [edn-ld.common :refer [rdf xsd]])
  (:import (org.apache.jena.riot Lang)
           (org.apache.jena.rdf.model ModelFactory)))

(deftest test-formats
  (are [x y] (= (get-format x) y)
    "turtle"             Lang/TURTLE
    "foo.ttl"            Lang/TURTLE
    "application/turtle" Lang/TURTLE
    Lang/TURTLE          Lang/TURTLE))

(def ex "http://example.com/")
(def test1-turtle
  "@prefix ex: <http://example.com/> .
   ex:subject ex:predicate \"Object\"@en ;
      ex:predicate ex:object .")

(def test1-edn
  [[(str ex "subject") (str ex "predicate") (str ex "object")]
   [(str ex "subject") (str ex "predicate") {:value "Object" :type "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString" :lang "en"}]])

(defn clean
  [s]
  (-> s
      (string/replace #"(?m)\s+" " ")
      string/trim))

(deftest test-triples
  (let [[prefixes triples] (read-triple-string test1-turtle "turtle")]
    (is (= prefixes {:base-iri ex :ex ex}))
    (is (= (set triples) (set test1-edn))))
  (is (= (clean (write-triple-string {:ex ex} test1-edn))
         (clean test1-turtle))))

(comment
  )

(def test2-trig
  "@prefix ex: <http://example.com/> .
ex:graph {
   ex:subject ex:predicate \"Object\"@en ;
      ex:predicate ex:object .
}")

(def test2-edn
  [[(str ex "graph") (str ex "subject") (str ex "predicate") (str ex "object")]
   [(str ex "graph") (str ex "subject") (str ex "predicate")
    {:value "Object" :type "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString" :lang "en"}]])

(deftest test-quads
  (let [[prefixes quads] (read-quad-string test2-trig "trig")]
    (is (= prefixes {:base-iri ex :ex ex}))
    (is (= (set quads) (set test2-edn))))
  (is (= (clean (write-quad-string {:ex ex} test2-edn))
         (clean test2-trig))))

(deftest test-blank
  (let [model (ModelFactory/createDefaultModel)
        node  (make-node model "_:foo")]
    (is (= (read-node node) "_:foo"))))
