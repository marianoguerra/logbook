(ns logbook.store
  (:refer-clojure :exclude [remove replicate get])
  (:require [cljsjs.pouchdb :as pdb]))

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

(defn replicate [{db :db} source target & options]
  (.replicate db source target (clj->js (or options {}))))

(defn sync [{db :db} source target & options]
  (.sync db source target (clj->js (or options {}))))

(defn query [{db :db} query options callback]
  (.query db (clj->js query) (clj->js options) callback))


(defn setup-db [db-obj]
  (let [map-fun "function mapFun(doc) { emit([doc['book-id'], doc.time]); }"
        design-doc {:_id "_design/logbook", :views {:entries {:map map-fun}}}]
    (put db-obj design-doc prn)))
