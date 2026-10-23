(ns doorman.roles-migration-test
  "doorman v0.7's roles were their names and a member held one; today a
   role has an id and a member holds a set. The tables are made as v0.7's
   maps say, rows written into them as v0.7's add-scope!, add-role! and
   add-membership! wrote them, then access-move-from-v0-7's two cellar
   runs bring them to today's maps. Every person keeps the actions they
   had. The Postgres tests run when DOORMAN_TEST_PORT is set; they say they
   were skipped otherwise."
  (:require
   [clojure.test :refer [deftest is testing]]
   [cellar.api :as cellar]
   [doorman.api :as doorman]
   [doorman.test-settings :as test-settings]))

(def ^:private port test-settings/port)

(def ^:private access-tables-of-v0-7
  "doorman v0.7.9's access-tables, as they were."
  [{:table :doorman/scopes
    :columns [[:scope/kind :string {:primary-key? true}] [:scope/id :uuid {:primary-key? true}]
              [:scope/parent-kind :string {:optional? true}] [:scope/parent-id :uuid {:optional? true}]]
    :indexes [{:columns [[:scope/parent-kind :ascending :nulls-last] [:scope/parent-id :ascending :nulls-last]] :method :btree :ops {} :unique? false :where nil}] :uniques [] :checks [] :references [] :triggers [] :gates nil :on-change nil}
   {:table :doorman/roles
    :columns [[:role/scope-kind :string {:primary-key? true}] [:role/scope-id :uuid {:primary-key? true}]
              [:role/name :string {:primary-key? true}]]
    :indexes [] :uniques [] :checks [] :references [] :triggers [] :gates nil :on-change nil}
   {:table :doorman/role-actions
    :columns [[:role-action/scope-kind :string {:primary-key? true}] [:role-action/scope-id :uuid {:primary-key? true}]
              [:role-action/role :string {:primary-key? true}] [:role-action/action :string {:primary-key? true}]]
    :indexes [] :uniques [] :checks [] :references [] :triggers [] :gates nil :on-change nil}
   {:table :doorman/memberships
    :columns [[:membership/person-id :uuid {:primary-key? true}] [:membership/scope-kind :string {:primary-key? true}]
              [:membership/scope-id :uuid {:primary-key? true}] [:membership/role :string]]
    :indexes [{:columns [[:membership/scope-kind :ascending :nulls-last] [:membership/scope-id :ascending :nulls-last]] :method :btree :ops {} :unique? false :where nil}] :uniques [] :checks [] :references [] :triggers [] :gates nil :on-change nil}])

(def ^:private scope-kinds
  (doorman/check-scope-kinds
   {:company {:table nil :parent nil :single? false :denies []
              :actions {:company/report #{:read}}
              :roles {"Director" #{:company/report :orders/see}}}
    :shop    {:table nil :parent :company :single? false :denies []
              :actions {:orders/see #{:read} :orders/edit #{:read :insert :update}}
              :roles {"Painter" #{:orders/see :orders/edit} "Viewer" #{:orders/see}}}}))

(defn- id [n] (parse-uuid (format "00000000-0000-4000-8000-%012d" n)))

(def ^:private company (id 100))
(def ^:private shop-a (id 101))
(def ^:private shop-b (id 102))
(def ^:private paul (id 1))   ; Owner of the company and both shops
(def ^:private anna (id 2))   ; Painter in A
(def ^:private dora (id 3))   ; Director of the company
(def ^:private bob  (id 4))   ; Viewer in B, and a role of B's own, "Driver"
(def ^:private eve  (id 5))   ; in A with a role that has no actions

(defn- run-sql! [pool text] (cellar/run-statement! pool (cellar/make-query-from-sql-text "test" text) {}))

(defn- write-scope-of-v0-7!
  "A scope as v0.7's add-scope! wrote it: the scope, Owner and the kind's
   roles by name, the founder a member holding Owner."
  [pool kind scope-id parent founder]
  (run-sql! pool (str "INSERT INTO doorman.scopes VALUES ('" (name kind) "', '" scope-id "', "
                      (if parent (str "'company', '" parent "'") "NULL, NULL") ")"))
  (run-sql! pool (str "INSERT INTO doorman.roles VALUES ('" (name kind) "', '" scope-id "', 'Owner')"))
  (doseq [[role-name actions] (get-in scope-kinds [kind :roles])]
    (run-sql! pool (str "INSERT INTO doorman.roles VALUES ('" (name kind) "', '" scope-id "', '" role-name "')"))
    (doseq [action actions]
      (run-sql! pool (str "INSERT INTO doorman.role_actions VALUES ('" (name kind) "', '" scope-id "', '" role-name "', '" (subs (str action) 1) "')"))))
  (run-sql! pool (str "INSERT INTO doorman.memberships VALUES ('" founder "', '" (name kind) "', '" scope-id "', 'Owner')")))

(defn- write-membership-of-v0-7! [pool person kind scope-id role-name]
  (run-sql! pool (str "INSERT INTO doorman.memberships VALUES ('" person "', '" (name kind) "', '" scope-id "', '" role-name "')")))

(deftest the-access-rows-of-v0-7-move-to-role-ids
  (if-not port
    (println "doorman.roles-migration-test: skipped, DOORMAN_TEST_PORT is not set")
    (let [pool   (test-settings/start-pool! (System/getenv "DOORMAN_TEST_USER") 2)
          move   doorman/access-move-from-v0-7
          people [paul anna dora bob eve]]
      (try
        (run-sql! pool "DROP SCHEMA IF EXISTS doorman CASCADE; DROP SCHEMA IF EXISTS cellar CASCADE")
        (with-out-str @(:slow-part (cellar/migrate! pool (test-settings/declare-tables access-tables-of-v0-7) [] test-settings/migrate-options)))
        (write-scope-of-v0-7! pool :company company nil paul)
        (write-scope-of-v0-7! pool :shop shop-a company paul)
        (write-scope-of-v0-7! pool :shop shop-b company paul)
        (write-membership-of-v0-7! pool anna :shop shop-a "Painter")
        (write-membership-of-v0-7! pool dora :company company "Director")
        (write-membership-of-v0-7! pool bob :shop shop-b "Viewer")
        (run-sql! pool (str "INSERT INTO doorman.roles VALUES ('shop', '" shop-b "', 'Driver'), ('shop', '" shop-a "', 'Idle')"))
        (run-sql! pool (str "INSERT INTO doorman.role_actions VALUES ('shop', '" shop-b "', 'Driver', 'orders/see')"))
        (write-membership-of-v0-7! pool eve :shop shop-a "Idle")
        (let [before (into {} (for [person people]
                                [person (-> (cellar/run-in-transaction! pool {:read-only? true :log nil}
                                                                        #(cellar/find-rows % (cellar/make-query-from-sql-text "test"
                                                                                                                              (str "SELECT membership__scope_kind, membership__scope_id, membership__role FROM doorman.memberships WHERE membership__person_id = '" person "'"))
                                                                                           {}))
                                            count)]))]
          (testing "today's maps alone stop: a role's primary key changed"
            (is (seq (:stops (cellar/make-plan-from-tables pool (test-settings/declare-tables doorman/access-tables) [])))))
          (testing "1. v0.7's tables renamed and kept, today's made beside them"
            (let [tables  (into doorman/access-tables (:old-tables move))
                  changes (:renames move)]
              (is (= [] (:stops (cellar/make-plan-from-tables pool (test-settings/declare-tables tables) changes))))
              (with-out-str @(:slow-part (cellar/migrate! pool (test-settings/declare-tables tables) changes test-settings/migrate-options)))
              (is (cellar/does-database-match-tables? pool (test-settings/declare-tables tables) changes))
              (is (= {} (doorman/list-actions-of-person pool scope-kinds anna)) "between the two steps, nobody holds anything")))
          (testing "2. the rows copied, the old tables dropped, in one transaction"
            (let [changes (into (:renames move) (:copy-and-drops move))]
              (is (= [] (:stops (cellar/make-plan-from-tables pool (test-settings/declare-tables doorman/access-tables) changes))))
              (with-out-str @(:slow-part (cellar/migrate! pool (test-settings/declare-tables doorman/access-tables) changes test-settings/migrate-options)))
              (is (cellar/does-database-match-tables? pool (test-settings/declare-tables doorman/access-tables) changes) "nothing left to do")
              (is (= [] (cellar/find-rows pool (cellar/make-query-from-sql-text "test" "SELECT table_name AS table__name FROM information_schema.tables WHERE table_name LIKE '%of_v0_7'") {}))
                  "the old tables are gone")))
          (testing "every person keeps their actions, by role ids now"
            (is (= {[:company company] #{:company/report :orders/see :orders/edit}
                    [:shop shop-a] #{:orders/see :orders/edit}
                    [:shop shop-b] #{:orders/see :orders/edit}}
                   (doorman/list-actions-of-person pool scope-kinds paul))
                "Owner of all three")
            (is (= {[:shop shop-a] #{:orders/see :orders/edit}} (doorman/list-actions-of-person pool scope-kinds anna)))
            (is (= {[:company company] #{:company/report :orders/see} [:shop shop-a] #{:orders/see} [:shop shop-b] #{:orders/see}}
                   (doorman/list-actions-of-person pool scope-kinds dora))
                "the Director's, counted in the shops below")
            (is (= {[:shop shop-b] #{:orders/see}} (doorman/list-actions-of-person pool scope-kinds bob)))
            (is (= {[:shop shop-a] #{}} (doorman/list-actions-of-person pool scope-kinds eve)) "a role with no actions: a member, holding nothing"))
          (testing "each membership's one role is a set of one id"
            (is (= before
                   (into {} (for [person people]
                              [person (count (cellar/find-rows pool (cellar/make-query-from-sql-text "test"
                                                                                                     (str "SELECT membership_role__role_id FROM doorman.membership_roles WHERE membership_role__person_id = '" person "'"))
                                                               {}))])))))
          (testing "every role has an id, its name and actions kept, the scope's own roles too"
            (is (= [["Driver" #{:orders/see}] ["Owner" #{:orders/see :orders/edit}] ["Painter" #{:orders/see :orders/edit}] ["Viewer" #{:orders/see}]]
                   (mapv (juxt :name :actions) (doorman/list-roles pool scope-kinds [:shop shop-b]))))
            (is (every? (comp uuid? :role-id) (doorman/list-roles pool scope-kinds [:shop shop-a]))))
          (testing "the moved rows work with today's functions"
            (let [viewer (some #(when (= "Viewer" (:name %)) (:role-id %)) (doorman/list-roles pool scope-kinds [:shop shop-b]))
                  painter (some #(when (= "Painter" (:name %)) (:role-id %)) (doorman/list-roles pool scope-kinds [:shop shop-b]))]
              (doorman/rename-role! pool [:shop shop-b] viewer "Looker")
              (is (true? (doorman/set-membership-roles! pool [:shop shop-b] bob #{viewer painter})))
              (is (= {[:shop shop-b] #{:orders/see :orders/edit}} (doorman/list-actions-of-person pool scope-kinds bob))))))
        (finally (cellar/stop-pool! pool))))))
