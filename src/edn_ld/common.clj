(ns edn-ld.common)

(def rdf "http://www.w3.org/1999/02/22-rdf-syntax-ns#")

(def rdfs "http://www.w3.org/2000/01/rdf-schema#")

(def xsd "http://www.w3.org/2001/XMLSchema#")

(def owl "http://www.w3.org/2002/07/owl#")

(def default-prefixes {:rdf rdf :rdfs rdfs :xsd xsd :owl owl})

(def default-context default-prefixes)
