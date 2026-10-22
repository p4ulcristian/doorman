(ns doorman.test-settings
  "The pool settings and migrate! options the tests run with, each written
   out, as cellar requires them.")

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
   :time-zone                      "UTC"})

(def migrate-options
  {:instant-part {:statement-timeout-ms 10000 :lock-timeout-ms 5000}
   :slow-part    {:statement-timeout-ms :none :lock-timeout-ms 5000}
   :log          (fn [_level _event _data])})
