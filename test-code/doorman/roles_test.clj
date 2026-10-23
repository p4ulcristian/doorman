(ns doorman.roles-test
  "Roles have ids, and a member holds a set of roles. RC Hotcoats' owner,
   Paul, renames \"Painter\" to \"Coater\", gives Anna one extra right as a
   small role (\"QC approver\") beside \"Employee\", invites Tom with no role
   yet, and removes a role once nobody holds it. The check, \"what may I
   do?\", explain-refusal and the leak test read the union of a member's
   roles. Runs against Postgres when DOORMAN_TEST_PORT is set."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [doorman.test-answers :refer [refusal without-request-id]]
   [cellar.api :as cellar]
   [doorman.api :as doorman]
   [doorman.test-log :refer [log]]
   [doorman.test-settings :as test-settings]
   [doorman.test-wire :refer [wire]]
   [bridge.api :as bridge]
   [wire.api :as wire-api]))

(def ^:private port test-settings/port)

(def ^:private scope-kinds
  (doorman/check-scope-kinds
   {:shop {:table :shops :parent nil :single? false :denies []
           :actions {:orders/see #{:read} :orders/edit #{:read :insert :update} :qc/approve #{:read :update}}
           :roles   {"Painter" #{:orders/see :orders/edit} "Employee" #{:orders/see}}}}))

(def ^:private shops
  {:table :shops :columns [[:shop/id :uuid {:primary-key? true}]]
   :indexes [] :references [] :triggers [] :on-change nil :access :public})

(def ^:private orders
  {:table :orders
   :columns [[:order/id :uuid {:primary-key? true}] [:order/shop-id :uuid {:references :shop/id}] [:order/approved :boolean]]
   :indexes [[:order/shop-id]] :references [] :triggers [] :on-change nil
   :access [{:allow #{:orders/see :orders/edit :qc/approve} :only :all-of-the-action :to [:member :order/shop-id]}]})

(def ^:private tables [shops orders])

(defn- id [n] (parse-uuid (format "00000000-0000-4000-8000-%012d" n)))

(def ^:private hotcoats (id 1001))
(def ^:private other-shop (id 2002))
(def ^:private paul (id 1))   ; Owner of RC Hotcoats
(def ^:private anna (id 2))   ; Employee and QC approver
(def ^:private dora (id 3))   ; Painter, then Coater
(def ^:private tom  (id 4))   ; invited, no role yet
(def ^:private olga (id 5))   ; Owner of the other shop
(def ^:private order-1 (id 11))
(def ^:private order-2 (id 22))   ; the other shop's

(def ^:private settings {:cookie-name "app_session" :session-days 1 :visitor-session-days 1 :renew-after-minutes 60
                         :allowed-origin "http://app.test" :secure-cookie? false
                         :token-lead "app_" :token-shown-characters 10 :token-longest-days 30})

(defn- query [text] (cellar/make-query-from-sql-text "a test statement" text))

(defn- run-sql! [pool text] (cellar/run-statement! pool (query text) {}))

(def ^:private handlers
  {:qc/approve {:action :qc/approve :record [:orders :order/id]
                :params-shape [:map [:order/id :uuid]] :answer-shape [:map [:count :int]]
                :refusals {} :rate-limit nil :run-steps-in nil
                :steps [{:name :approve
                         :enter #(bridge/add-answer-to-context
                                  % {:count (cellar/run-statement! (:doorman/transaction %)
                                                                   (query "UPDATE orders SET order__approved = true WHERE order__id = :order/id")
                                                                   (:bridge/message-data %))})}]}
   :qc/in-shop {:action :qc/approve :scope [:shop :shop/id]
                :params-shape [:map [:shop/id :uuid]] :answer-shape [:map]
                :refusals {} :rate-limit nil :run-steps-in nil
                :steps [{:name :answer :enter #(bridge/add-answer-to-context % {})}]}})

(defn- refused-with [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (select-keys (ex-data e) [:type :name :held-by]))))

(defn- role-names [pool scope] (mapv :name (doorman/list-roles pool scope-kinds scope)))

(deftest a-member-holds-a-set-of-roles
  (if-not port
    (println "doorman.roles-test: skipped, DOORMAN_TEST_PORT is not set")
    (let [owner-pool (test-settings/start-pool! (System/getenv "DOORMAN_TEST_USER") 2)]
      (run-sql! owner-pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS doorman CASCADE; DROP SCHEMA IF EXISTS cellar CASCADE")
      (run-sql! owner-pool "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'doorman_app') THEN CREATE ROLE doorman_app LOGIN; END IF; END $$")
      (let [ready-tables (into (doorman/put-gates-on-tables tables scope-kinds) (conj doorman/access-tables doorman/sessions-table doorman/tokens-table))]
        (doorman/install-access-functions! owner-pool tables scope-kinds)
        (with-out-str @(:slow-part (cellar/migrate! owner-pool ready-tables [] test-settings/migrate-options)))
        (cellar/grant-tables-to-role! owner-pool ready-tables "doorman_app"))
      (run-sql! owner-pool (str "INSERT INTO shops VALUES ('" hotcoats "'), ('" other-shop "')"))
      (run-sql! owner-pool (str "INSERT INTO orders VALUES ('" order-1 "', '" hotcoats "', false), ('" order-2 "', '" other-shop "', false)"))
      (let [shop     [:shop hotcoats]
            roles    (cellar/run-in-transaction! owner-pool {:read-only? false}
                                                 #(do (doorman/add-scope! % scope-kinds :shop other-shop olga nil)
                                                      (doorman/add-scope! % scope-kinds :shop hotcoats paul nil)))
            painter  (get roles "Painter")
            employee (get roles "Employee")
            app-pool (test-settings/start-pool! "doorman_app" 4)
            ready    (doorman/put-access-into-handlers handlers {:pool app-pool :tables tables :scope-kinds scope-kinds
                                                                 :find-session-step (doorman/make-find-session-step app-pool settings)})
            request  (fn [person]
                       {:request-method :post
                        :headers {"origin" "http://app.test"
                                  "cookie" (first (str/split (:set-cookie (doorman/sign-in! app-pool {:headers {}} person
                                                                                            {:settings settings :tables [] :session-flags #{}}))
                                                             #";"))}})
            ask      (fn [person handler-name params]
                       (without-request-id (get (bridge/answer-message ready :no-rate-limits (request person) {handler-name params} {:wire wire :log log :meter (test-settings/make-meter) :metric-buckets test-settings/metric-buckets})
                                                handler-name)))
            my-actions (let [handlers {:doorman/my-actions (doorman/make-my-actions-handler {:pool app-pool :tables tables :scope-kinds scope-kinds
                                                                                             :find-session-step (doorman/make-find-session-step app-pool settings)
                                                                                             :rate-limit nil})}]
                         (fn [person]
                           (:shaped-data (wire-api/turn-json-data-into-shaped-data-or-errors
                                          wire doorman/my-actions-answer-shape
                                          (get (bridge/answer-message handlers :no-rate-limits (request person) {:doorman/my-actions {}} {:wire wire :log log :meter (test-settings/make-meter) :metric-buckets test-settings/metric-buckets})
                                               :doorman/my-actions)))))
            explain  (fn [person handler-name params]
                       (doorman/explain-refusal {:pool app-pool :handlers handlers :tables tables :scope-kinds scope-kinds}
                                                {:person-id person :visitor-id nil :session-flags #{}} handler-name params))]
        (try
          (testing "add-scope! answers the new roles' ids by name"
            (is (= #{"Owner" "Painter" "Employee"} (set (keys roles))))
            (is (every? uuid? (vals roles))))

          (let [qc-approver (doorman/add-role! owner-pool scope-kinds shop "QC approver" #{:qc/approve})]
            (testing "an extra right is a small role; a member holds a set of roles; Tom holds none yet"
              (cellar/run-in-transaction! owner-pool {:read-only? false}
                                          (fn [transaction]
                                            (doorman/add-membership! transaction shop anna #{employee qc-approver})
                                            (doorman/add-membership! transaction shop dora #{painter})
                                            (doorman/add-membership! transaction shop tom #{})))
              (is (= {shop #{:orders/see :qc/approve}} (doorman/list-actions-of-person owner-pool scope-kinds anna)) "the union of her roles'")
              (is (= {shop #{}} (doorman/list-actions-of-person owner-pool scope-kinds tom)) "a member, holding nothing")
              (is (= ["Employee" "Owner" "Painter" "QC approver"] (role-names owner-pool shop)))
              (is (= #{:orders/see :orders/edit :qc/approve}
                     (:actions (first (filter #(= "Owner" (:name %)) (doorman/list-roles owner-pool scope-kinds shop)))))
                  "Owner: every action of the kind"))

            (testing "the check reads the union"
              (is (= {:count 1} (ask anna :qc/approve {:order/id order-1})) "QC approver gives her :qc/approve")
              (is (= (refusal :doorman/not-allowed) (ask dora :qc/approve {:order/id order-1})) "Dora sees the order, but no role of hers approves")
              (is (= (refusal :doorman/not-found) (ask tom :qc/approve {:order/id order-1})) "Tom, no role yet: nothing")
              (is (= {} (ask anna :qc/in-shop {:shop/id hotcoats})))
              (is (= (refusal :doorman/not-found) (ask tom :qc/in-shop {:shop/id hotcoats}))))

            (testing "\"what may I do?\": the union, and nothing for a member with no role"
              (is (= [{:scope-kind :shop :scope-id hotcoats :actions [:orders/see :qc/approve]}] (my-actions anna)))
              (is (= [] (my-actions tom))))

            (testing "explain-refusal names the roles that gave the action, and those held"
              (let [answer (explain anna :qc/approve {:order/id order-1})]
                (is (= :allowed (:answer answer)))
                (is (= [{:scope-kind :shop :scope-id hotcoats :role-id qc-approver :name "QC approver" :gives :qc/approve}]
                       (:given-by (:to (first (:sentences answer))))))
                (is (= [{:scope-kind :shop :scope-id hotcoats
                         :roles [{:role-id employee :name "Employee" :actions [:orders/see]}
                                 {:role-id qc-approver :name "QC approver" :actions [:qc/approve]}]}]
                       (:memberships answer))))
              (let [answer (explain dora :qc/approve {:order/id order-1})]
                (is (= :doorman/not-allowed (:answer answer)))
                (is (= [] (:given-by (:to (first (:sentences answer))))) "no role of hers gives it")
                (is (= [{:scope-kind :shop :scope-id hotcoats :roles [{:role-id painter :name "Painter" :actions [:orders/edit :orders/see]}]}]
                       (:memberships answer))))
              (let [answer (explain tom :qc/in-shop {:shop/id hotcoats})]
                (is (= :doorman/not-found (:answer answer)))
                (is (= {:says [:shop :shop/id] :scope-kind :shop :scope-id hotcoats :held-there? false :given-by []} (:scope answer)))
                (is (= [{:scope-kind :shop :scope-id hotcoats :roles []}] (:memberships answer)) "a member, holding no role")))

            (testing "the leak test: Anna's roles and Tom's none reach nothing of the other shop"
              (let [options {:wire wire :log log :meter (test-settings/make-meter) :metric-buckets test-settings/metric-buckets :scope-kinds scope-kinds :seed 7 :size 10}]
                (is (= [] (doorman/find-leaks ready (request anna) {:orders [order-2] :shops [other-shop]} options)))
                (is (= [] (doorman/find-leaks ready (request tom) {:orders [order-1 order-2] :shops [hotcoats other-shop]} options)))))

            (testing "a rename is one row: Dora keeps her role"
              (doorman/rename-role! owner-pool shop painter "Coater")
              (is (= ["Coater" "Employee" "Owner" "QC approver"] (role-names owner-pool shop)))
              (is (= {shop #{:orders/see :orders/edit}} (doorman/list-actions-of-person owner-pool scope-kinds dora))))

            (testing "a name is unique in its scope; Owner is doorman's"
              (is (= {:type :doorman/role-name-taken :name "Employee"} (refused-with #(doorman/rename-role! owner-pool shop painter "Employee"))))
              (is (= {:type :doorman/role-name-taken :name "Employee"} (refused-with #(doorman/add-role! owner-pool scope-kinds shop "Employee" #{}))))
              (is (= :doorman/owner-role (:type (refused-with #(doorman/rename-role! owner-pool shop painter "Owner")))))
              (is (= :doorman/owner-role (:type (refused-with #(doorman/rename-role! owner-pool shop (get roles "Owner") "Boss")))))
              (is (= :doorman/owner-role (:type (refused-with #(doorman/add-role! owner-pool scope-kinds shop "Owner" #{})))))
              (is (= :doorman/owner-role (:type (refused-with #(doorman/set-role-actions! owner-pool scope-kinds shop (get roles "Owner") #{})))))
              (is (= :doorman/owner-role (:type (refused-with #(doorman/remove-role! owner-pool shop (get roles "Owner"))))))
              (is (uuid? (doorman/add-role! owner-pool scope-kinds [:shop other-shop] "Coater" #{})) "the same name in another shop"))

            (testing "set-role-actions! reaches every member holding the role at once"
              (doorman/set-role-actions! owner-pool scope-kinds shop qc-approver #{:qc/approve :orders/edit})
              (is (= {shop #{:orders/see :orders/edit :qc/approve}} (doorman/list-actions-of-person owner-pool scope-kinds anna)))
              (is (= :doorman/unknown-action (:type (refused-with #(doorman/set-role-actions! owner-pool scope-kinds shop qc-approver #{:orders/fly}))))))

            (testing "a role from another shop is refused, by doorman and by Postgres"
              (let [elsewhere (get (into {} (map (juxt :name :role-id)) (doorman/list-roles owner-pool scope-kinds [:shop other-shop])) "Painter")]
                (is (= :doorman/unknown-role (:type (refused-with #(doorman/set-membership-roles! owner-pool shop tom #{elsewhere})))))
                (is (= :doorman/unknown-role (:type (refused-with #(doorman/rename-role! owner-pool shop elsewhere "Mine")))))
                (is (thrown? Exception (run-sql! owner-pool (str "INSERT INTO doorman.membership_roles VALUES ('" tom "', 'shop', '" hotcoats "', '" elsewhere "')")))
                    "the reference holds the role to the membership's scope")))

            (testing "a role is removed only once nobody holds it"
              (is (= {:type :doorman/role-held :name "Coater" :held-by 1} (refused-with #(doorman/remove-role! owner-pool shop painter))))
              (is (true? (doorman/set-membership-roles! owner-pool shop dora #{employee})) "Dora moves to Employee")
              (is (false? (doorman/set-membership-roles! owner-pool shop olga #{employee})) "Olga isn't a member here: nothing changes")
              (is (= {shop #{:orders/see}} (doorman/list-actions-of-person owner-pool scope-kinds dora)))
              (is (nil? (doorman/remove-role! owner-pool shop painter)))
              (is (= ["Employee" "Owner" "QC approver"] (role-names owner-pool shop))))

            (testing "Tom gets his role in place"
              (is (true? (doorman/set-membership-roles! owner-pool shop tom #{qc-approver})))
              (is (= {:count 1} (ask tom :qc/approve {:order/id order-1}))))

            (testing "leaving a scope takes the roles held there"
              (is (true? (doorman/remove-membership! owner-pool shop anna)))
              (is (= {} (doorman/list-actions-of-person owner-pool scope-kinds anna)))
              (is (= [] (cellar/find-rows owner-pool (query (str "SELECT membership_role__role_id FROM doorman.membership_roles WHERE membership_role__person_id = '" anna "'")) {})))))
          (finally
            (cellar/stop-pool! app-pool)
            (cellar/stop-pool! owner-pool)))))))
