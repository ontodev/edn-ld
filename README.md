# EDN-LD

EDN-LD is a set of conventions and a library for working with [Linked Data (LD)](http://linkeddata.org) using [Extensible Data Notation (EDN)](https://github.com/edn-format/edn) and the [Clojure programming language](http://clojure.org). EDN-LD builds on EDN and [JSON-LD](http://json-ld.org), but is not otherwise affiliated with those projects.

This project is in early development!


## Linked Data

Linked data is an approach to working with data on the Web:

- instead of tables we have graphs -- networks of data
- instead of rows we have resources -- nodes in the graph
- the values in our cells are also nodes -- either resources or literals: strings, numbers, dates
- and instead of columns we have named relations that link nodes to form the graph

Just think of your tables as big sets of row-column-cell "triples". By switching from rigid tables to flexible graphs, we can easily merge data from across the web.

Linked data is simple. The tools for working with it are powerful: big Java libraries such as [Jena](https://jena.apache.org), [Sesame](http://rdf4j.org), [OWLAPI](http://owlapi.sourceforge.net), etc. Unfortunately, most of the tools are not simple.

EDN-LD is a simple linked data tool.


## Install

EDN-LD is a Clojure library. The easiest way to get started is to use [Leiningen](http://leiningen.org) and add this to your `project.clj` dependencies:

    [edn-ld "0.1.0"]


## Tutorial

Try out EDN-LD by cloning this project and starting a REPL:

    $ git clone https://github.com/ontodev/edn-ld.git
    $ cd edn-ld
    $ lein repl
    nREPL server started ...
    user=> (use 'edn-ld.core 'edn-ld.common)
    nil
    user=> (require '[clojure.string :as string])
    nil
    user=> "Ready!"
    Ready!

Say we have a (very small) table of books and their authors called `books.tsv`:

Title     | Author
----------|-------
The Iliad | Homer

A common way to represent this in Clojure is as a list of maps, with the column names as the keys. We can `slurp` and split the data until we get what we want:

    user=> (defn split-row [row] (string/split row #"\t"))
    #'user/split-row
    user=> (defn read-tsv [path] (->> path slurp string/split-lines (drop 1) (mapv split-row)))
    #'user/read-tsv
    user=> (def rows (read-tsv "test-resources/books.tsv"))
    #'user/rows
    user=> rows
    [["The Iliad" "Homer"]]

Now we use `zipmap` to associate keys with values:

    user=> (def data (mapv (partial zipmap [:title :author]) rows))
    #'user/data
    user=> data
    [{:title "The Iliad", :author "Homer"}]

We have the data in a convenient shape, but what does it mean? Well, there's some resource that has "The Iliad" as its title, and some guy named "Homer" who is the author of that resource. We also know from the context that it's a book.

The first thing to do is give names to our resources. Linked data names are [IRIs](https://en.wikipedia.org/wiki/Internationalized_resource_identifier): globally unique identifiers that generalize the familiar URL you see in your browser's location bar. We can use some standard names for our relations from the [Dublin Core](http://dublincore.org) metadata standard, and we'll make up some more.

Name      | IRI
----------|-----------------------------------------
title     | `http://purl.org/dc/elements/1.1/title`
author    | `http://purl.org/dc/elements/1.1/author`
The Iliad | `http://example.com/the-iliad`
Homer     | `http://example.com/Homer`
book      | `http://example.com/book`

IRIs can be long and cumbersome, so let's define some prefixes that we can use to shorten them:

Prefix | IRI
-------|-----------------------------------
`dc`   | `http://purl.org/dc/elements/1.1/`
`ex`   | `http://example.com/`

The `ex` prefix will be our default. We use strings for full IRIs and keywords when we're using some sort of contraction.

IRI                                      | Contraction
-----------------------------------------|------------
`http://purl.org/dc/elements/1.1/title`  | `:dc:title`
`http://purl.org/dc/elements/1.1/author` | `:dc:author`
`http://example.com/the-iliad`           | `:the-iliad`
`http://example.com/Homer`               | `:Homer`
`http://example.com/book`                | `:book`

We'll put this naming information in a *context* map:

    user=> (def context {:dc "http://purl.org/dc/elements/1.1/", :ex "http://example.com/", nil :ex, :title, :dc:title, :author :dc:author})
    #'user/context

The `nil` key indicates the default prefix `:ex`. Now we can use the context to expand contractions and to contract IRIs:

    user=> (expand context :title)
    http://purl.org/dc/elements/1.1/title
    user=> (expand context :Homer)
    http://example.com/Homer
    user=> (contract context "http://purl.org/dc/elements/1.1/title")
    :title
    user=> (contract context "http://purl.org/dc/elements/1.1/foo")
    :dc:foo
    user=> (expand-all context data)
    [{"http://purl.org/dc/elements/1.1/title" "The Iliad", "http://purl.org/dc/elements/1.1/author" "Homer"}]

Sometimes we also want to *resolve* a name to an IRI. We can define a resources map from string to IRIs or contractions:

    user=> (def resources {"Homer" :Homer, "The Iliad" :the-iliad})
    #'user/resources

Now it's time to convert our book data to "triples", i.e. statements about things to put in our graph. A triple consists of a subject, a predicate, and an object:

- the subject is the name of a resource: an IRI
- the predicate is the name of a relation: also an IRI
- the object can either be an IRI or literal data.

We represent an IRI with a string, or a contracted IRI with a keyword. We represent literal data as a map with special keys:

- `:value` is the string value ("lexical value") of the data, e.g. "The Iliad", "100.31"
- `:type` is the IRI of a data type, with `xsd:string` as the default
- `:lang` is an optional language code, e.g. "en", "en-uk"

The `literal` function is a convenient way to create a literal map:

    user=> (literal "The Iliad")
    {:value "The Iliad"}
    user=> (literal 100.31)
    {:value "100.31", :type :xsd:float}

The `objectify` function takes a resource map and a value, and determines whether to convert the value to an IRI or a literal:

    user=> (objectify resources "Some string")
    {:value "Some string"}
    user=> (objectify resources "Homer")
    :Homer

Now we can treat each map as a set of statements about a resources, and `triplify` it to a lazy sequence of triples. The format will be "flat triples", a list with slots for: subject, predicate, object, type, and lang. The `triplify` function takes our resource map, they keyword to use for setting the subject, and a map of data. It returns a lazy sequence of flat triples.

    user=> (def triples (triplify resources :the-iliad (first data)))
    #'user/triples
    user=> (vec triples)
    [[:the-iliad :title "The Iliad" :xsd:string] [:the-iliad :author :Homer]]

You'll notice that the subject `:the-iliad` is repeated here. With a larger set of triples the redundancy will be greater. Instead we can use a nested data structure:

    user=> (def subjects (subjectify triples))
    #'user/subjects
    user=> subjects
    {:the-iliad {:title #{{:value "The Iliad"}}, :author #{:Homer}}}

From the inside out, it works like this:

- object-set: the set of object with the same subject and predicate
- predicate-map: a map from predicate IRIs to object sets
- subject-map: map from subject IRIs to predicate sets

We work with these data structures like any other Clojure data, using `merge`, `assoc`, `update`, and the rest of the standard Clojure toolkit:

    user=> (def context+ (merge default-context context))
    #'user/context+
    user=> (def subjects+ (assoc-in subjects [:the-iliad :rdf:type] #{:book}))
    #'user/subjects+

Finally, we can write to standard linked data formats, such as RDFXML:

    user=> (edn-ld.rdfxml/write-string context+ (expand-all context+ subjects+))
    <?xml version='1.0' encoding='UTF-8'?>
    <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#" xmlns:rdfs="http://www.w3.org/2000/01/rdf-schema#" xmlns:xsd="http://www.w3.org/2001/XMLSchema#" xmlns:owl="http://www.w3.org/2002/07/owl#" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:ex="http://example.com/">
        <ex:book rdf:about="http://example.com/the-iliad">
            <dc:title>The Iliad</dc:title>
            <dc:author rdf:resource="http://example.com/Homer"/>
         </ex:book>
    </rdf:RDF>


## To Do

- generalize to RDF datasets (collections of graphs)
- finish RDFXML reader and writer
- use Apache Jena for reading and writing?


## License

Copyright © 2015 James A. Overton

Distributed under the BSD 3-Clause License.
