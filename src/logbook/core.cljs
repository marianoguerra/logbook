(ns logbook.core
  (:require [om.core :as om]

            [om-bootstrap.input :as i]
            [om-bootstrap.random :as r]
            [om-bootstrap.button :as b]

            [cognitect.transit :as t]

            [json-html.core :as json-html]
            [markdown.core :as md]
            [om-tools.core :refer-macros [defcomponent]]
            [om-tools.dom :as dom :include-macros true]))

(enable-console-print!)

(def writer (t/writer :json))
(def reader (t/reader :json))

(defonce app-state (atom {:title "My First LogBook"
                          :author "Mariano Guerra"
                          :created "Yesterday"
                          :type "json"
                          :text ""
                          :textarea-height 1
                          :input-error nil
                          :entries [{:type :error
                                     :time 1431200053442
                                     :value "File Not Found"}
                                    {:type :output
                                     :time 1431200053442
                                     :value "Compiling \"main.js\" from [\"src\"]...\nSuccessfully compiled \"main.js\" in 0.116 seconds."}
                                    {:type :command
                                     :time 1431200053442
                                     :value {:input "ls"
                                             :output ". foo bar"}}]}))

(defn format-timestamp [timestamp]
  (.toISOString (new js/Date timestamp)))

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

(defn entry-markdown [state type value time icon]
  (base-entry :entry-markdown time icon
              (render-md "md-content" value)))

(defn parse-command [txt]
  (let [[input output] (clojure.string/split txt #"\n" 2)]
    {:ok? true :value {:input input :output output}}))

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
  {:error {:fn entry-error
           :label "Error"
           :icon "alert"
           :shortcut \!
           :parser identity-parser}
   :command {:fn entry-command
             :label "Command"
             :icon "console"
             :shortcut \$
             :parser parse-command}
   :link {:fn entry-link
          :label "Link"
          :icon "link"
          :shortcut \l
          :parser parse-link}
   :image {:fn entry-image
           :label "Image"
           :icon "picture"
           :shortcut \i
           :parser parse-image}
   :json {:fn entry-json
          :label "JSON"
          :icon "cog"
          :shortcut \{
          :parser entry-json-parse}
   :markdown {:fn entry-markdown
              :label "Markdown"
              :icon "pencil"
              :shortcut \#
              :parser identity-parser}
   :output {:fn entry-output
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

(defn create-entry [state]
  (let [{:keys [type text]} state
        type-kw (keyword type)
        parse (get-in entry-formatters [type-kw :parser])
        now (.now js/Date)
        {:keys [ok? error reason value]} (parse text)]
    (if ok?
      (do
        (om/update! state :input-error nil)
        (om/transact! state :entries
                      #(conj % {:type type-kw :value value :time now}))
        (set-text state ""))

      (om/update! state [:input-error] reason))))

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

(defn on-textarea-key-down [event state]
  (let [ctrl-pressed (.-ctrlKey event)
        key-code (.-keyCode event)]
    (when (and ctrl-pressed (= key-code 13))
      (create-entry state))))

(defn logbook-input [state]
  (let [on-change #(on-textarea-change % state)
        on-key-down #(on-textarea-key-down % state)
        on-type-change #(set-type state (event-value %))
        on-create #(create-entry state)
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

(defn logbook [{:keys [title author created entries] :as state}]
  (dom/div {:class "logbook"}
          (dom/h1 title)
          (dom/p {:class "logbook-data"} "by " (dom/strong author) " created " (dom/em created))
          (dom/div {:class "entries"} (om/build-all entry entries))
          (dom/hr)
          (logbook-input state)))

(om/root
  (fn [data owner]
    (reify om/IRender
      (render [_]
        (logbook data))))
  app-state
  {:target (. js/document (getElementById "root"))})
