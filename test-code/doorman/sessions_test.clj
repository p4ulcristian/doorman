(ns doorman.sessions-test
  "The Postgres tests run when DOORMAN_TEST_PORT is set; they say
   they were skipped otherwise."
  (:require
   [clojure.test :refer [deftest is testing]]
   [doorman.api :as doorman]
   [doorman.cookie :as cookie]
   [cellar.api :as cellar]
   [mailroom.api :as mailroom]))

(def ^:private port (some-> (System/getenv "DOORMAN_TEST_PORT") parse-long))

(def ^:private settings {:cookie-name "app_session" :session-days 30 :renew-after-minutes 60 :allowed-origin "https://app.test" :secure-cookie? true})

(def ^:private anna #uuid "a0000000-0000-4000-8000-000000000001")

(defn- make-request [set-cookie origin]
  {:request-method :post
   :headers        (cond-> {"origin" origin}
                     set-cookie (assoc "cookie" (first (clojure.string/split set-cookie #";"))))})

(defn- run-find-session [step request]
  (mailroom/run-steps-until-answered [step] {:mailroom/request request}))

(deftest the-cookie
  (testing "only the fingerprint is kept, and it is the same for the same cookie"
    (is (= 64 (count (cookie/turn-cookie-value-into-fingerprint "abc"))))
    (is (= (cookie/turn-cookie-value-into-fingerprint "abc") (cookie/turn-cookie-value-into-fingerprint "abc"))))
  (testing "the cookie is HttpOnly, SameSite=Lax and Secure"
    (is (= "app_session=xyz; Path=/; HttpOnly; SameSite=Lax; Max-Age=60; Secure"
           (cookie/make-set-cookie-text "app_session" "xyz" 60 true)))))

(deftest the-settings-are-required
  (let [errors (try (doorman/make-find-session-step nil (dissoc settings :cookie-name :renew-after-minutes))
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :doorman/bad-settings (:type errors)))
    (is (= #{:cookie-name :renew-after-minutes} (set (keys (:errors errors)))) "each missing one is named")))

(deftest the-session-key-reads-the-named-cookie
  (let [request {:headers {"cookie" "other=1; app_session=abc"}}]
    (is (= (cookie/turn-cookie-value-into-fingerprint "abc") (doorman/find-session-key-of-request request settings)))
    (is (nil? (doorman/find-session-key-of-request request (assoc settings :cookie-name "another_app_session")))
        "another app's cookie on the same host is not this one's")))

(defn- run-sql! [pool text] (cellar/run-statement! pool (cellar/make-query-from-sql-text "test" text) {}))

(deftest sessions-against-postgres
  (if-not port
    (println "doorman.sessions-test: skipped, DOORMAN_TEST_PORT is not set")
    (let [pool (cellar/start-pool! {:host "localhost" :port port :database "postgres"
                                 :user (System/getenv "DOORMAN_TEST_USER") :password nil
                                 :tls :off :pool-size 2 :statement-timeout-ms 5000 :time-zone "UTC"})
          step (doorman/make-find-session-step pool settings)]
      (try
        (cellar/run-statement! pool (cellar/make-query-from-sql-text "test" "DROP SCHEMA IF EXISTS doorman CASCADE; DROP SCHEMA IF EXISTS cellar CASCADE") {})
        (with-out-str @(:slow-part (cellar/migrate! pool [doorman/sessions-table] [])))
        (let [{:keys [set-cookie]} (doorman/sign-in! pool anna settings)]
          (testing "a signed-in request: the person is in the context"
            (is (= anna (:doorman/person-id (run-find-session step (make-request set-cookie "https://app.test"))))))
          (testing "another site's request is answered with an error, before any work"
            (is (contains? (:mailroom/answer (run-find-session step (make-request set-cookie "https://evil.test"))) :error)))
          (testing "no cookie: nobody"
            (is (nil? (:doorman/person-id (run-find-session step (make-request nil "https://app.test"))))))
          (testing "signed out: the same cookie finds nobody"
            (doorman/sign-out! pool (make-request set-cookie "https://app.test") settings)
            (is (nil? (:doorman/person-id (run-find-session step (make-request set-cookie "https://app.test")))))))
        (testing "a session seen again lasts :session-days from then, once :renew-after-minutes have passed"
          (let [{:keys [set-cookie]} (doorman/sign-in! pool anna settings)
                find-minutes-left #(:session/minutes-left
                                 (cellar/find-one-row pool (cellar/make-query-from-sql-text
                                                            "minutes left"
                                                            "SELECT round(extract(epoch FROM max(session__ends_at) - now()) / 60)::int AS session__minutes_left FROM doorman.sessions")
                                                      {}))]
            (run-sql! pool "UPDATE doorman.sessions SET session__ends_at = now() + interval '30 days' - interval '59 minutes'")
            (run-find-session step (make-request set-cookie "https://app.test"))
            (is (= (- (* 30 24 60) 59) (find-minutes-left)) "seen within the hour: not written")
            (run-sql! pool "UPDATE doorman.sessions SET session__ends_at = now() + interval '20 days'")
            (run-find-session step (make-request set-cookie "https://app.test"))
            (is (= (* 30 24 60) (find-minutes-left)) "seen after it: 30 days from now")
            (doorman/sign-out-everywhere! pool anna)))
        (testing "signing out everywhere ends every session of the person"
          (doorman/sign-in! pool anna settings)
          (doorman/sign-in! pool anna settings)
          (is (= 2 (doorman/sign-out-everywhere! pool anna))))
        (finally (cellar/stop-pool! pool))))))
