(ns edn-ld.jena
  (:require [clojure.string :as string]
            [clojure.java.io :as io]
            [edn-ld.common :refer [xsd default-prefixes]])
  (:import (java.io StringReader StringWriter)
           (com.hp.hpl.jena.graph Triple Node_URI Node_Blank Node_Literal)
           (com.hp.hpl.jena.sparql.core Quad)
           (org.apache.jena.riot.system StreamRDF)
           (com.hp.hpl.jena.rdf.model ModelFactory AnonId)
           (com.hp.hpl.jena.query DatasetFactory)
           (com.hp.hpl.jena.datatypes BaseDatatype)
           (org.apache.jena.riot RDFDataMgr RDFLanguages Lang)))


;; # Apache Jena

(defmulti read-node
  "Given a Jena Node, return an EDN-LD node."
  class)

(defmethod read-node :default
  [node]
  nil)

(defmethod read-node Node_URI
  [node]
  (.getURI node))

(defmethod read-node Node_Blank
  [node]
  (.getLabelString (.getBlankNodeId node)))

(defmethod read-node Node_Literal
  [node]
  (let [value (.getLiteralLexicalForm node)
        type  (.getLiteralDatatype node)
        type  (when type (.getURI type))
        lang  (.getLiteralLanguage node)]
    (merge
     {:value value}
     (when type
       (when (not= type (str xsd "string"))
         {:type type}))
     (when-not (string/blank? lang)
       {:lang lang}))))

(defmulti make-node
  "Given a model and an EDN-LD node, return a Jena Node."
  (fn [model node] (class node)))

(defmethod read-node :default
  [node]
  nil)

(defmethod make-node String
  [model node]
  (if (.startsWith node "_:")
    (.createResource model (AnonId. (.substring node 2)))
    (.createResource model node)))

(defmethod make-node java.util.Map
  [model {:keys [value type lang] :as node}]
  (cond
    lang
    (.createLiteral model value lang)
    type
    (.createTypedLiteral model value (BaseDatatype. type))
    :else
    (.createTypedLiteral model value (BaseDatatype. (str xsd "string")))))

(defn get-model
  "Given a PrefixMap and ExpandedTriples,
   return a model with the namespace prefixes and triples added."
  [prefixes triples]
  (let [model (ModelFactory/createDefaultModel)]
    (doseq [[prefix iri] (filter #(string? (val %)) prefixes)]
      (.setNsPrefix model (name prefix) iri))
    (doseq [[subject predicate object] triples]
      (.add
       model
       (make-node model subject)
       (.createProperty model predicate)
       (make-node model object)))
    model))

(defn get-triple-map
  "Given Quads, return a map from GraphNames to SubjectMaps."
  [quads]
  (reduce
   (fn [coll quad]
     (update-in
      coll
      [(first quad)]
      (fnil conj [])
      (->> quad (drop 1) vec)))
   {}
   quads))

(defn get-model-map
  "Given a PrefixMap and a map from graph names to Triples,
   return a map from graph names to Models."
  [prefixes triple-map]
  (->> triple-map
       (map (juxt key #(get-model prefixes (val %))))
       (into {})))

(defn get-dataset
  "Given a PrefixMap and ExpandedQuads,
   return a dataset with models for each of the graphs."
  [prefixes quads]
  (let [dataset (DatasetFactory/createMem)
        models  (->> quads get-triple-map (get-model-map prefixes))]
    (doseq [[graph model] (filter key models)]
      (.addNamedModel dataset graph model))
    (if (find models nil)
      (.setDefaultModel dataset (get models nil))
      (.setDefaultModel dataset (get-model prefixes nil)))
    dataset))

(defn get-format
  "Given a Lang, a format string, a content type, or a filename,
   try to return an RDF Lang (file format)."
  [format]
  (or (when (instance? Lang format) format)
      (when (string? format) (RDFLanguages/nameToLang format))
      (when (string? format) (RDFLanguages/contentTypeToLang format))
      (when (string? format) (RDFLanguages/filenameToLang format))
      (throw (Exception. (str "Could not determine format: " format)))))

(defn get-output
  "Given a StringWriter or potential output stream,
   return either a StringWriter or an OutputStream."
  [output]
  (if (instance? StringWriter output)
    output
    (io/output-stream output)))


;; # Read Triples

(defn stream-triples
  "Given atoms for a PrefixMap and a sequence for ExpandedTriples,
   return an instance of StreamRDF for collecting triples.
   Quads are ignored."
  [prefixes triples]
  (reify StreamRDF
    (^void start  [_])
    (^void triple [_ ^Triple triple]
      (swap!
       triples
       conj
       [(read-node (.getSubject triple))
        (read-node (.getPredicate triple))
        (read-node (.getObject triple))]))
    (^void quad   [_ ^Quad quad])
    (^void base   [_ ^String base]
      (swap! prefixes assoc :base-iri base)) ; TODO: handle base IRI
    (^void prefix [_ ^String prefix ^String iri]
      (swap!
       prefixes
       assoc
       (if (string/blank? prefix) nil (keyword prefix))
       iri))
    (^void finish [_])))

(defn read-triples
  "Given a source path, reader, or input stream,
   an optional format name, and an optional base IRI,
   return the pair of a PrefixMap and ExpandedTriples."
  ([source]
   (let [prefixes (atom {})
         triples (atom [])]
     (RDFDataMgr/parse (stream-triples prefixes triples)
                       source)
     [@prefixes @triples]))
  ([source format]
   (let [prefixes (atom {})
         triples (atom [])]
     (RDFDataMgr/parse (stream-triples prefixes triples)
                       source
                       (get-format format))
     [@prefixes @triples]))
  ([source base format]
   (let [prefixes (atom {})
         triples (atom [])]
     (RDFDataMgr/parse (stream-triples prefixes triples)
                       source
                       base
                       (get-format format))
     [@prefixes @triples])))

(defn read-triple-string
  "Given an input string, an optional base IRI, and a format name,
   return the pair of a PrefixMap and ExpandedTriples."
  ([input format]
   (read-triple-string input "http://example.com/" format))
  ([input base format]
   (read-triples (StringReader. input) base format)))


;; # Write Triples

(defn write-triples
  "Given a destination that can be used as an OutputStream or StringWriter,
   an optional format, a PrefixMap, and ExpandedTriples,
   write the triples to the destination and return them unchanged."
  ([dest prefixes triples]
   (write-triples
    dest
    (RDFLanguages/filenameToLang dest)
    prefixes
    triples))
  ([dest format prefixes triples]
   (with-open [output (get-output dest)]
     (RDFDataMgr/write
      output
      (get-model prefixes triples)
      (get-format format)))
   triples))

(defn write-triple-string
  "Given an optional format (defaults to Turtle),
   a PrefixMap, and ExpandedTriples,
   return a string representation in that format."
  ([prefixes triples]
   (with-open [writer (StringWriter.)]
     (write-triples writer (get-format "ttl") prefixes triples)
     (str writer)))
  ([format prefixes triples]
   (with-open [writer (StringWriter.)]
     (write-triples writer format prefixes triples)
     (str writer))))


;; # Read Quads

(defn stream-quads
  "Given atoms for a PrefixMap and a sequence for ExpandedQuads,
   return an instance of StreamRDF for collecting quads.
   Triples are ignored."
  [prefixes quads]
  (reify StreamRDF
    (^void start  [_])
    (^void triple [_ ^Triple triple])
    (^void quad   [_ ^Quad quad]
      (swap!
       quads
       conj
       [(read-node (.getGraph quad))
        (read-node (.getSubject quad))
        (read-node (.getPredicate quad))
        (read-node (.getObject quad))]))
    (^void base   [_ ^String base]
      (swap! prefixes assoc :base-iri base)) ; TODO: handle base IRI
    (^void prefix [_ ^String prefix ^String iri]
      (swap!
       prefixes
       assoc
       (if (string/blank? prefix) nil (keyword prefix))
       iri))
    (^void finish [_])))

(defn read-quads
  "Given a source path, reader, or input stream,
   an optional format name, and an optional base IRI,
   return the pair of a PrefixMap and ExpandedQuads."
  ([source]
   (let [prefixes (atom {})
         quads (atom [])]
     (RDFDataMgr/parse (stream-quads prefixes quads)
                       source)
     [@prefixes @quads]))
  ([source format]
   (let [prefixes (atom {})
         quads (atom [])]
     (RDFDataMgr/parse (stream-quads prefixes quads)
                       source
                       (get-format format))
     [@prefixes @quads]))
  ([source base format]
   (let [prefixes (atom {})
         quads (atom [])]
     (RDFDataMgr/parse (stream-quads prefixes quads)
                       source
                       base
                       (get-format format))
     [@prefixes @quads])))

(defn read-quad-string
  "Given an input string, an optional base IRI, and a format name,
   return the pair of a PrefixMap and ExpandedQuads."
  ([input format]
   (read-quad-string input "http://example.com/" format))
  ([input base format]
   (read-quads (StringReader. input) base format)))


;; # Write Quads

(defn write-quads
  "Given a destination that can be used as an OutputStream or StringWriter,
   an optional PrefixMap, an optional format, and ExpandedQuads,
   write the quads to the destination and return them unchanged."
  ([dest quads]
   (write-quads dest default-prefixes quads))
  ([dest prefixes quads]
   (write-quads
    dest
    (RDFLanguages/filenameToLang dest)
    prefixes
    quads))
  ([dest format prefixes quads]
   (with-open [output (get-output dest)]
     (RDFDataMgr/write
      output
      (get-dataset prefixes quads)
      (get-format format)))
   quads))

(defn write-quad-string
  "Given an optional PrefixMap, and optional format (defaults to Trig),
   and ExpandedQuads, return a string representation in that format."
  ([quads]
   (write-quad-string default-prefixes quads))
  ([prefixes quads]
   (with-open [writer (StringWriter.)]
     (write-quads writer (get-format "trig") prefixes quads)
     (str writer)))
  ([format prefixes quads]
   (with-open [writer (StringWriter.)]
     (write-quads writer format prefixes quads)
     (str writer))))
