(ns logbook.store
  (:refer-clojure :exclude [remove replicate get])
  (:require [cljsjs.pouchdb :as pdb]))

; this types are also written on setup-db map functions, change them in both places
(def type-entry "e")
(def type-book "b")
(def db-name "logbook")

(defn new-db [name]
  {:name name :db (js/PouchDB. name)})

(defn post [{db :db} doc callback]
  (.post db (clj->js doc) callback))

(defn put [{db :db} doc callback]
  (.put db (clj->js doc) callback))

(defn get [{db :db} id callback]
  (.get db id callback))

(defn remove [{db :db} id rev callback]
  (.remove db id callback))

(defn- add-handlers [p handlers]
  (reduce (fn [cp handler-key]
            (if-let [handler (clojure.core/get handlers handler-key)]
              (.on cp (name handler-key) handler)
              cp))
          p [:change :paused :active :denied :complete :error]))

(defn replicate [source target options]
  (add-handlers (.replicate js/PouchDB source target (clj->js (or options {})))
                (:handlers options)))

(defn sync [source target options]
  (add-handlers (.sync js/PouchDB source target (clj->js (or options {})))
                (:handlers options)))

(defn query [{db :db} query options callback]
  (.query db (clj->js query) (clj->js options) callback))

(defn setup-db [db-obj]
  (let [entries-map-fun "function mapFun(doc) { if (doc.t === 'e') { emit([doc['book-id'], doc.created]); }}"
        books-map-fun "function mapFun(doc) { if (doc.t === 'b') { emit(doc._id); }}"
        design-doc {:_id "_design/logbook"
                    :views {:entries {:map entries-map-fun}
                            :books {:map books-map-fun}}}]
    (put db-obj design-doc prn)))
