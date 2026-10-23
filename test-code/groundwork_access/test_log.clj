(ns groundwork-access.test-log
  "The tests' log, made as an app makes its own (groundwork-logs), its lines kept
   as JSON data in lines."
  (:require
   [jsonista.core :as json]
   [groundwork-logs.api :as logs]))

(def lines (atom []))

(def log
  (logs/log-function (logs/make-logger {:lowest-level :debug :format :json :errors-kept 100
                                               :write-to     #(swap! lines conj (json/read-value %))})))

(defn find-lines
  "The lines written with this event, as JSON data."
  [event]
  (filterv #(= event (get % "event")) @lines))
