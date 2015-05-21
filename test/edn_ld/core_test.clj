(ns edn-ld.core-test
  (:require [clojure.test :refer :all]
            [clojure.string :as string]
            [schema.core :as s]
            [edn-ld.core :refer :all]))

; These macros are borrowed from
; https://github.com/Prismatic/schema/blob/master/test/clj/schema/test_macros.clj
(defmacro valid!
  "Assert that x satisfies schema s, and the walked value is equal to the original."
  [s x]
  `(let [x# ~x] (~'is (= x# ((s/start-walker s/walker ~s) x#)))))

(defmacro invalid!
  "Assert that x does not satisfy schema s, optionally checking the stringified return value"
  ([s x]
   `(~'is (s/check ~s ~x)))
  ([s x expected]
   `(do (invalid! ~s ~x)
        (sm/if-cljs nil (~'is (= ~expected (pr-str (s/check ~s ~x))))))))

;; For now, any string is a valid IRI.

(deftest test-iris
  (valid! IRI "http://example.com")
  (valid! IRI "foo")
  (invalid! IRI 123)
  (invalid! IRI :foo))

(def context
  {:ex  "http://example.com/"
   nil  :ex
   :foo :ex:foo})

(deftest test-context
  (valid! Context context))

(deftest test-expand
  (are [x y]  (= (expand context x) y)
    nil     nil
    123     123
    :foo    "http://example.com/foo"
    :ex     "http://example.com/"
    :ex:bar "http://example.com/bar"
    :baz    "http://example.com/baz"))

(deftest test-contract
  (are [x y] (= (contract context x) y)
    nil       nil
    123       123
    "foo"     "foo"
    "http://example.com/foo" :foo
    "http://example.com/bar" :bar))

(deftest test-literals
  (invalid! Literal nil)
  (invalid! Literal "foo")
  (invalid! Literal 123)
  (invalid! Literal {})
  (invalid! Literal [])
  (invalid! Literal {:value 123})
  (invalid! Literal {:value "foo" :type 123})
  (invalid! Literal {:value "foo" :type "bar" :lang "en"})
  (valid! Literal {:value "foo"})
  (valid! Literal {:value "foo" :type "bar"})
  (valid! Literal {:value "foo" :type :rdf:langString :lang "en"})
  (is (= (literal "foo")
         {:value "foo"}))
  (is (= (literal 123)
         {:value "123" :type :xsd:integer}))
  (is (= (literal "foo" "bar")
         {:value "foo" :type "bar"}))
  (is (= (literal "foo" "@bar")
         {:value "foo" :type :rdf:langString :lang "bar"})))

(deftest test-flatten-triples
  (is (= (flatten-triples :s :p :o)
         [[:s :p :o]]))
  (is (= (flatten-triples :s :p {:value "o"})
         [[:s :p "o" :xsd:string]]))
  (is (= (flatten-triples :s :p {:value "o" :type :foo})
         [[:s :p "o" :foo]]))
  (is (= (flatten-triples :s :p {:value "o" :lang "en"})
         [[:s :p "o" :rdf:langString "en"]])))

(deftest test-objectify
  (are [x y] (= (objectify x) y)
    nil   nil
    :foo  :foo
    "foo" {:value "foo"}
    123   {:value "123" :type :xsd:integer})
  (is (= (objectify {"foo" :foo} "foo") :foo)))

(deftest test-triplify
  (is (= (triplify {:subject-iri :subject
                    :predicate   :object})
         [[:subject :predicate :object]]))
  (is (= (triplify {:subject-iri :subject
                    :predicate   "Object"})
         [[:subject :predicate "Object" :xsd:string]]))
  (is (= (triplify {"Object" :object}
                   {:subject-iri :subject
                    :predicate   "Object"})
         [[:subject :predicate :object]])))

(deftest test-quadruplify
  (is (= (quadruplify {:subject-iri :subject
                       :graph-iri   :graph
                       :predicate   :object})
         [[:graph :subject :predicate :object]]))
  (is (= (quadruplify {:subject-iri :subject
                       :graph-iri   :graph
                       :predicate   "Object"})
         [[:graph :subject :predicate "Object" :xsd:string]]))
  (is (= (quadruplify {"Object" :object}
                      {:subject-iri :subject
                       :graph-iri   :graph
                       :predicate   "Object"})
         [[:graph :subject :predicate :object]])))

(deftest test-subjectify
  (is (= (subjectify
          [[:subject :predicate :object]
           [:subject :predicate "Object" :xsd:string]])
         {:subject {:predicate #{:object {:value "Object"}}}})))

(deftest test-graphify
  (is (= (graphify
          [[:graph :subject :predicate :object]
           [:graph :subject :predicate "Object" :xsd:string]])
         {:graph {:subject {:predicate #{:object {:value "Object"}}}}})))

(deftest test-flatten-subjects
  (is (= (set (flatten-subjects
               {:subject {:predicate #{:object {:value "Object"}}}}))
         #{[:subject :predicate :object]
           [:subject :predicate "Object" :xsd:string]})))

(deftest test-flatten-graphs
  (is (= (set (flatten-graphs
               {:graph {:subject {:predicate #{:object {:value "Object"}}}}}))
         #{[:graph :subject :predicate :object]
           [:graph :subject :predicate "Object" :xsd:string]})))
