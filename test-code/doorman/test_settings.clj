(ns doorman.test-settings
  "The pool settings and migrate! options the tests run with, each written
   out, as cellar requires them, and a pool started as an app starts its
   own: with a log and a meter of its own."
  (:require
   [cellar.api :as cellar]
   [doorman.api :as doorman]
   [logbook.api :as logbook]
   [meter.api :as meter]))

(def port (some-> (System/getenv "DOORMAN_TEST_PORT") parse-long))

(defn make-pool-settings
  "A test Postgres on localhost, no password, no TLS."
  [user pool-size]
  {:host                           "localhost"
   :port                           port
   :database                       "postgres"
   :user                           user
   :password                       nil
   :tls                            :off
   :tls-root-cert                  nil
   :pool-size                      pool-size
   :connection-wait-ms             30000
   :connection-check-wait-ms       5000
   :connection-max-lifetime-ms     1800000
   :connection-keepalive-every-ms  120000
   :leak-warning-after-ms          :none
   :connect-timeout-seconds        10
   :read-timeout-seconds           :none
   :tcp-keepalive?                 false
   :statement-timeout-ms           5000
   :lock-timeout-ms                :none
   :idle-in-transaction-timeout-ms :none
   :time-zone                      "UTC"
   :metric-buckets                 {:query-seconds              [0.01 0.1 1]
                                    :connection-acquire-seconds [0.01 0.1 1]
                                    :connection-usage-seconds   [0.01 0.1 1]}})

(defn declare-tables
  "What the tests declare to cellar beside the tables: doorman's own
   functions, no extensions or views."
  [tables]
  {:tables tables :extensions [] :functions doorman/function-files :views []})

(def migrate-options
  {:instant-part {:statement-timeout-ms 10000 :lock-timeout-ms 5000}
   :slow-part    {:statement-timeout-ms :none :lock-timeout-ms 5000}
   :log          (fn [_level _event _data])})

(def log
  "The tests' log, as an app makes its own; its lines go nowhere."
  (logbook/log-function (logbook/make-logbook {:lowest-level :error :format :json :errors-kept 10
                                               :write-to     (fn [_line])})))

(defn make-meter
  "A meter, as an app makes its own."
  []
  (meter/make-meter {:jvm-metrics false :most-series-per-metric 10000} {:log log}))

(def metric-buckets
  "bridge's histogram bounds, as answer-message and the leak test take
   them: the settings' :metric-buckets."
  {:answer-seconds [0.01 0.1 1]})

(defn start-pool!
  "A pool on the test Postgres, as user, with the tests' log and a meter of
   its own (cellar takes one meter per pool)."
  [user pool-size]
  (cellar/start-pool! (make-pool-settings user pool-size)
                      {:log log :meter (make-meter)}))
