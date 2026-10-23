(ns doorman.system-and-parents-test
  "A named system actor ([:system name], run-as-system!) and records under
   records (:parent), end to end. The tables belong to doorman_owner, a
   role that is not a superuser, as in production (forced gates hold it
   too); the app runs as doorman_app, which owns nothing. The test's rows
   are written by the superuser. Runs when DOORMAN_TEST_PORT is set."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [doorman.test-answers :refer [refusal refusal-json read-without-request-ids without-request-id]]
   [cellar.api :as cellar]
   [doorman.api :as doorman]
   [doorman.test-roles :refer [add-member!]]
   [doorman.asking :as asking]
   [doorman.test-log :refer [log]]
   [doorman.test-settings :as test-settings]
   [doorman.test-wire :refer [wire]]
   [bridge.api :as bridge]))

(def ^:private port test-settings/port)

;; -----------------------------------------------------------------------------
;; The app

(def ^:private scope-kinds
  (doorman/check-scope-kinds
   {:shop {:table :shops :parent nil :single? false
           :actions {:orders/see #{:read} :orders/edit #{:read :insert :update}
                     :jobs/see #{:read} :jobs/edit #{:read :insert :update}
                     :shop-emails/see #{:read} :shop-emails/mark-bounced #{:read :update}}
           :roles   {"Staff" #{:orders/see :orders/edit :jobs/see :jobs/edit :shop-emails/see}}
           :denies  [{:deny :writes :when [:= :shop/status "suspended"]}]}}))

(def ^:private shops
  {:table :shops :columns [[:shop/id :uuid {:primary-key? true}] [:shop/status :string]]
   :indexes [] :uniques [] :checks [] :references [] :triggers [] :on-change nil :access :public})

(def ^:private orders
  {:table      :orders
   :columns    [[:order/id :uuid {:primary-key? true}]
                [:order/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]]
   :indexes    [{:columns [[:order/shop-id :ascending :nulls-last]]
                 :method :btree :ops {} :unique? false :where nil}]
   :uniques    []
   :checks     []
   :references []
   :triggers   []
   :on-change  nil
   :access     [{:allow #{:orders/see :orders/edit} :only :all-of-the-action :to [:member :order/shop-id]}]})

(def ^:private jobs
  {:table      :jobs
   :columns    [[:job/id :uuid {:primary-key? true :default :random-uuid}]
                [:job/order-id :uuid {:references {:to :order/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]
                [:job/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]]
   :indexes    [{:columns [[:job/order-id :ascending :nulls-last]]
                 :method :btree :ops {} :unique? false :where nil}
                {:columns [[:job/shop-id :ascending :nulls-last]]
                 :method :btree :ops {} :unique? false :where nil}]
   :uniques    []
   :checks     []
   :references []
   :triggers   []
   :on-change  nil
   :parent     {:column :job/order-id :carries {:job/shop-id :order/shop-id}}
   :access     [{:allow #{:jobs/see :jobs/edit} :only :all-of-the-action :to [:member :job/shop-id]}]})

(def ^:private photos
  {:table      :photos
   :columns    [[:photo/id :uuid {:primary-key? true :default :random-uuid}]
                [:photo/job-id :uuid {:references {:to :job/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]
                [:photo/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]]
   :indexes    [{:columns [[:photo/job-id :ascending :nulls-last]]
                 :method :btree :ops {} :unique? false :where nil}]
   :uniques    []
   :checks     []
   :references []
   :triggers   []
   :on-change  nil
   :parent     {:column :photo/job-id :carries {:photo/shop-id :job/shop-id}}
   :access     [{:allow #{:jobs/see :jobs/edit} :only :all-of-the-action :to [:member :photo/shop-id]}]})

(def ^:private shop-emails
  {:table      :shop-emails
   :columns    [[:shop-email/id :uuid {:primary-key? true}]
                [:shop-email/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]
                [:shop-email/address :string]
                [:shop-email/bounced :boolean {:default false}]]
   :indexes    [{:columns [[:shop-email/shop-id :ascending :nulls-last]]
                 :method :btree :ops {} :unique? false :where nil}]
   :uniques    []
   :checks     []
   :references []
   :triggers   []
   :on-change  nil
   :access     [{:allow :shop-emails/see :only :all-of-the-action :to [:member :shop-email/shop-id]}
                {:allow :shop-emails/mark-bounced :only :all-of-the-action :to [:system :postbox]}]})

(def ^:private tables [shops orders jobs photos shop-emails])

(defn- id [n] (parse-uuid (format "00000000-0000-4000-8000-%012d" n)))

(def ^:private shop-7 (id 7007))
(def ^:private shop-8 (id 8008)) ; suspended
(def ^:private shop-9 (id 9009))
(def ^:private anna (id 2)) ; Staff in shop 7
(def ^:private mia (id 3))  ; Staff in shops 7 and 9
(def ^:private order-42 (id 42)) ; shop 7
(def ^:private order-99 (id 99)) ; shop 9
(def ^:private email-7 (id 707))
(def ^:private email-8 (id 808))

;; -----------------------------------------------------------------------------
;; Helpers

(defn- run-sql! [pool text] (cellar/run-statement! pool (cellar/make-query-from-sql-text "test" text) {}))

(defn- find-rows [pool text] (cellar/find-rows pool (cellar/make-query-from-sql-text "test" text) {}))

(defn- make-database!
  "The tables made by doorman_owner, the rows written by the superuser."
  [super-pool]
  (run-sql! super-pool (str "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; "
                            "DROP SCHEMA IF EXISTS doorman CASCADE; DROP SCHEMA IF EXISTS cellar CASCADE"))
  (run-sql! super-pool (str "DO $$ BEGIN "
                            "IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'doorman_app') THEN CREATE ROLE doorman_app LOGIN; END IF; "
                            "IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'doorman_owner') THEN CREATE ROLE doorman_owner LOGIN; END IF; "
                            "END $$"))
  (run-sql! super-pool "GRANT CREATE ON DATABASE postgres TO doorman_owner; ALTER SCHEMA public OWNER TO doorman_owner")
  (let [owner-pool (test-settings/start-pool! "doorman_owner" 2)
        ready      (into (doorman/put-gates-on-tables tables scope-kinds) (conj doorman/access-tables doorman/sessions-table doorman/tokens-table))]
    (try
      (with-out-str @(:slow-part (cellar/migrate! owner-pool (test-settings/declare-tables-and-access ready tables scope-kinds) [] test-settings/migrate-options)))
      (cellar/grant-tables-to-role! owner-pool ready "doorman_app")
      (is (cellar/does-database-match-tables? owner-pool (test-settings/declare-tables-and-access ready tables scope-kinds) []) "the link and the fill are in the maps: nothing left to do")
      (finally (cellar/stop-pool! owner-pool))))
  (run-sql! super-pool (str "INSERT INTO shops VALUES ('" shop-7 "', 'open'), ('" shop-8 "', 'suspended'), ('" shop-9 "', 'open')"))
  (cellar/run-in-transaction! super-pool {:read-only? false :log nil}
                              (fn [transaction]
                                (doseq [shop [shop-7 shop-8 shop-9]]
                                  (doorman/add-scope! transaction scope-kinds :shop shop (id 1) nil))
                                (add-member! transaction scope-kinds [:shop shop-7] anna #{"Staff"})
                                (add-member! transaction scope-kinds [:shop shop-7] mia #{"Staff"})
                                (add-member! transaction scope-kinds [:shop shop-9] mia #{"Staff"})))
  (run-sql! super-pool (str "INSERT INTO orders VALUES ('" order-42 "', '" shop-7 "'), ('" order-99 "', '" shop-9 "')"))
  (run-sql! super-pool (str "INSERT INTO shop_emails (shop_email__id, shop_email__shop_id, shop_email__address) VALUES "
                            "('" email-7 "', '" shop-7 "', 'seven@shop.test'), ('" email-8 "', '" shop-8 "', 'eight@shop.test')")))

(defn- as-person
  "(f transaction) as the person, through the gates, on the app's role."
  [app-pool person-id f]
  (cellar/run-in-transaction! app-pool {:read-only? false :log nil}
                              (fn [transaction]
                                (asking/set-asking! transaction {:person-id person-id :visitor-id nil :session-flags #{}}
                                                    {:acting :every-action :holding (doorman/list-actions-of-person transaction scope-kinds person-id)})
                                (f transaction))))

(defn- with-database [f]
  (let [super-pool (test-settings/start-pool! (System/getenv "DOORMAN_TEST_USER") 2)]
    (try
      (make-database! super-pool)
      (let [app-pool (test-settings/start-pool! "doorman_app" 2)]
        (try (f super-pool app-pool)
             (finally (cellar/stop-pool! app-pool))))
      (finally (cellar/stop-pool! super-pool)))))

(defn- problems [tables]
  (try (doorman/make-sql-conditions tables scope-kinds) []
       (catch clojure.lang.ExceptionInfo e (:problems (ex-data e)))))

;; -----------------------------------------------------------------------------
;; The words, checked

(deftest the-words-are-checked
  (testing "[:system name] names an actor by a keyword without a namespace"
    (is (some #(re-find #"keyword without a namespace" %)
              (problems [shops orders (assoc-in shop-emails [:access 1 :to] [:system "postbox"])]))))
  (testing "a :via's :to can't be a system actor"
    (is (some #(re-find #"a system actor is named by the sentence's own :to" %)
              (problems [shops orders (assoc-in shop-emails [:access 1] {:allow :shop-emails/mark-bounced :only :all-of-the-action
                                                                         :via {:table :orders :match {:shop-email/shop-id :order/shop-id}
                                                                               :to [:system :postbox]}})]))))
  (testing ":parent's columns, the parent, their types, NOT NULL and the index"
    (is (= [] (problems tables)))
    (is (some #(re-find #"must reference another table's one-column primary key" %)
              (problems [shops orders (assoc-in jobs [:parent :column] :job/id)])))
    (is (some #(re-find #":job/shop-id is optional" %)
              (problems [shops orders (assoc-in jobs [:columns 2] [:job/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement} :optional? true}])])))
    (is (some #(re-find #"needs an index on \[:job/order-id\]" %)
              (problems [shops orders (assoc jobs :indexes [{:columns [[:job/shop-id :ascending :nulls-last]] :method :btree :ops {} :unique? false :where nil}])])))
    (is (some #(re-find #"carries :job/shop-id from :order/nothing" %)
              (problems [shops orders (assoc-in jobs [:parent :carries] {:job/shop-id :order/nothing})])))
    (is (some #(re-find #":parent is \{:column" %)
              (problems [shops orders (assoc jobs :parent {:column :job/order-id})]))))
  (testing "doorman puts the link and the fill on the child, beside its own"
    (let [ready (into {} (map (juxt :table identity)) (doorman/put-gates-on-tables tables scope-kinds))]
      (is (= [{:columns [:job/order-id :job/shop-id] :to [:order/id :order/shop-id]
               :on-delete :refuse :on-update :cascade :checked-at :statement}]
             (get-in ready [:jobs :references])))
      (is (= [{:columns [:photo/job-id :photo/shop-id] :to [:job/id :job/shop-id]
               :on-delete :refuse :on-update :cascade :checked-at :statement}]
             (get-in ready [:photos :references])))
      (is (= [:before #{:insert :update} [:job/order-id] :row nil]
             ((juxt :timing :events :update-of :for-each :when) (first (get-in ready [:jobs :triggers]))))
          "called for every row: the fill itself skips a row with no parent")
      (is (= [] (get-in ready [:orders :references]) (get-in ready [:orders :triggers]))))))

(deftest a-system-actor-is-refused-a-transaction-and-a-bad-name
  (is (= :doorman/bad-system-name
         (:type (ex-data (try (doorman/run-as-system! nil :billing/postbox {:read-only? false :log nil} identity) (catch Exception e e))))))
  (is (= :doorman/bad-options
         (:type (ex-data (try (doorman/run-as-system! nil :postbox {} identity) (catch Exception e e))))))
  (is (= :doorman/system-in-a-transaction
         (:type (ex-data (try (doorman/run-as-system! {:cellar/connection :a-transaction} :postbox {:read-only? false :log nil} identity)
                              (catch Exception e e)))))))

;; -----------------------------------------------------------------------------
;; Against Postgres

(def ^:private mark-bounced
  (cellar/make-query-from-sql-text "a bounce" "UPDATE shop_emails SET shop_email__bounced = true WHERE shop_email__address = :address"))

(deftest a-named-system-actor-gets-exactly-what-its-sentences-say
  (if-not port
    (println "doorman.system-and-parents-test: skipped, DOORMAN_TEST_PORT is not set")
    (with-database
      (fn [super-pool app-pool]
        (testing "postbox marks a shop's email row, in a suspended shop too: the denies hold people"
          (is (= 1 (doorman/run-as-system! app-pool :postbox {:read-only? false :log nil}
                                           #(cellar/run-statement! % mark-bounced {:address "seven@shop.test"}))))
          (is (= 1 (doorman/run-as-system! app-pool :postbox {:read-only? false :log nil}
                                           #(cellar/run-statement! % mark-bounced {:address "eight@shop.test"}))))
          (is (= [true true] (map :shop-email/bounced (find-rows super-pool "SELECT shop_email__bounced FROM shop_emails")))))
        (testing "the transaction carries the caller's log: work kept for after its commit that fails is written there"
          (let [lines (atom [])]
            (doorman/run-as-system! app-pool :postbox {:read-only? false :log (fn [level event data] (swap! lines conj [level event (ex-message (:error data))]))}
                                    (fn [transaction] (cellar/run-after-commit! transaction #(throw (ex-info "after the commit" {})))))
            (is (= [[:error :cellar/after-commit-failed "after the commit"]] @lines))))
        (testing "the same function reads no other table's rows"
          (is (= [] (doorman/run-as-system! app-pool :postbox {:read-only? true :log nil}
                                            #(cellar/find-rows % (cellar/make-query-from-sql-text "all" "SELECT order__id FROM orders") {}))))
          (is (= [] (doorman/run-as-system! app-pool :postbox {:read-only? true :log nil}
                                            #(cellar/find-rows % (cellar/make-query-from-sql-text "all" "SELECT job__id FROM jobs") {})))))
        (testing "the system leak test: postbox reaches no order; another actor no email; postbox reads and changes emails, never deletes"
          (is (= [] (doorman/find-system-leaks app-pool tables :postbox {:orders [order-42 order-99]})))
          (is (= [] (doorman/find-system-leaks app-pool tables :billing {:shop-emails [email-7 email-8]})))
          (is (= [{:system :postbox :table :shop-emails :id email-7 :reached #{:read :update}}]
                 (doorman/find-system-leaks app-pool tables :postbox {:shop-emails [email-7]}))
              "what the sentences give it, it reaches: the test sees it"))
        (testing "a person never holds a system's sentence"
          (is (= 0 (as-person app-pool anna #(cellar/run-statement! % mark-bounced {:address "seven@shop.test"}))))
          (let [ready (doorman/put-access-into-handlers
                       {:emails/mark {:action :shop-emails/mark-bounced :scope :every-scope-holding-the-action :params-shape [:map] :answer-shape [:map]
                                      :refusals {} :rate-limit nil :run-steps-in nil
                                      :steps [{:name :answer :enter #(bridge/add-answer-to-context % {})}]}}
                       {:pool app-pool :tables tables :scope-kinds scope-kinds
                        :find-session-step (doorman/make-find-session-step app-pool {:cookie-name "app_session" :session-days 1
                                                                                     :visitor-session-days 1 :renew-after-minutes 60
                                                                                     :allowed-origin "http://app.test" :secure-cookie? false
                                                                                     :token-lead "app_" :token-shown-characters 10 :token-longest-days 30})})
                {:keys [set-cookie]} (doorman/sign-in! app-pool {:headers {}} anna
                                                       {:settings {:cookie-name "app_session" :session-days 1 :visitor-session-days 1
                                                                   :renew-after-minutes 60 :allowed-origin "http://app.test" :secure-cookie? false
                                                                   :token-lead "app_" :token-shown-characters 10 :token-longest-days 30}
                                                        :tables [] :session-flags #{}})
                request {:request-method :post
                         :headers {"origin" "http://app.test" "cookie" (first (str/split set-cookie #";"))}}]
            (is (= (refusal :doorman/not-found)
                   (without-request-id (:emails/mark (bridge/answer-message ready :no-rate-limits request {:emails/mark {}} {:wire wire :log log :meter (test-settings/make-meter) :metric-buckets test-settings/metric-buckets})))))))))))

(deftest a-record-under-a-record-carries-its-parents-columns
  (if-not port
    (println "doorman.system-and-parents-test: skipped, DOORMAN_TEST_PORT is not set")
    (with-database
      (fn [super-pool app-pool]
        (let [job (fn [] (first (find-rows super-pool "SELECT job__id, job__shop_id FROM jobs")))]
          (testing "filled on insert: Anna adds a job under her shop's order without naming the shop"
            (as-person app-pool anna #(run-sql! % (str "INSERT INTO jobs (job__order_id) VALUES ('" order-42 "')")))
            (is (= shop-7 (:job/shop-id (job))))
            (as-person app-pool anna #(run-sql! % (str "INSERT INTO photos (photo__job_id) VALUES ('" (:job/id (job)) "')")))
            (is (= [shop-7] (map :photo/shop-id (find-rows super-pool "SELECT photo__shop_id FROM photos")))
                "a record under a record under a record"))
          (testing "filled from the parent, so the gates refuse a job under another shop's order"
            (is (thrown? Exception (as-person app-pool anna #(run-sql! % (str "INSERT INTO jobs (job__order_id) VALUES ('" order-99 "')")))))
            (is (thrown? Exception (as-person app-pool anna #(run-sql! % (str "INSERT INTO jobs (job__order_id, job__shop_id) VALUES ('"
                                                                              order-99 "', '" shop-7 "')"))))
                "naming its own shop doesn't help: the fill sets the parent's"))
          (testing "a child can't hold another value than its parent's"
            (is (thrown? Exception (run-sql! super-pool (str "UPDATE jobs SET job__shop_id = '" shop-9 "'")))))
          (testing "carried down when the parent changes, by Postgres, past the children's gates"
            (as-person app-pool mia #(run-sql! % (str "UPDATE orders SET order__shop_id = '" shop-9 "' WHERE order__id = '" order-42 "'")))
            (is (= shop-9 (:job/shop-id (job))))
            (is (= [shop-9] (map :photo/shop-id (find-rows super-pool "SELECT photo__shop_id FROM photos")))))
          (testing "the gates follow: Anna no longer sees the job or its photo; the check agrees; no leak"
            (is (= [] (as-person app-pool anna #(cellar/find-rows % (cellar/make-query-from-sql-text "jobs" "SELECT job__id FROM jobs") {}))))
            (is (= [] (as-person app-pool anna #(cellar/find-rows % (cellar/make-query-from-sql-text "photos" "SELECT photo__id FROM photos") {}))))
            (let [settings {:cookie-name "app_session" :session-days 1 :visitor-session-days 1 :renew-after-minutes 60
                            :allowed-origin "http://app.test" :secure-cookie? false
                            :token-lead "app_" :token-shown-characters 10 :token-longest-days 30}
                  ready    (doorman/put-access-into-handlers
                            {:jobs/one {:action :jobs/see :record [:jobs :job/id] :params-shape [:map [:job/id :uuid]]
                                        :answer-shape [:map [:job/id :uuid]] :refusals {} :rate-limit nil :run-steps-in nil
                                        :steps [{:name :answer :enter #(bridge/add-answer-to-context % (select-keys (:bridge/message-data %) [:job/id]))}]}}
                            {:pool app-pool :tables tables :scope-kinds scope-kinds
                             :find-session-step (doorman/make-find-session-step app-pool settings)})
                  request  (fn [person]
                             {:request-method :post
                              :headers {"origin" "http://app.test"
                                        "cookie" (first (str/split (:set-cookie (doorman/sign-in! app-pool {:headers {}} person
                                                                                                  {:settings settings :tables [] :session-flags #{}}))
                                                                   #";"))}})
                  job-id   (:job/id (job))]
              (is (= (refusal :doorman/not-found) (without-request-id (:jobs/one (bridge/answer-message ready :no-rate-limits (request anna) {:jobs/one {:job/id job-id}}
                                                                                                           {:wire wire :log log :meter (test-settings/make-meter) :metric-buckets test-settings/metric-buckets})))))
              (is (= {:job/id (str job-id)} (:jobs/one (bridge/answer-message ready :no-rate-limits (request mia) {:jobs/one {:job/id job-id}}
                                                                          {:wire wire :log log :meter (test-settings/make-meter) :metric-buckets test-settings/metric-buckets})))
                  "Mia, in shop 9 too, still does")
              (is (= [] (doorman/find-leaks ready (request anna) {:jobs [job-id]} {:wire wire :log log :meter (test-settings/make-meter) :metric-buckets test-settings/metric-buckets :scope-kinds scope-kinds :seed 7 :size 10}))))))))))
