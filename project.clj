(defproject logbook "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojurescript "0.0-2760"]
                 [org.omcljs/om "0.8.8"]
                 [prismatic/om-tools "0.3.11"]
                 [com.cognitect/transit-cljs "0.8.207"]
                 [json-html "0.2.8"]
                 [markdown-clj "0.9.66"]
                 [racehub/om-bootstrap "0.5.0"]]
  :plugins [[lein-cljsbuild "1.0.5"]]
  :cljsbuild {:builds [{:id "dev"
                        :source-paths ["src"]
                        :compiler {
                                   :main logbook.core
                                   :output-to "main.js"
                                   :output-dir "out"
                                   :optimizations :none
                                   :source-map true}}
                       {:id "release"
                        :source-paths ["src"]
                        :compiler {
                                   :main main.core
                                   :output-to "main.js"
                                   :optimizations :advanced
                                   :pretty-print false}}]}
)
