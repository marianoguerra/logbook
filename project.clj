(defproject logbook "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0-RC1"]
                 [org.clojure/clojurescript "0.0-3297"]
                 [org.omcljs/om "0.8.8"]
                 [prismatic/om-tools "0.3.11"]
                 [com.cognitect/transit-cljs "0.8.215"]
                 [cljsjs/pouchdb "3.5.0-0"]
                 [cljsjs/highlight "8.4-0"]
                 [cljsjs/csv "1.1.1-0"]
                 [json-html "0.2.8"]
                 [markdown-clj "0.9.66"]
                 [racehub/om-bootstrap "0.5.0"]]
  :plugins [[lein-cljsbuild "1.0.6"]]
  :cljsbuild {:builds [{:id "dev"
                        :source-paths ["src"]
                        :compiler {:main logbook.core
                                   :output-to "main-dev.js"
                                   :output-dir "out"
                                   :optimizations :none
                                   :source-map true}}
                       {:id "release"
                        :source-paths ["src"]
                        :compiler {:main logbook.core
                                   :output-to "main.js"
                                   :optimizations :advanced
                                   :pretty-print false
                                   :source-map "main.js.map"}}]}
  )
