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
  (invalid! IRI :foo)
  )
