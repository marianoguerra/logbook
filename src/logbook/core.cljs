(ns logbook.core
  (:require [om.core :as om]

            [logbook.store :as store]

            [om-bootstrap.input :as i]
            [om-bootstrap.random :as r]
            [om-bootstrap.button :as b]
            [om-bootstrap.nav :as n]
            [om-bootstrap.table :refer [table]]

            [clojure.walk :as clj-walk]

            [cognitect.transit :as t]

            logbook.all-highlighters
            [logbook.entry :refer [glyph entry-formatters entry date-now
                                   format-timestamp entry-formatters-shortcuts]]
            [om-tools.dom :as dom :include-macros true]))

(enable-console-print!)

(def writer (t/writer :json))
(def reader (t/reader :json))
(def search-path [:ui :search])

(defonce app-state (atom {:books nil
                          :book nil
                          :ui {:new-book {:title "" :author ""}
                               :sync {:visible? false :local "" :remote ""}}}))

(defn set-text [state txt]
  (om/update! state :text txt))

(defn set-type [state type]
  (om/update! state :type type))

(defn entry-option [default [type {:keys [label shortcut]}]]
  (dom/option {:value (name type)}
              label (when shortcut (str " (" shortcut ")"))))

(defn create-book [title author db callback]
  (let [now (date-now)
        book {:created now :edited now :title title :author author :t store/type-book}]
    (store/post db book callback)))

(extend-type js/NodeList
    ISeqable
      (-seq [array] (array-seq array 0)))

(defn qsa [selector]
  (.querySelectorAll js/document selector))

(defn init-highlight []
  (doseq [node (qsa "pre code")]
    (.highlightBlock js/hljs node)))

(defn create-entry [state db]
  (let [{:keys [type text id]} state
        parse (get-in entry-formatters [type :parser])
        now (date-now)
        {:keys [ok? error reason value]} (parse text)]
    (if ok?
      (let [entry {:type type :value value :created now :edited now
                   :book-id id :t store/type-entry}]
        (store/post db entry prn)
        (om/update! state :input-error nil)
        (om/transact! state :entries #(conj % entry))
        (set-text state ""))

      (om/update! state [:input-error] reason))
    ; TODO: allow types to specify a function to call after creation
    (when (= type "code")
      (.setTimeout js/window init-highlight 100))))

(defn event-value [event]
  (aget event "target" "value"))

(defn update-textarea-height [state txt]
  (let [rows (count (clojure.string/split txt #"\n"))]
    (om/update! state :textarea-height (min (inc rows) 25))))

(defn on-textarea-change [event state]
  (let [value (event-value event)]
    (update-textarea-height state value)
    (if (and (= (count value) 2) (= (first value) \#))
      (if-let [new-type (get entry-formatters-shortcuts (second value))]
        (do
          (set-type state new-type)
          (set-text state ""))

        (set-text state value))

      (set-text state value))))

(defn on-textarea-key-down [event state db]
  (let [ctrl-pressed (aget event "ctrlKey")
        key-code (aget event "keyCode")]
    (when (and ctrl-pressed (= key-code 13))
      (create-entry state db))))

(defn logbook-input [state db]
  (let [on-change #(on-textarea-change % state)
        on-key-down #(on-textarea-key-down % state db)
        on-type-change #(set-type state (event-value %))
        on-create #(create-entry state db)
        {selected :type text :text input-error :input-error
         textarea-height :textarea-height} state]

    (dom/form {:class "logbook-input"}
              (i/input {:type "select" :on-change on-type-change :value selected}
                       (map #(entry-option selected %)
                            (sort-by (fn [[_ val]] (:label val))
                                     entry-formatters)))
              (i/input {:type "textarea"
                        :on-change on-change
                        :on-key-down on-key-down
                        :rows textarea-height
                        :value text})
              (when input-error
                (r/alert {:class "input-error" :bs-style "danger"} input-error))
              (dom/div {:class "buttons"}
                       (b/button {:bs-style "primary" :on-click on-create} "Create")))))

(defn entry-to-search-str [{:keys [type value] :as data}]
  (let [{search-keys :search-keys} (get entry-formatters type)
        fields (map (fn [key] (let [field (key value)]
                                (if (nil? field) "" (str field))))
                                search-keys)
        entry-str (clojure.string/join " " fields)]
    (.toLowerCase entry-str)))

(defn get-search-term [data]
  (let [search-val (get-in data [:ui :search])]
    (if (nil? search-val) "" (.toLowerCase search-val))))

(defn filter-books [data entries]
  (let [search (get-search-term data)]
    (filter (fn [{:keys [title author]}]
              (let [entry-str (.toLowerCase (str title " " author))
                    match (or (empty? search) (not= (.indexOf entry-str search) -1))]
                match))
            entries)))

(defn filter-search [data entries]
  (let [search (get-search-term data)]
    (filter (fn [entry]
              (let [entry-str (entry-to-search-str entry)
                    match (or (empty? search) (not= (.indexOf entry-str search) -1))]
                match))
            entries)))

(defn on-change-update [state path]
  (fn [event]
    (let [value (event-value event)]
      (om/update! state path value))))

(defn search-box [data]
  (let [search (get-in data search-path)]
    (i/input {:type "text" :value search
              :on-input (on-change-update data search-path)
              :addon-before (glyph  "search")})))

(defn logbook [{:keys [title author created edited entries] :as state} db]
  (dom/div {:class "logbook"}
           (dom/h1 title)
           (dom/p {:class "logbook-data"} "by " (dom/strong author)
                  " created " (format-timestamp created)
                  " edited " (format-timestamp edited))
           (search-box state)
           (dom/div {:class "entries"}
                    (om/build-all entry (filter-search state entries)))
           (dom/hr)
           (logbook-input state db)))


(defn results->clj [callback]
  (fn [err res]
    (let [rows (aget res "rows")
          docs (map #(aget % "doc") rows)
          clj-docs (js->clj docs)
          clj-docs-kw (clj-walk/keywordize-keys clj-docs)
          clj-docs-vec (vec clj-docs-kw)]
      (callback clj-docs-vec))))

(def small-timestamp 0)
(def big-timestamp 99999999999999)

(defn load-book-entries [state db id]
  (store/query db "logbook/entries" {:include_docs true
                                     :startkey [id small-timestamp]
                                     :endkey [id big-timestamp]}

               (results->clj
                 (fn [docs]
                   (om/transact! state #(assoc-in % [:book :entries] docs))))))

(defn on-link-click [callback]
  (fn [e]
    (.preventDefault e)
    (callback)))

(defn logbook-entry [{:keys [created edited title author _id] :as entry} db state]
  (dom/tr {:class "logbook-entry" :key _id}
          (dom/td
            (dom/a {:href "#"
                    :on-click (on-link-click
                                (fn [_]
                                  (om/update! state :book
                                              {:author author
                                               :id _id
                                               :title title
                                               :created created})
                                  (load-book-entries state db _id)))}
                   title))
          (dom/td {:class "logbook-entry-author"} author)
          (dom/td {:class "logbook-entry-edited"} (format-timestamp created))
          (dom/td {:class "logbook-entry-edited"} (format-timestamp edited))))

(defn load-books [state db]
  (store/query db "logbook/books" {:include_docs true}
               (results->clj
                 (fn [docs]
                   (om/update! state :books docs)))))

(defn new-book-form [data db]
  (let [{:keys [title author]} (get-in data [:ui :new-book])
        title-key [:ui :new-book :title]
        author-key [:ui :new-book :author]
        on-create #(create-book title author db
                                (fn [err res]
                                  (when (nil? err)
                                    (do
                                      (om/update! data title-key "")
                                      (om/update! data author-key "")
                                      (load-books data db)))))]
    (dom/form {:class "form-box new-book-form"}
              (i/input {:type "text"
                        :value title
                        :addon-before (glyph  "pencil")
                        :on-input (on-change-update data title-key)})
              (i/input {:type "text"
                        :value author
                        :addon-before (glyph  "user")
                        :on-input (on-change-update data author-key)})
              (dom/div {:class "buttons"}
                       (b/button {:bs-style "primary" :on-click on-create}
                                 "Create " (glyph  "book"))))))

(defn sync-box [data db]
  (let [local-key [:ui :sync :local]
        remote-key [:ui :sync :remote]
        local store/db-name
        remote (get-in data remote-key)
        on-upload #(store/replicate local remote)
        on-download #(store/replicate remote local)
        on-sync #(store/sync local remote)]

  (dom/form {:class "form-box sync-form"}
            (i/input {:type "text"
                      :value remote
                      :addon-before "Remote"
                      :on-input (on-change-update data remote-key)})
            (dom/div {:class "buttons"}
                     (b/button {:bs-style "primary" :on-click on-upload}
                               "" (glyph  "arrow-up"))
                     (b/button {:bs-style "primary" :on-click on-download}
                               "" (glyph  "arrow-down"))
                     (b/button {:bs-style "primary" :on-click on-sync}
                               (glyph  "arrow-up")
                               (glyph  "arrow-down"))))))

(defn top-bar [data db]
  (let [sync-visible-path [:ui :sync :visible?]
        on-sync-click (on-link-click #(om/transact! data sync-visible-path not))
        sync-visible (get-in data sync-visible-path)]
    (dom/div
      (n/navbar
        {:brand (dom/a {:href "#"} "LogBook")}
        (n/nav
          {:collapsible? true}
          (n/nav-item {:key 1 :href "#" :on-click on-sync-click
                       :class (if sync-visible "nav-item-active" "")}
                      (glyph  "refresh"))))
      (when sync-visible
        (sync-box data db))

      (search-box data))))

(defn empty-logbook-list [state db]
  (dom/div
    (top-bar state db)
    (dom/h2 {:class "centered"} "No " (glyph  "book") "s")
    (new-book-form state db)))

(defn logbook-list [state db]
  (let [books (:books state)
        filtered-books (filter-books state books)]
    (if (empty? books)
      (do
        (load-books state db)
        (empty-logbook-list state db))

      (dom/div
        (top-bar state db)
        (table {:striped? true :bordered? true :condensed? true :hover? true}
               (dom/thead
                 (dom/th "Title")
                 (dom/th "Author")
                 (dom/th "Created")
                 (dom/th "Edited"))
               (map #(logbook-entry % db state) filtered-books))
        (new-book-form state db)))))

(defn main-ui [data db]
  (if-let [book (:book data)]
    (logbook book db)
    (logbook-list data db)))

(defn init []
  (let [db (store/new-db store/db-name)]
    (store/setup-db db)

    (om/root
      (fn [data owner]
        (reify om/IRender
          (render [_]
            (main-ui data db))))
      app-state
      {:target (. js/document (getElementById "root"))})))

(init)
