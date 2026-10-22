(ns doorman.test-log
  "The tests' log, made as an app makes its own (logbook), its lines kept
   as JSON data in lines."
  (:require
   [jsonista.core :as json]
   [logbook.api :as logbook]))

(def lines (atom []))

(def log
  (logbook/log-function (logbook/make-logbook {:lowest-level :debug :format :json :errors-kept 100
                                               :write-to     #(swap! lines conj (json/read-value %))})))

(defn find-lines
  "The lines written with this event, as JSON data."
  [event]
  (filterv #(= event (get % "event")) @lines))
