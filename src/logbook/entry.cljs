(ns logbook.entry
  (:require [om.core :as om]

            [om-bootstrap.input :as i]
            [om-bootstrap.random :as r]
            [om-bootstrap.button :as b]
            [om-bootstrap.nav :as n]
            [om-bootstrap.table :refer [table]]

            [json-html.core :as json-html]
            [markdown.core :as md]
            [cljsjs.csv :as csv]
            [om-tools.core :refer-macros [defcomponent]]
            [om-tools.dom :as dom :include-macros true]))

(defn date-now []
  (.now js/Date))

(defn render-md [class-name txt]
  (dom/div {:class class-name
            :dangerouslySetInnerHTML {:__html (md/md->html txt)}}
           nil))

(defn format-timestamp [timestamp]
  (let [date (new js/Date timestamp)]
    (str (.toLocaleDateString date) " " (.toLocaleTimeString date))))

(defn glyph [glyph-name & {:keys [on-click]}]
  (r/glyphicon {:glyph glyph-name :on-click on-click}))

(defn base-entry [sub-class time icon body _id & {:keys [on-icon-click]}]
  (dom/div {:class (str "entry " (name sub-class)) :key (or _id (date-now))}
           (dom/div {:class "entry-header"}
                    (dom/div {:class "entry-title"}
                             (glyph  icon :on-click on-icon-click)
                             "")
                    (dom/div {:class "entry-time"} (format-timestamp time)))

           (dom/div {:class "entry-body"} body)))

(defn unknown-entry [type value time icon _id]
  (base-entry :unknown time nil (str "Unknown entry of type: " (name type)) _id))

(defn entry-error [state type {value :value} time icon _id]
  (base-entry :entry-error time icon value _id))

(defn entry-command [state type {:keys [input output]} time icon _id]
  (base-entry :entry-command time icon
              (dom/div {:class "command-wrapper"}
                (dom/div {:class "command-input"} input)
                (dom/pre {:class "command-output"} output)) _id))

(defn entry-output [state type {output :value} time icon _id]
  (base-entry "no-frame entry-output" time icon
              (dom/pre {:class "output"} output) _id))

(defn entry-link [state type {:keys [url title description]} time icon _id]
  (base-entry :entry-link time icon
              (dom/div {:class "link-wrapper"}
                (dom/p {:class "link"} (dom/a {:href url :target "_blank"} title))
                (render-md "link-description" description)) _id))

(defn entry-image [state type {:keys [url title description]} time icon _id]
  (base-entry :entry-link time icon
              (dom/div {:class "img-wrapper"}
                (when-not (empty? title)
                  (dom/h2 {:class "img-title"} title))

                (dom/p {:class "img"}
                       (dom/a {:href url :target "_blank"}
                              (dom/img {:src url :title description})))

                (when-not (empty? description)
                  (render-md "img-description" description))) _id))

(defn parse-json [txt]
  (.parse js/JSON txt))

(defn format-json [js-value]
  (.stringify js/JSON js-value nil 2))

(defn pretty-print-json [txt]
  (format-json (parse-json txt)))

(defn toggle-state-field [state field]
  (om/update! state field (not (get state field))))

(defn entry-json [state type {value :value} time icon _id]
  (base-entry :entry-json time icon
              (if (:raw state)
                (dom/textarea {:class "entry-json-raw" :value (pretty-print-json value)})
                (dom/div {:dangerouslySetInnerHTML
                          {:__html (json-html/json->html (parse-json value))}}
                         nil))
              _id
              :on-icon-click #(toggle-state-field state :raw)))

(defn entry-code [state type {:keys [lang code]} time icon _id]
  (base-entry :entry-code time icon
              (if (:raw state)
                (dom/textarea {:class "entry-code-raw" :value code})

                (dom/pre {:class "code-wrapper"}
                         (dom/code {:class lang} code)))
              _id
              :on-icon-click #(toggle-state-field state :raw)))

(defn entry-markdown [class-name]
  (fn [state type {value :value} time icon _id]
    (base-entry class-name time icon
                (render-md "md-content" value) _id)))

(defn entry-csv [state type {value :value} time icon _id]
  (base-entry :entry-csv time icon
              (if (:raw state)
                (dom/textarea {:class "entry-csv-raw" :value value})
                (table {:striped? true :bordered? true :condensed? true :hover? true}
                       (for [row (.parse js/CSV value)]
                         (dom/tr
                           (for [col row]
                             (dom/td col))))))
              _id
              :on-icon-click #(toggle-state-field state :raw)))

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
    {:ok? true :value {:value txt}}
    (catch js/Error e
      {:ok? false
       :error e
       :reason "Invalid Json Format"
       :code :invalid-format})))

(defn identity-parser [txt]
  {:ok? true :value {:value txt}})

(def entry-formatters
  {"error" {:fn entry-error
            :label "Error"
            :icon "alert"
            :shortcut \!
            :search-keys [:value]
            :parser identity-parser}
   "command" {:fn entry-command
              :label "Command"
              :icon "console"
              :shortcut \$
              :search-keys [:input :output]
              :parser parse-command}
   "code" {:fn entry-code
           :label "Code"
           :icon "file"
           :shortcut \c
           :search-keys [:lang :code]
           :parser  parse-code}
   "csv" {:fn entry-csv
           :label "CSV"
           :icon "th"
           :shortcut \,
           :search-keys [:value]
           :parser identity-parser}
   "link" {:fn entry-link
           :label "Link"
           :icon "link"
           :shortcut \l
           :search-keys [:url :title :description]
           :parser parse-link}
   "image" {:fn entry-image
            :label "Image"
            :icon "picture"
            :shortcut \i
            :search-keys [:url :title :description]
            :parser parse-image}
   "json" {:fn entry-json
           :label "JSON"
           :icon "cog"
           :search-keys [:value]
           :shortcut \{
           :parser entry-json-parse}
   "markdown" {:fn (entry-markdown :entry-markdown)
               :label "Markdown"
               :icon "pencil"
               :shortcut \#
               :search-keys [:value]
               :parser identity-parser}
   "success" {:fn (entry-markdown :entry-success)
               :label "Success"
               :icon "ok"
               :shortcut \s
               :search-keys [:value]
               :parser identity-parser}
   "failure" {:fn (entry-markdown :entry-failure)
               :label "Failure"
               :icon "remove"
               :shortcut \f
               :search-keys [:value]
               :parser identity-parser}
   "question" {:fn (entry-markdown :entry-question)
               :label "Question"
               :icon "question-sign"
               :shortcut \?
               :search-keys [:value]
               :parser identity-parser}
   "observation" {:fn (entry-markdown :entry-observation)
               :label "Observation"
               :icon "eye-open"
               :shortcut \o
               :search-keys [:value]
               :parser identity-parser}
   "output" {:fn entry-output
             :label "Output"
             :icon "menu-right"
             :shortcut \>
             :search-keys [:value]
             :parser identity-parser}})

; TODO: make entry-formatters an atom and watch for changes and update this
(def entry-formatters-shortcuts
  (let [shortcuts (map (fn [[k v]]
                             (when-let [shortcut (:shortcut v)]
                               [shortcut k]))
                           entry-formatters)
        shortcuts-map (into {} shortcuts)]
    shortcuts-map))

(defcomponent entry [{:keys [type value created edited _id] :as data} state]
  (render [_]
          (if-let [{:keys [fn icon]} (get entry-formatters type)]
            (fn data type value edited icon _id)
            (unknown-entry type value edited "" _id))))

