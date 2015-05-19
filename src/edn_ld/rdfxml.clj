(ns edn-ld.rdfxml
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [edn-ld.common :refer [rdf xsd default-context]])
  (:import (org.codehaus.stax2 XMLInputFactory2 XMLOutputFactory2
                               XMLStreamReader2 XMLStreamWriter2)))

;; WARNING: This code is work in progress!
;; Does not handle all of RDFXML, has not been optimized,
;; and has not been properly documented.


;; ## Clojure Type Hints
;;
;; Java is a strongly typed programming language, in which the type of every
;; variable and method is explicitly declared. Clojure is a dynamic language
;; in which types are usually inferred. The type inference process often
;; involves "reflection" into classes, and this tends to slow down Clojure code.
;;
;; Clojure can be almost as fast as native Java code, but we have to avoid
;; reflection. We avoid it by adding "type hints" to tell the Clojure compiler
;; what types to expect for input and return values. Metadata annotation on
;; functions and values provide the hints: `^QName`, `^XMLInputFactory2`, etc.

;; The code in this file is optimized for speed, so we tell the Clojure compiler
;; to `warn-on-reflection` and add type hints until those warnings go away.

;(set! *warn-on-reflection* true)

;; ## Factories
;;
;; The following functions are used to create factories, readers, writers,
;; and filters from the Woodstox library.

(defn create-input-factory
  "Create and return Woodstox XMLInputFactory2, with configuration."
  ^XMLInputFactory2 []
  (XMLInputFactory2/newInstance))

(defn create-output-factory
  "Create and return Woodstox XMLOutputFactory2."
  ^XMLOutputFactory2 []
  (XMLOutputFactory2/newInstance))

;; By default we will use the same shared input and output factories.

(def ^XMLInputFactory2 shared-input-factory (create-input-factory))
(def ^XMLOutputFactory2 shared-output-factory (create-output-factory))

(defn create-stream-reader
  "Create and return a Woodstox XMLStreamReader2 for a given path.
   An XMLInputFactory is optional."
  (^XMLStreamReader2 [path]
    (create-stream-reader shared-input-factory path))
  (^XMLStreamReader2 [^XMLInputFactory2 input-factory path]
    (.createXMLStreamReader input-factory (io/file path))))

(defn create-stream-writer
  "Create and return a Woodstox XMLStreamWriter2 for a given path.
   An XMLOutputFactory is optional."
  (^XMLStreamWriter2 [path]
    (create-stream-writer shared-output-factory path))
  (^XMLStreamWriter2 [^XMLOutputFactory2 output-factory path]
    (.createXMLStreamWriter output-factory (clojure.java.io/writer path))))

; To read an XML file lazily
; we create a wrapped function using lazy-seq.
; If the (.hasNext reader) fails, the
; http://stackoverflow.com/a/19656800

#_(defn lazy-read-ok
  [csv-file]
  (with-open [in-file (io/reader csv-file)]
    (frequencies (map #(nth % 2) (csv/read-csv in-file)))))

(defn lazy-read
  [path]
  (let [reader (create-stream-reader path)
        lazy (fn lazy [wrapped]
               (lazy-seq
                 (if (.hasNext reader)
                   (do (.next reader)
                       (cons (.getEventType reader) (lazy reader)))
                   (.close reader))))]
    (lazy reader)))

(defn advance
  [reader]
  (while (and (.hasNext reader) (not (.isStartElement reader)))
    (.next reader)))

(defn get-context
  [reader]
  (advance reader)
  (when (and (.isStartElement reader)
             (= (.getLocalName reader) "RDF"))
    (->> (range 0 (.getNamespaceCount reader))
         (map
           (fn [i]
             [(when-not (string/blank? (.getNamespacePrefix reader i))
                (.getNamespacePrefix reader i))
              (.getNamespaceURI reader i)]))
         (into {}))))

(defn get-element-iri
  [context reader]
  (when (.isStartElement reader)
    (str (get context (.getPrefix reader))
         (.getLocalName reader))))

(defn get-text
  [reader]
  (while (and (.hasNext reader) (not (.hasText reader)))
    (.next reader))
  (.getText reader))

(defn get-attribute-map
  "Given a reader at a start element,
   return a map from attribute IRIs to their values."
  [context reader]
  (when (.isStartElement reader)
    (->> (range 0 (.getAttributeCount reader))
         (map
           (fn [i]
             [(str (get context (.getAttributePrefix reader i))
                   (.getAttributeLocalName reader i))
              (.getAttributeValue reader i)]))
         (into {}))))

(defn read-triple
  "Read the triples attached to a given element."
  [context subject reader]
  (when (.isStartElement reader)
    (let [element   (get-element-iri context reader)
          attrs     (get-attribute-map context reader)
          about     (get attrs (str rdf "about"))
          resource  (get attrs (str rdf "resource"))
          datatype  (get attrs (str rdf "datatype") (str xsd "string"))
          lang      (get attrs "lang")]
      (cond
        ; RDF root element: return nothing
        (= element (str rdf "RDF"))
        [nil nil]
        ; Description element
        ; TODO: does not handle non-RDF attributes
        (and about (= element (str rdf "Description")))
        [about nil]
        ; type declaration element
        ; TODO: does not handle non-RDF attributes
        about
        [about [about (str rdf "type") element nil nil]]
        ; predicate resource assertion
        resource
        [subject [subject element resource nil nil]]
        ; predicate langString literal assertion
        lang
        [subject
         [subject element (get-text reader) (str rdf "langString") lang]]
        ; predicate typed literal assertion
        :else
        [subject [subject element (get-text reader) datatype nil]]
        ))))

(defn myread
  [path]
  (with-open [reader (create-stream-reader path)]
    (let [context (get-context reader)]
      (println context)
      (loop [triples []
             subject nil]
        (if (.hasNext reader)
          (let [[subject triple] (read-triple context subject reader)]
            (.next reader)
            (advance reader)
            (recur (conj triples triple) subject))
          triples)))))

(defn myshow
  [path]
  (->> path
       myread
       (remove nil?)
       (map println)
       doall))

(defn compact
  "Given a context map and an IRI,
   return a triple of the matched prefix, the namespace IRI, and the localname."
  [context iri]
  (if (string/blank? iri)
    ["" (get context nil) iri]
    (->> context
         (filter #(string? (val %)))
         (map (juxt val (comp name key)))
         (sort-by (comp count first) >)
         (filter #(.startsWith (str iri) (first %)))
         first
         ((fn [[uri prefix]]
            [prefix uri (string/replace-first (str iri) (str uri) "")])))))

(defn write-object
  "Given a single Object (IRI or Literal), write it to RDFXML."
  [writer prefix uri local object]
  (.writeCharacters writer "\n        ")
  (.writeStartElement writer prefix local uri)
  (when (string? object)
    (.writeAttribute writer "rdf" rdf "resource" object))
  (when (:type object)
    (when (and (not= (:type object) (str xsd "string"))
               (not= (:type object) (str rdf "langString")))
      (.writeAttribute writer "rdf" rdf "datatype" (:type object))))
  (when (:lang object)
    (.writeAttribute writer "xml" nil "lang" (:lang object)))
  (when (:value object)
    (.writeCharacters writer (:value object)))
  (.writeEndElement writer))

(defn write-predicate
  "Given a predicate and an object set, write all of the objects."
  [writer context predicate object-set]
  (let [[prefix uri local] (compact context predicate)]
    (doseq [object object-set]
      (write-object writer prefix uri local object))))

(defn pick-first-type
  "Given a predicate-map, pick out the first rdf:type,
   and return the pair of that type and a predicate map
   with that type removed."
  [predicate-map]
  (let [types (seq (get predicate-map (str rdf "type")))]
    [(first types)
     (if (seq (rest types))
       (assoc predicate-map
              (str rdf "type")
              (set (rest types)))
       (dissoc predicate-map (str rdf "type")))]))

(defn write-subject
  "Given a subject and predicate map,
   write an element for this subject,
   with children for all predicate-object pairs."
  [writer context subject predicate-map]
  (.writeCharacters writer "    ")
  (let [[type predicate-map] (pick-first-type predicate-map)]
    (if type
      (let [[prefix uri local] (compact context type)]
        (.writeStartElement writer prefix local uri))
      (.writeStartElement writer rdf "Description"))
    (.writeAttribute writer "rdf" rdf "about" subject)
    (when (seq predicate-map)
      (doseq [[predicate object-set]
              (seq (dissoc predicate-map (str rdf "type")))]
        (write-predicate writer context predicate object-set))
      (.writeCharacters writer "\n     ")))
  (.writeEndElement writer))

(defn write-subjects
  [writer context subject-map]
  (.writeStartDocument writer)
  (.writeCharacters writer "\n")
  (.writeStartElement writer "rdf" "RDF" rdf)
  (doseq [[prefix uri] (seq context)]
    (when (and (nil? prefix) (string? uri))
      (.writeDefaultNamespace writer uri))
    (when (and prefix (string? uri))
      (.writeNamespace writer (name prefix) uri)))
  (.writeCharacters writer "\n")
  (doseq [subject (butlast (keys subject-map))]
    (write-subject writer context subject (get subject-map subject))
    (.writeCharacters writer "\n\n"))
  (when (last (keys subject-map))
    (let [subject (last (keys subject-map))]
      (write-subject writer context subject (get subject-map subject))
      (.writeCharacters writer "\n")))
  (.writeEndElement writer)
  (.writeEndDocument writer)
  (.flush writer)
  (.close writer))

(defn write-file
  ([path subject-map]
   (write-file path default-context subject-map))
  ([path context subject-map]
   (write-subjects
     (create-stream-writer path)
     context
     subject-map)))

(defn write-string
  "Given an optional context and a subject map,
   return a string with the RDFXML representation."
  ([subject-map]
   (write-string default-context subject-map))
  ([context subject-map]
   (let [writer (java.io.StringWriter.)]
     (write-subjects
       (.createXMLStreamWriter shared-output-factory writer)
       context
       subject-map)
     (.toString writer))))

(defn mytest
  []
  (->> (myread "simple.owl")
       (remove nil?)
       edn-ld.core/subjectify
       (write-file "output.owl" default-context)))

