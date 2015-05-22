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

            [json-html.core :as json-html]
            [markdown.core :as md]
            logbook.all-highlighters
            [cljsjs.csv :as csv]
            [om-tools.core :refer-macros [defcomponent]]
            [om-tools.dom :as dom :include-macros true]))

(enable-console-print!)
(set! (.-RELAXED js/CSV) true)

(def writer (t/writer :json))
(def reader (t/reader :json))

(defonce app-state (atom {:books nil
                          :book nil
                          :ui {:new-book {:title "" :author ""}
                               :sync {:visible? false :local "" :remote ""}}}))

(defn format-timestamp [timestamp]
  (let [date (new js/Date timestamp)]
    (str (.toLocaleDateString date) " " (.toLocaleTimeString date))))

(defn render-md [class-name txt]
  (dom/div {:class class-name
            :dangerouslySetInnerHTML {:__html (md/md->html txt)}}
           nil))

(defn base-entry [sub-class time icon body & {:keys [on-icon-click]}]
  (dom/div {:class (str "entry " (name sub-class))}
           (dom/div {:class "entry-header"}
                    (dom/div {:class "entry-title"}
                             (r/glyphicon {:glyph icon :on-click on-icon-click})
                             "")
                    (dom/div {:class "entry-time"} (format-timestamp time)))

           (dom/div {:class "entry-body"} body)))

(defn entry-error [state type value time icon]
  (base-entry :entry-error time icon value))

(defn entry-command [state type {:keys [input output]} time icon]
  (base-entry :entry-command time icon
              (dom/div {:class "command-wrapper"}
                (dom/div {:class "command-input"} input)
                (dom/pre {:class "command-output"} output))))

(defn entry-code [state type {:keys [lang code]} time icon]
  (base-entry :entry-code time icon
              (dom/pre {:class "code-wrapper"}
                (dom/code {:class lang} code))))

(defn entry-output [state type output time icon]
  (base-entry "no-frame entry-output" time icon
              (dom/pre {:class "output"} output)))

(defn entry-link [state type {:keys [url title description]} time icon]
  (base-entry :entry-link time icon
              (dom/div {:class "link-wrapper"}
                (dom/p {:class "link"} (dom/a {:href url :target "_blank"} title))
                (render-md "link-description" description))))

(defn entry-image [state type {:keys [url title description]} time icon]
  (base-entry :entry-link time icon
              (dom/div {:class "img-wrapper"}
                (when-not (empty? title)
                  (dom/h2 {:class "img-title"} title))

                (dom/p {:class "img"}
                       (dom/a {:href url :target "_blank"}
                              (dom/img {:src url :title description})))

                (when-not (empty? description)
                  (render-md "img-description" description)))))

(defn parse-json [txt]
  (.parse js/JSON txt))

(defn format-json [js-value]
  (.stringify js/JSON js-value nil 2))

(defn pretty-print-json [txt]
  (format-json (parse-json txt)))

(defn toggle-state-field [state field]
  (om/update! state field (not (get state field))))

(defn entry-json [state type value time icon]
  (base-entry :entry-json time icon
              (if (:raw state)
                (dom/textarea {:class "entry-json-raw" :value (pretty-print-json value)})
                (dom/div {:dangerouslySetInnerHTML
                          {:__html (json-html/json->html (parse-json value))}}
                         nil))
              :on-icon-click #(toggle-state-field state :raw)))

(defn entry-markdown [class-name]
  (fn [state type value time icon]
    (base-entry class-name time icon
                (render-md "md-content" value))))

(defn entry-csv [state type value time icon]
  (base-entry :entry-csv time icon
              (table {:striped? true :bordered? true :condensed? true :hover? true}
                     (for [row (.parse js/CSV value)]
                       (dom/tr
                         (for [col row]
                           (dom/td col)))))))

(defn parse-command [txt]
  (let [[input output] (clojure.string/split txt #"\n" 2)]
    {:ok? true :value {:input input :output output}}))

(defn parse-code [txt]
  (let [[lang code] (clojure.string/split txt #"\n" 2)]
    {:ok? true :value {:lang lang :code code}}))

(defn parse-link [txt]
  (let [[url title description] (clojure.string/split txt #"\n" 3)]
    {:ok? true :value {:url url
                       :title (or title url)
                       :description (or description "")}}))

(defn parse-image [txt]
  (let [[url title description] (clojure.string/split txt #"\n" 3)]
    {:ok? true :value {:url url
                       :title (clojure.string/trim (or title ""))
                       :description (clojure.string/trim (or description ""))}}))

(defn entry-json-parse [txt]
  (try
    (.parse js/JSON txt)
    {:ok? true :value txt}
    (catch js/Error e
      {:ok? false
       :error e
       :reason "Invalid Json Format"
       :code :invalid-format})))

(defn identity-parser [txt]
  {:ok? true :value txt})

(def entry-formatters
  {"error" {:fn entry-error
            :label "Error"
            :icon "alert"
            :shortcut \!
            :parser identity-parser}
   "command" {:fn entry-command
              :label "Command"
              :icon "console"
              :shortcut \$
              :parser parse-command}
   "code" {:fn entry-code
           :label "Code"
           :icon "file"
           :shortcut \c
           :parser  parse-code}
   "csv" {:fn entry-csv
           :label "CSV"
           :icon "th"
           :shortcut \,
           :parser identity-parser}
   "link" {:fn entry-link
           :label "Link"
           :icon "link"
           :shortcut \l
           :parser parse-link}
   "image" {:fn entry-image
            :label "Image"
            :icon "picture"
            :shortcut \i
            :parser parse-image}
   "json" {:fn entry-json
           :label "JSON"
           :icon "cog"
           :shortcut \{
           :parser entry-json-parse}
   "markdown" {:fn (entry-markdown :entry-markdown)
               :label "Markdown"
               :icon "pencil"
               :shortcut \#
               :parser identity-parser}
   "success" {:fn (entry-markdown :entry-success)
               :label "Success"
               :icon "ok"
               :shortcut \s
               :parser identity-parser}
   "failure" {:fn (entry-markdown :entry-failure)
               :label "Failure"
               :icon "remove"
               :shortcut \f
               :parser identity-parser}
   "question" {:fn (entry-markdown :entry-question)
               :label "Question"
               :icon "question-sign"
               :shortcut \?
               :parser identity-parser}
   "observation" {:fn (entry-markdown :entry-observation)
               :label "Observation"
               :icon "eye-open"
               :shortcut \o
               :parser identity-parser}
   "output" {:fn entry-output
             :label "Output"
             :icon "menu-right"
             :shortcut \>
             :parser identity-parser}})

; TODO: make entry-formatters an atom and watch for changes and update this
(def entry-formatters-shortcuts
  (let [shortcuts (map (fn [[k v]]
                             (when-let [shortcut (:shortcut v)]
                               [shortcut k]))
                           entry-formatters)
        shortcuts-map (into {} shortcuts)]
    shortcuts-map))

(defn set-text [state txt]
  (om/update! state :text txt))

(defn set-type [state type]
  (om/update! state :type type))

(defn unknown-entry [type value time icon]
  (base-entry :unknown time nil (str "Unknown entry of type: " (name type))))

(defcomponent entry [{:keys [type value time] :as data} state]
  (render [_]
          (if-let [{:keys [fn icon]} (get entry-formatters type)]
            (fn data type value time icon)
            (unknown-entry type value time ""))))

(defn entry-option [default [type {:keys [label shortcut]}]]
  (dom/option {:value (name type)}
              label (when shortcut (str " (" shortcut ")"))))

(defn date-now []
  (.now js/Date))

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
  (prn "init highlight")
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
  (.-value (.-target event)))

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
  (let [ctrl-pressed (.-ctrlKey event)
        key-code (.-keyCode event)]
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

(defn logbook [{:keys [title author created edited entries] :as state} db]
  (dom/div {:class "logbook"}
           (dom/h1 title)
           (dom/p {:class "logbook-data"} "by " (dom/strong author)
                  " created " (format-timestamp created)
                  " edited " (format-timestamp edited))
           (dom/div {:class "entries"} (om/build-all entry entries))
           (dom/hr)
           (logbook-input state db)))


(defn results->clj [callback]
  (fn [err res]
    (let [rows (.-rows res)
          docs (map #(.-doc %) rows)
          clj-docs (vec (clj-walk/keywordize-keys (js->clj docs)))]
      (callback clj-docs))))

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
  (dom/tr {:class "logbook-entry"}
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
                 (fn [docs] (om/update! state :books docs)))))

(defn on-change-update [state path]
  (fn [event]
    (let [value (event-value event)]
      (om/update! state path value))))

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
                        :addon-before (r/glyphicon {:glyph "pencil"})
                        :on-input (on-change-update data title-key)})
              (i/input {:type "text"
                        :value author
                        :addon-before (r/glyphicon {:glyph "user"})
                        :on-input (on-change-update data author-key)})
              (dom/div {:class "buttons"}
                       (b/button {:bs-style "primary" :on-click on-create}
                                 "Create " (r/glyphicon {:glyph "book"}))))))

(defn sync-box [data db]
  (let [local-key [:ui :sync :local]
        remote-key [:ui :sync :remote]
        local store/name
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
                               "" (r/glyphicon {:glyph "arrow-up"}))
                     (b/button {:bs-style "primary" :on-click on-download}
                               "" (r/glyphicon {:glyph "arrow-down"}))
                     (b/button {:bs-style "primary" :on-click on-sync}
                               (r/glyphicon {:glyph "arrow-up"})
                               (r/glyphicon {:glyph "arrow-down"}))))))


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
                      (r/glyphicon {:glyph "refresh"}))))
      (when sync-visible
        (sync-box data db)))))

(defn empty-logbook-list [state db]
  (dom/div
    (top-bar state db)
    (dom/h2 {:class "centered"}
            "No " (r/glyphicon {:glyph "book"}) "s")
    (new-book-form state db)))

(defn logbook-list [state db]
  (let [books (:books state)]
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
               (map #(logbook-entry % db state) books))
        (new-book-form state db)))))

(defn main-ui [data db]
  (if-let [book (:book data)]
    (logbook book db)
    (logbook-list data db)))

(defn init []
  (let [db (store/new-db store/name)]
    (store/setup-db db)

    (om/root
      (fn [data owner]
        (reify om/IRender
          (render [_]
            (main-ui data db))))
      app-state
      {:target (. js/document (getElementById "root"))})))

(init)
