(ns doorman.sessions-migration-test
  "doorman v0.7.0 changed doorman.sessions on a database that has rows:
   a visitor column and a flags column added, the person made optional
   (and doorman.scopes got an index). The tables are made as v0.6.0's
   maps say (v0.6.x is the last before the change; the maps as they were,
   with cellar's two newer keys written out empty, which change no table),
   people's sessions written into them, then cellar brings them to
   today's maps. The rows must survive and work. The Postgres tests run
   when DOORMAN_TEST_PORT is set; they say they were skipped otherwise."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [cellar.api :as cellar]
   [doorman.api :as doorman]
   [doorman.cookie :as cookie]
   [doorman.test-log :as test-log]
   [doorman.test-settings :as test-settings]
   [bridge.api :as bridge]))

(def ^:private port test-settings/port)

(def ^:private sessions-table-of-v0-6
  {:table      :doorman/sessions
   :columns    [[:session/fingerprint :string       {:primary-key? true}]
                [:session/person-id   :uuid]
                [:session/created-at  :time/instant {:default :now}]
                [:session/ends-at     :time/instant]]
   :indexes    [[:session/person-id] [:session/ends-at]]
   :references []
   :triggers   []
   :gates      nil
   :on-change  nil})

(def ^:private access-tables-of-v0-6
  [{:table      :doorman/scopes
    :columns    [[:scope/kind        :string {:primary-key? true}]
                 [:scope/id          :uuid   {:primary-key? true}]
                 [:scope/parent-kind :string {:optional? true}]
                 [:scope/parent-id   :uuid   {:optional? true}]]
    :indexes    []
    :references []
    :triggers   []
    :gates      nil
    :on-change  nil}
   {:table      :doorman/roles
    :columns    [[:role/scope-kind :string {:primary-key? true}]
                 [:role/scope-id   :uuid   {:primary-key? true}]
                 [:role/name       :string {:primary-key? true}]]
    :indexes    []
    :references []
    :triggers   []
    :gates      nil
    :on-change  nil}
   {:table      :doorman/role-actions
    :columns    [[:role-action/scope-kind :string {:primary-key? true}]
                 [:role-action/scope-id   :uuid   {:primary-key? true}]
                 [:role-action/role       :string {:primary-key? true}]
                 [:role-action/action     :string {:primary-key? true}]]
    :indexes    []
    :references []
    :triggers   []
    :gates      nil
    :on-change  nil}
   {:table      :doorman/memberships
    :columns    [[:membership/person-id  :uuid   {:primary-key? true}]
                 [:membership/scope-kind :string {:primary-key? true}]
                 [:membership/scope-id   :uuid   {:primary-key? true}]
                 [:membership/role       :string]]
    :indexes    [[:membership/scope-kind :membership/scope-id]]
    :references []
    :triggers   []
    :gates      nil
    :on-change  nil}])

(def ^:private settings
  {:cookie-name "app_session" :session-days 30 :visitor-session-days 2 :renew-after-minutes 60
   :allowed-origin "https://app.test" :secure-cookie? true
   :token-lead "app_" :token-shown-characters 10 :token-longest-days 30})

(def ^:private anna #uuid "a0000000-0000-4000-8000-000000000001")
(def ^:private bob #uuid "b0000000-0000-4000-8000-000000000002")

(defn- run-sql! [pool text] (cellar/run-statement! pool (cellar/make-query-from-sql-text "test" text) {}))

(defn- find-rows [pool text] (cellar/find-rows pool (cellar/make-query-from-sql-text "test" text) {}))

(defn- make-request [cookie-value]
  {:request-method :post
   :headers        (cond-> {"origin" "https://app.test"}
                     cookie-value (assoc "cookie" (str "app_session=" cookie-value)))})

(defn- turn-set-cookie-into-value [set-cookie]
  (second (re-find #"^app_session=([^;]*)" set-cookie)))

(defn- find-asker [step cookie-value]
  ((juxt :doorman/person-id :doorman/visitor-id :doorman/session-flags)
   (bridge/run-steps-until-answered [step] {:bridge/request (make-request cookie-value) :bridge/log test-log/log})))

(defn- write-session-of-v0-6!
  "A person's session as v0.6.x's sign-in! wrote it: the cookie's
   fingerprint, the person, when it ends."
  [pool cookie-value person-id]
  (run-sql! pool (str "INSERT INTO doorman.sessions (session__fingerprint, session__person_id, session__ends_at) VALUES ('"
                      (cookie/turn-cookie-value-into-fingerprint cookie-value) "', '" person-id "', now() + interval '20 days')")))

(deftest the-sessions-of-v0-6-survive-the-migration-and-work
  (if-not port
    (println "doorman.sessions-migration-test: skipped, DOORMAN_TEST_PORT is not set")
    (let [pool (test-settings/start-pool! (System/getenv "DOORMAN_TEST_USER") 2)]
      (try
        (run-sql! pool "DROP SCHEMA IF EXISTS doorman CASCADE; DROP SCHEMA IF EXISTS cellar CASCADE")
        @(:slow-part (cellar/migrate! pool (conj access-tables-of-v0-6 sessions-table-of-v0-6) [] test-settings/migrate-options))
        (write-session-of-v0-6! pool "anna-old-cookie" anna)
        (write-session-of-v0-6! pool "anna-other-device" anna)
        (write-session-of-v0-6! pool "bob-old-cookie" bob)
        (let [today (conj doorman/access-tables doorman/sessions-table)
              plan  (cellar/make-plan-from-tables pool today [])]
          (testing "cellar's plan has no stop and needs no change entry: two columns added, the person made optional, an index"
            (is (= [] (:stops plan)))
            (is (= #{"ALTER TABLE doorman.sessions ADD COLUMN session__visitor_id uuid"
                     "ALTER TABLE doorman.sessions ADD COLUMN session__flags text NOT NULL DEFAULT ''"
                     "ALTER TABLE doorman.sessions ALTER COLUMN session__person_id DROP NOT NULL"}
                   (set (map :sql (:instant plan)))))
            (is (= 1 (count (:slow plan))))
            (is (str/starts-with? (:sql (first (:slow plan))) "CREATE INDEX CONCURRENTLY IF NOT EXISTS doorman_scopes_scope__parent_kind_scope__parent_id_idx ON doorman.scopes")))
          @(:slow-part (cellar/migrate! pool (conj today doorman/tokens-table) [] test-settings/migrate-options))
          (is (cellar/does-database-match-tables? pool (conj today doorman/tokens-table) []) "nothing left to do; the tokens' table made by the same plan")
          (testing "every row survives, with no flags and no visitor"
            (is (= [[anna "" nil] [anna "" nil] [bob "" nil]]
                   (sort-by (comp str first)
                            (map (juxt :session/person-id :session/flags :session/visitor-id)
                                 (find-rows pool "SELECT session__person_id, session__flags, session__visitor_id FROM doorman.sessions"))))))
          (let [step (doorman/make-find-session-step pool settings)]
            (testing "an old cookie still finds its person"
              (is (= [anna nil #{}] (find-asker step "anna-old-cookie")))
              (is (= [bob nil #{}] (find-asker step "bob-old-cookie"))))
            (testing "a visitor's session works after it, and becomes the person's at sign-in"
              (let [{:keys [visitor-id set-cookie]} (doorman/start-visitor-session! pool (make-request nil) settings)
                    visitor-cookie (turn-set-cookie-into-value set-cookie)]
                (is (uuid? visitor-id))
                (is (= [nil visitor-id #{}] (find-asker step visitor-cookie)))
                (let [signed-in (doorman/sign-in! pool (make-request visitor-cookie) anna
                                                  {:settings settings :tables [] :session-flags #{:impersonated}})]
                  (is (= [nil nil #{}] (find-asker step visitor-cookie)) "the visitor's session ended")
                  (is (= [anna nil #{:impersonated}] (find-asker step (turn-set-cookie-into-value (:set-cookie signed-in))))))))
            (testing "signing out everywhere ends the old sessions with the new"
              (is (= {:sessions 3 :tokens 0} (doorman/sign-out-everywhere! pool anna)))
              (is (= [nil nil #{}] (find-asker step "anna-other-device"))))))
        (finally (cellar/stop-pool! pool))))))
