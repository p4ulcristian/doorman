(ns doorman.access-extras-test
  "The words built on v0.6.0's, end to end, as the app's own role, which
   owns nothing: parent scopes, a kind of one scope, [:member column
   action], :via and its guard, a session flag's deny and a scope state's,
   a visitor's rows (and their move at sign-in), the functions that narrow
   a list by an action, \"what may I do?\", explain-refusal and the leak
   test. Each word is allowed and refused by the check (a message) and by
   the gates (a statement with no WHERE, a write). Runs when
   DOORMAN_TEST_PORT is set."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [cellar.api :as cellar]
   [doorman.api :as doorman]
   [doorman.asking :as asking]
   [doorman.test-log :refer [log]]
   [doorman.test-settings :as test-settings]
   [doorman.test-wire :refer [wire]]
   [bridge.api :as bridge]
   [wire.api :as wire-api]))

(def ^:private port (some-> (System/getenv "DOORMAN_TEST_PORT") parse-long))

;; -----------------------------------------------------------------------------
;; The app

(def ^:private scope-kinds
  (doorman/check-scope-kinds
   {:company  {:table :companies :parent nil :single? false
               :actions {:company/report #{:read}}
               :roles   {"Director" #{:company/report :orders/see}}
               :denies  []}
    :shop     {:table :shops :parent :company :single? false
               :actions {:orders/see #{:read} :orders/edit #{:read :insert :update} :orders/note #{:read :update}
                         :agreements/make #{:read :insert :update}}
               :roles   {"Staff" #{:orders/see :orders/edit :agreements/make} "Clerk" #{:orders/see}}
               :denies  [{:deny :writes :session :impersonated}
                         {:deny :writes :when [:= :shop/status "suspended"]}]}
    :partner  {:table :partners :parent nil :single? false
               :actions {:agreements/see #{:read} :agreements/accept #{:read :update}}
               :roles   {"Agent" #{:agreements/see :agreements/accept}}
               :denies  []}
    :platform {:table nil :parent nil :single? true
               :actions {:platform/support #{:read}}
               :roles   {"Support" #{:platform/support}}
               :denies  []}}))

(def ^:private companies
  {:table :companies :columns [[:company/id :uuid {:primary-key? true}]] :indexes [] :references [] :triggers [] :on-change nil :access :public})

(def ^:private shops
  {:table :shops :columns [[:shop/id :uuid {:primary-key? true}] [:shop/status :string]] :indexes [] :references [] :triggers [] :on-change nil
   :access :public})

(def ^:private partners
  {:table :partners :columns [[:partner/id :uuid {:primary-key? true}]] :indexes [] :references [] :triggers [] :on-change nil :access :public})

(def ^:private orders
  {:table     :orders
   :columns   [[:order/id :uuid {:primary-key? true :default :random-uuid}]
               [:order/shop-id :uuid {:references :shop/id}]
               [:order/customer-id :uuid {:optional? true}]
               [:order/visitor-id :uuid {:optional? true}]
               [:order/status :string]]
   :indexes   [[:order/shop-id] [:order/customer-id] [:order/visitor-id]]
   :references []
   :triggers   []
   :on-change nil
   :visitor   {:column :order/visitor-id :moves-to :order/customer-id}
   :access    [{:allow #{:orders/see :orders/edit} :only :all-of-the-action :to [:member :order/shop-id]}
               {:allow #{:orders/see :orders/edit} :only :all-of-the-action :to [:me :order/customer-id]}
               {:allow #{:orders/see :orders/edit} :only :all-of-the-action :to [:me :order/visitor-id]}
               {:allow :orders/note :only :all-of-the-action :to [:member :order/shop-id :orders/edit]}
               {:allow :orders/see :only :all-of-the-action :to [:member :platform :platform/support]}
               {:allow :orders/see :only :all-of-the-action :via {:table :agreements
                                         :match {:order/shop-id :agreement/shop-id}
                                         :to    [:member :agreement/partner-id :agreements/see]
                                         :when  [:= :agreement/status "active"]}}]})

(def ^:private agreements
  {:table     :agreements
   :columns   [[:agreement/id :uuid {:primary-key? true}]
               [:agreement/shop-id :uuid {:references :shop/id}]
               [:agreement/partner-id :uuid {:references :partner/id}]
               [:agreement/status :string]
               [:agreement/note :string]]
   :indexes   [[:agreement/shop-id] [:agreement/partner-id]]
   :references []
   :triggers   []
   :on-change nil
   :access    [{:allow #{:agreements/see :agreements/accept} :only :all-of-the-action :to [:member :agreement/partner-id]}
               {:allow :agreements/make :only :all-of-the-action :to [:member :agreement/shop-id]}]})

(def ^:private tables [companies shops partners orders agreements])

(defn- id [n] (parse-uuid (format "00000000-0000-4000-8000-%012d" n)))

(def ^:private company-1 (id 1001))
(def ^:private shop-7 (id 7007))
(def ^:private shop-8 (id 8008))
(def ^:private shop-9 (id 9009))
(def ^:private partner-1 (id 5005))
(def ^:private platform (id 3003))

(def ^:private paul (id 1)) ; Owner of shop 7
(def ^:private anna (id 2)) ; Staff in shop 7
(def ^:private carl (id 3)) ; Clerk in shop 7
(def ^:private dora (id 4)) ; Director of company 1, above shop 7
(def ^:private bob  (id 5)) ; Owner of shop 9
(def ^:private gus  (id 6)) ; Owner of shop 8, suspended
(def ^:private pia  (id 7)) ; Agent of partner 1
(def ^:private sam  (id 8)) ; Support in the platform
(def ^:private cora (id 9)) ; a customer of shop 9
(def ^:private eve  (id 10)) ; signed in, nowhere
(def ^:private vic  (id 11)) ; a visitor who signs in

(def ^:private order-42 (id 42))   ; shop 7
(def ^:private order-80 (id 80))   ; shop 8
(def ^:private order-99 (id 99))   ; shop 9
(def ^:private order-c  (id 123))  ; shop 9, Cora's
(def ^:private order-v  (id 124))  ; shop 9, a visitor's
(def ^:private order-w  (id 125))  ; shop 9, another visitor's
(def ^:private agreement-7 (id 707))  ; shop 7 and partner 1, active
(def ^:private agreement-9 (id 909))  ; shop 9 and partner 1, ended

(def ^:private settings {:cookie-name "app_session" :session-days 1 :visitor-session-days 1 :renew-after-minutes 60
                         :allowed-origin "http://app.test" :secure-cookie? false
                         :token-lead "app_" :token-shown-characters 10 :token-longest-days 30})

;; -----------------------------------------------------------------------------
;; Handlers

(def ^:private find-all-orders (cellar/make-query-from-sql-text "all orders, no WHERE" "SELECT order__id FROM orders ORDER BY order__id"))
(def ^:private update-status (cellar/make-query-from-sql-text "an order's status" "UPDATE orders SET order__status = 'seen' WHERE order__id = :order/id"))
(def ^:private insert-order (cellar/make-query-from-sql-text "a new order" "INSERT INTO orders (order__shop_id, order__status) VALUES (:order/shop-id, 'new')"))

(defn- answer-with [f] {:name :answer :enter #(bridge/add-answer-to-context % (f %))})

(def ^:private handlers
  {:orders/one    {:action :orders/see :record [:orders :order/id] :params-shape [:map [:order/id :uuid]]
                   :answer-shape [:map [:order/id :uuid]] :rate-limit nil :run-steps-in nil
                   :steps [(answer-with #(select-keys (:bridge/message-data %) [:order/id]))]}
   :orders/edit   {:action :orders/edit :record [:orders :order/id] :params-shape [:map [:order/id :uuid]]
                   :answer-shape [:map [:order/id :uuid]] :rate-limit nil :run-steps-in nil
                   :steps [(answer-with #(do (cellar/run-statement! (:doorman/transaction %) update-status (:bridge/message-data %))
                                             (select-keys (:bridge/message-data %) [:order/id])))]}
   :orders/note   {:action :orders/note :record [:orders :order/id] :params-shape [:map [:order/id :uuid]]
                   :answer-shape [:map [:order/id :uuid]] :rate-limit nil :run-steps-in nil
                   :steps [(answer-with #(select-keys (:bridge/message-data %) [:order/id]))]}
   :orders/all    {:action :orders/see :scope :every-scope-holding-the-action :params-shape [:map] :answer-shape [:vector :uuid] :rate-limit nil :run-steps-in nil
                   :steps [(answer-with #(mapv :order/id (cellar/find-rows (:doorman/transaction %) find-all-orders {})))]}
   :orders/place  {:action :orders/edit :scope [:shop :order/shop-id] :params-shape [:map [:order/shop-id :uuid]] :answer-shape [:map] :rate-limit nil
                   :run-steps-in nil
                   :steps [(answer-with #(do (cellar/run-statement! (:doorman/transaction %) insert-order (:bridge/message-data %)) {}))]}})

;; -----------------------------------------------------------------------------
;; Helpers

(defn- run-sql! [pool text] (cellar/run-statement! pool (cellar/make-query-from-sql-text "test" text) {}))

(defn- start-pool! [user] (test-settings/start-pool! user 4))

(defn- make-database! [owner-pool]
  (run-sql! owner-pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS doorman CASCADE; DROP SCHEMA IF EXISTS cellar CASCADE")
  (run-sql! owner-pool "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'doorman_app') THEN CREATE ROLE doorman_app LOGIN; END IF; END $$")
  (let [ready (into (doorman/put-gates-on-tables tables scope-kinds) (conj doorman/access-tables doorman/sessions-table doorman/tokens-table))]
    (doorman/install-access-functions! owner-pool tables scope-kinds)
    (with-out-str @(:slow-part (cellar/migrate! owner-pool ready [] test-settings/migrate-options)))
    (cellar/grant-tables-to-role! owner-pool ready "doorman_app"))
  (run-sql! owner-pool (str "INSERT INTO companies VALUES ('" company-1 "')"))
  (run-sql! owner-pool (str "INSERT INTO shops VALUES ('" shop-7 "', 'open'), ('" shop-8 "', 'suspended'), ('" shop-9 "', 'open')"))
  (run-sql! owner-pool (str "INSERT INTO partners VALUES ('" partner-1 "')"))
  (cellar/run-in-transaction! owner-pool {:read-only? false}
                              (fn [transaction]
                                (doorman/add-scope! transaction scope-kinds :company company-1 dora nil)
                                (doorman/remove-membership! transaction [:company company-1] dora)
                                (doorman/add-membership! transaction [:company company-1] dora "Director")
                                (doorman/add-scope! transaction scope-kinds :shop shop-7 paul company-1)
                                (doorman/add-scope! transaction scope-kinds :shop shop-8 gus nil)
                                (doorman/add-scope! transaction scope-kinds :shop shop-9 bob nil)
                                (doorman/add-membership! transaction [:shop shop-7] anna "Staff")
                                (doorman/add-membership! transaction [:shop shop-7] carl "Clerk")
                                (doorman/add-scope! transaction scope-kinds :partner partner-1 pia nil)
                                (doorman/remove-membership! transaction [:partner partner-1] pia)
                                (doorman/add-membership! transaction [:partner partner-1] pia "Agent")
                                (doorman/add-scope! transaction scope-kinds :platform platform sam nil)
                                (doorman/remove-membership! transaction [:platform platform] sam)
                                (doorman/add-membership! transaction [:platform platform] sam "Support"))))

(defn- add-orders!
  "The test's rows, written by the tables' owner (a superuser here, which
   passes every gate)."
  [owner-pool visitor-1 visitor-2]
  (run-sql! owner-pool (str "INSERT INTO orders VALUES "
                            "('" order-42 "', '" shop-7 "', NULL, NULL, 'open'), "
                            "('" order-80 "', '" shop-8 "', NULL, NULL, 'open'), "
                            "('" order-99 "', '" shop-9 "', NULL, NULL, 'open'), "
                            "('" order-c "', '" shop-9 "', '" cora "', NULL, 'open'), "
                            "('" order-v "', '" shop-9 "', NULL, '" visitor-1 "', 'open'), "
                            "('" order-w "', '" shop-9 "', NULL, '" visitor-2 "', 'open')"))
  (run-sql! owner-pool (str "INSERT INTO agreements VALUES "
                            "('" agreement-7 "', '" shop-7 "', '" partner-1 "', 'active', ''), "
                            "('" agreement-9 "', '" shop-9 "', '" partner-1 "', 'ended', '')")))

(defn- cookie-of [set-cookie] (first (str/split set-cookie #";")))

(defn- request-of [cookie]
  {:request-method :post :headers (cond-> {"origin" "http://app.test"} cookie (assoc "cookie" cookie))})

(defn- ask
  "A message's answer as over http (JSON data), by the app's wire."
  [ready cookie handler-name params]
  (get (bridge/answer-message ready :no-rate-limits (request-of cookie) {handler-name params} {:wire wire :log log})
       handler-name))

(defn- texts [answer] (set (map str (filter #(or (uuid? %) (string? %)) (tree-seq coll? seq answer)))))

(defn- as-asker
  "(f transaction) in a transaction of the app's own role that tells
   Postgres who is asking, as the check does; rolled back after. A
   statement the gates refuse throws."
  [app-pool asker f]
  (let [result (volatile! nil)]
    (try
      (cellar/run-in-transaction! app-pool {:read-only? false}
                                  (fn [transaction]
                                    (asking/set-asking! transaction (merge {:person-id nil :visitor-id nil :session-flags #{}} asker)
                                                        {:acting  :every-action
                                                         :holding (if-let [person (:person-id asker)] (doorman/list-actions-of-person transaction scope-kinds person) {})})
                                    (vreset! result (f transaction))
                                    (throw (ex-info "roll back" {::roll-back true}))))
      (catch clojure.lang.ExceptionInfo e
        (if (::roll-back (ex-data e)) @result (throw e))))))

(defn- orders-seen-by [app-pool asker]
  (set (as-asker app-pool asker #(map :order/id (cellar/find-rows % find-all-orders {})))))

(defn- updated-by [app-pool asker order-id]
  (as-asker app-pool asker #(cellar/run-statement! % update-status {:order/id order-id})))

(defn- refused? [f]
  (try (f) false (catch Exception e (boolean (re-find #"row-level security|violates|permission" (str (.getMessage e) (some-> e .getCause .getMessage)))))))

(def ^:private not-found {:error "Not found."})
(def ^:private not-allowed {:error "Not allowed."})

;; -----------------------------------------------------------------------------
;; Pure checks

(deftest the-scope-kinds-are-checked-whole
  (let [problems #(try (doorman/check-scope-kinds %) [] (catch clojure.lang.ExceptionInfo e (:problems (ex-data e))))
        kind     {:table nil :parent nil :single? false :actions {:a/see #{:read}} :roles {} :denies []}]
    (is (some #(re-find #":parent|missing" %) (problems {:shop (dissoc kind :parent)})) "every key is written out")
    (is (some #(re-find #"loop" %) (problems {:a (assoc kind :parent :b) :b (assoc kind :parent :a :actions {:b/see #{:read}})})))
    (is (some #(re-find #"action of both" %) (problems {:a kind :b kind})))
    (is (some #(re-find #"names :b/see" %) (problems {:a (assoc kind :denies [{:deny :b/see :session :impersonated}])
                                                      :b (assoc kind :actions {:b/see #{:read}})}))
        "a deny is about its kind's own actions")
    (is (some #(re-find #"has none" %) (problems {:a (assoc kind :denies [{:deny :writes :when [:= :a/status "x"]}])}))
        "a state deny reads the kind's :table")
    (is (= [] (problems {:a kind :b (assoc kind :parent :a :actions {:b/see #{:read}} :roles {"R" #{:b/see}})}))
        "a role of a kind below")
    (is (some #(re-find #"names :a/see" %) (problems {:a kind
                                                      :b (assoc kind :parent :a :actions {:b/see #{:read}} :roles {"R" #{:a/see}})}))
        "a role holds its kind's actions and those below, not above")))

(deftest the-new-words-are-checked
  (let [problems #(try (doorman/make-sql-conditions % scope-kinds) [] (catch clojure.lang.ExceptionInfo e (:problems (ex-data e))))]
    (is (some #(re-find #"needs an index on \[:agreement/shop-id\]" %)
              (problems (assoc tables 4 (assoc agreements :indexes [[:agreement/partner-id]])))))
    (is (some #(re-find #"must have exactly one scope" %)
              (problems (assoc tables 3 (assoc orders :access [{:allow :orders/see :only :all-of-the-action :to [:member :shop]}])))))
    (is (some #(re-find #"names the action held on the related row" %)
              (problems (assoc tables 3 (assoc orders :access [{:allow :orders/see :only :all-of-the-action :via (assoc (:via (last (:access orders)))
                                                                                               :to [:member :agreement/partner-id])}])))))
    (is (some #(re-find #"nobody in a shop can hold :company/report" %)
              (problems (assoc tables 3 (assoc orders :access [{:allow :orders/see :only :all-of-the-action :to [:member :order/shop-id :company/report]}])))))
    (is (= [] (problems (assoc tables 3 (assoc orders :access [{:allow :orders/edit :only :all-of-the-action :via {:table :agreements :match {:order/shop-id :agreement/shop-id}
                                                                                          :to :signed-in}}]))))
        "an open write narrowed by a :via")
    (is (some #(re-find #":visitor's column" %)
              (problems (assoc tables 3 (assoc orders :visitor {:column :order/status :moves-to nil})))))))

;; -----------------------------------------------------------------------------
;; End to end

(deftest the-extras-end-to-end
  (if-not port
    (println "doorman.access-extras-test: skipped, DOORMAN_TEST_PORT is not set")
    (let [owner-pool (start-pool! (System/getenv "DOORMAN_TEST_USER"))]
      (make-database! owner-pool)
      (let [app-pool   (start-pool! "doorman_app")
            find-step  (doorman/make-find-session-step app-pool settings)
            ready      (doorman/put-access-into-handlers handlers {:pool app-pool :tables tables :scope-kinds scope-kinds
                                                                   :find-session-step find-step})
            my-actions {:doorman/my-actions (doorman/make-my-actions-handler {:pool app-pool :tables tables :scope-kinds scope-kinds
                                                                              :find-session-step find-step :rate-limit nil})}
            sign-in    (fn [person flags] (cookie-of (:set-cookie (doorman/sign-in! app-pool {:headers {}} person
                                                                                    {:settings settings :tables tables
                                                                                     :session-flags flags}))))
            visitor-1  (doorman/start-visitor-session! app-pool {:headers {}} settings)
            visitor-2  (doorman/start-visitor-session! app-pool {:headers {}} settings)
            v1-cookie  (cookie-of (:set-cookie visitor-1))
            v2-cookie  (cookie-of (:set-cookie visitor-2))
            v1         (:visitor-id visitor-1)
            v2         (:visitor-id visitor-2)
            explain    #(doorman/explain-refusal {:pool app-pool :handlers handlers :tables tables :scope-kinds scope-kinds}
                                                 (merge {:person-id nil :visitor-id nil :session-flags #{}} %1) %2 %3)
            what-may   (fn [cookie]
                         (:shaped-data (wire-api/turn-json-data-into-shaped-data-or-errors
                                        wire doorman/my-actions-answer-shape
                                        (ask my-actions cookie :doorman/my-actions {}))))]
        (add-orders! owner-pool v1 v2)
        (try
          (testing "a visitor session: its own cookie; a request that has one makes none"
            (is (uuid? v1))
            (is (= {:visitor-id v1 :set-cookie nil} (doorman/start-visitor-session! app-pool (request-of v1-cookie) settings))))

          (testing "parent scopes: a Director of the company reaches its shops' orders, not another shop's"
            (let [cookie (sign-in dora #{})]
              (is (= #{(str order-42)} (texts (ask ready cookie :orders/one {:order/id order-42}))))
              (is (= not-found (ask ready cookie :orders/one {:order/id order-99})))
              (is (= not-allowed (ask ready cookie :orders/edit {:order/id order-42})) "Director holds orders/see only"))
            (is (= #{order-42} (orders-seen-by app-pool {:person-id dora})) "the gates: shop 7's only")
            (is (= #{} (orders-seen-by app-pool {:person-id eve})) "the gates: nobody's for a stranger"))

          (testing "a kind of one scope: platform Support sees every order; a second platform is refused"
            (let [cookie (sign-in sam #{})]
              (is (= #{(str order-99)} (texts (ask ready cookie :orders/one {:order/id order-99}))))
              (is (= not-found (ask ready (sign-in eve #{}) :orders/one {:order/id order-99}))))
            (is (= #{order-42 order-80 order-99 order-c order-v order-w} (orders-seen-by app-pool {:person-id sam})))
            (is (= 0 (updated-by app-pool {:person-id sam} order-99)) "the gates: Support only reads")
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exactly one scope"
                                  (cellar/run-in-transaction! owner-pool {:read-only? false}
                                                              #(doorman/add-scope! % scope-kinds :platform (id 3004) sam nil)))))

          (testing "[:member column action]: noting needs orders/edit held in the order's shop"
            (is (= #{(str order-42)} (texts (ask ready (sign-in anna #{}) :orders/note {:order/id order-42}))))
            (is (= not-allowed (ask ready (sign-in carl #{}) :orders/note {:order/id order-42})) "a Clerk sees, can't note")
            (is (= 1 (updated-by app-pool {:person-id anna} order-42)) "the gates: Staff may update (orders/edit, orders/note)")
            (is (= 0 (updated-by app-pool {:person-id carl} order-42)) "the gates: a Clerk may not"))

          (testing ":via: a partner's Agent sees a shop's orders while their agreement is active"
            (let [cookie (sign-in pia #{})]
              (is (= #{(str order-42)} (texts (ask ready cookie :orders/one {:order/id order-42}))))
              (is (= not-found (ask ready cookie :orders/one {:order/id order-99})) "agreement 9 has ended")
              (is (= #{(str order-42)} (texts (ask ready cookie :orders/all {})))))
            (is (= #{order-42} (orders-seen-by app-pool {:person-id pia})))
            (is (= 0 (updated-by app-pool {:person-id pia} order-42)) "the gates: the via gives seeing only"))

          (testing "the guard: what a :via matches changes only by someone who may create such a row"
            (let [move   #(cellar/run-statement! % (cellar/make-query-from-sql-text "move" (str "UPDATE agreements SET agreement__shop_id = '" shop-9 "' WHERE agreement__id = '" agreement-7 "'")) {})
                  revive #(cellar/run-statement! % (cellar/make-query-from-sql-text "revive" (str "UPDATE agreements SET agreement__status = 'active' WHERE agreement__id = '" agreement-9 "'")) {})
                  note   #(cellar/run-statement! % (cellar/make-query-from-sql-text "note" (str "UPDATE agreements SET agreement__note = 'hi' WHERE agreement__id = '" agreement-7 "'")) {})]
              (is (refused? #(as-asker app-pool {:person-id pia} move)) "the Agent may not point it at shop 9")
              (is (refused? #(as-asker app-pool {:person-id pia} revive)) "nor make an ended one hold again")
              (is (= 1 (as-asker app-pool {:person-id pia} note)) "a change to nothing it matches is fine")
              (is (= 1 (as-asker app-pool {:person-id bob} revive)) "shop 9's Owner, who may make agreements, may")))

          (testing "a session flag's deny: impersonated, Anna sees but doesn't write"
            (let [cookie (sign-in anna #{:impersonated})]
              (is (= #{(str order-42)} (texts (ask ready cookie :orders/one {:order/id order-42}))))
              (is (= not-allowed (ask ready cookie :orders/edit {:order/id order-42})))
              (is (= not-allowed (ask ready cookie :orders/place {:order/shop-id shop-7})) "a handler without a record too"))
            (is (= #{order-42} (orders-seen-by app-pool {:person-id anna :session-flags #{:impersonated}})))
            (is (= 0 (updated-by app-pool {:person-id anna :session-flags #{:impersonated}} order-42)))
            (is (refused? #(as-asker app-pool {:person-id anna :session-flags #{:impersonated}}
                                     (fn [t] (cellar/run-statement! t insert-order {:order/shop-id shop-7}))))))

          (testing "a scope state's deny: suspended shop 8 keeps its reads, loses its writes"
            (let [cookie (sign-in gus #{})]
              (is (= #{(str order-80)} (texts (ask ready cookie :orders/one {:order/id order-80}))))
              (is (= not-allowed (ask ready cookie :orders/edit {:order/id order-80}))))
            (is (= #{order-80} (orders-seen-by app-pool {:person-id gus})))
            (is (= 0 (updated-by app-pool {:person-id gus} order-80)))
            (is (refused? #(as-asker app-pool {:person-id gus} (fn [t] (cellar/run-statement! t insert-order {:order/shop-id shop-8})))))
            (is (= 1 (updated-by app-pool {:person-id bob} order-99)) "open shop 9's Owner still writes"))

          (testing "a visitor's rows: theirs alone, by the check and the gates"
            (is (= #{(str order-v)} (texts (ask ready v1-cookie :orders/one {:order/id order-v}))))
            (is (= not-found (ask ready v2-cookie :orders/one {:order/id order-v})) "another visitor")
            (is (= not-found (ask ready (sign-in eve #{}) :orders/one {:order/id order-v})) "a signed-in stranger")
            (is (= #{(str order-v)} (texts (ask ready v1-cookie :orders/all {}))))
            (is (= #{order-v} (orders-seen-by app-pool {:visitor-id v1})))
            (is (= 1 (updated-by app-pool {:visitor-id v1} order-v)))
            (is (= 0 (updated-by app-pool {:visitor-id v1} order-w))))

          (testing "a list narrowed by an action: doorman.\"may orders/edit on orders\"()"
            (let [may-edit (cellar/make-query-from-sql-text "may edit" "SELECT * FROM doorman.\"may orders/edit on orders\"() AS may (order__id)")]
              (is (= #{order-42} (set (as-asker app-pool {:person-id anna} #(map :order/id (cellar/find-rows % may-edit {}))))))
              (is (= #{} (set (as-asker app-pool {:person-id dora} #(map :order/id (cellar/find-rows % may-edit {}))))) "a Director only sees")
              (is (= #{} (set (as-asker app-pool {:person-id gus} #(map :order/id (cellar/find-rows % may-edit {}))))) "suspended")))

          (testing "what may I do?"
            (is (= [{:scope-kind nil :scope-id nil :actions [:orders/edit :orders/see]}
                    {:scope-kind :company :scope-id company-1 :actions [:company/report :orders/see]}
                    {:scope-kind :shop :scope-id shop-7 :actions [:orders/see]}]
                   (what-may (sign-in dora #{}))) "the Director's, in the company and the shop below it")
            (is (= [{:scope-kind nil :scope-id nil :actions [:orders/edit :orders/see]}
                    {:scope-kind :shop :scope-id shop-7 :actions [:agreements/make :orders/edit :orders/note :orders/see]}]
                   (what-may (sign-in anna #{}))) "orders/note, which needs orders/edit there")
            (is (= [{:scope-kind nil :scope-id nil :actions [:orders/see]}
                    {:scope-kind :shop :scope-id shop-7 :actions [:orders/see]}]
                   (what-may (sign-in anna #{:impersonated}))) "impersonated: the shop's writes are out")
            (is (= [{:scope-kind nil :scope-id nil :actions [:orders/edit :orders/see]}
                    {:scope-kind :shop :scope-id shop-8 :actions [:orders/see]}]
                   (what-may (sign-in gus #{}))) "suspended: shop 8's writes are out")
            (is (= [{:scope-kind nil :scope-id nil :actions [:orders/edit :orders/see]}
                    {:scope-kind :partner :scope-id partner-1 :actions [:agreements/accept :agreements/see]}
                    {:scope-kind :shop :scope-id shop-7 :actions [:orders/see]}]
                   (what-may (sign-in pia #{}))) "the via names shop 7")
            (is (= [{:scope-kind nil :scope-id nil :actions [:orders/edit :orders/see]}
                    {:scope-kind :platform :scope-id platform :actions [:platform/support]}]
                   (what-may (sign-in sam #{}))) "Support: orders/see in every scope")
            (is (= [{:scope-kind nil :scope-id nil :actions [:orders/edit :orders/see]}] (what-may v1-cookie)) "a visitor")
            (is (= [] (what-may nil)) "nobody"))

          (testing "explain-refusal"
            (let [answer (explain {:person-id anna} :orders/edit {:order/id order-99})]
              (is (= :not-found (:answer answer)))
              (is (= {:table :orders :id order-99 :exists? true :scopes [{:column :order/shop-id :scope-kind :shop :scope-id shop-9}]}
                     (:record answer)))
              (is (= {:says [:member :order/shop-id] :holds? false :scope-kind :shop :scope-id shop-9 :needs :orders/edit :held-there? false}
                     (:to (first (:sentences answer)))))
              (is (= [] (:memberships answer)) "Anna is in no scope of order 99"))
            (let [answer (explain {:person-id anna :session-flags #{:impersonated}} :orders/edit {:order/id order-42})]
              (is (= :not-allowed (:answer answer)))
              (is (true? (:holds? (first (:sentences answer)))) "her role holds it there")
              (is (= [{:scope-kind :shop :deny {:deny :writes :session :impersonated} :says-no? true}
                      {:scope-kind :shop :deny {:deny :writes :when [:= :shop/status "suspended"]} :says-no? false :scope-ids []}]
                     (:denies answer))))
            (let [answer (explain {:person-id gus} :orders/edit {:order/id order-80})]
              (is (= :not-allowed (:answer answer)))
              (is (= [shop-8] (:scope-ids (second (:denies answer))))))
            (let [answer (explain {:person-id dora} :orders/one {:order/id order-42})]
              (is (= :allowed (:answer answer)))
              (is (= [{:scope-kind :company :scope-id company-1 :role "Director"}] (:memberships answer)) "her role, above the shop")
              (is (= [{:scope-kind :shop :scope-id shop-7 :actions [:orders/see]}] (:actions-in-scopes answer))))
            (let [answer (explain {:person-id pia} :orders/one {:order/id order-99})]
              (is (= {:says (:via (last (:access orders))) :holds? false} (:via (last (:sentences answer))))
                  "the via's related row isn't there for her"))
            (let [answer (explain {} :orders/all {})]
              (is (= :not-found (:answer answer)) "nobody can have orders/see")
              (is (= #{:signed-in :visitor [:holds :orders/see] [:holds :platform/support] [:holds :agreements/see]}
                     (set (map :way (:sentences answer)))))))

          (testing "the leak test, for each asker, with the rows they must not reach"
            (let [leaks #(doorman/find-leaks ready (request-of %1) {:orders %2} {:wire wire :log log :scope-kinds scope-kinds :seed 7 :size 30})]
              (is (= [] (leaks (sign-in anna #{}) [order-80 order-99 order-c order-v])) "Staff of shop 7")
              (is (= [] (leaks (sign-in dora #{}) [order-80 order-99 order-v])) "the Director")
              (is (= [] (leaks (sign-in pia #{}) [order-80 order-99 order-c])) "the partner, through the via")
              (is (= [] (leaks v2-cookie [order-v order-42])) "another visitor")
              (is (= [] (leaks (sign-in eve #{}) [order-v order-42])) "a signed-in stranger")))

          (testing "sign-in: the visitor's rows become the person's, the visitor session ends"
            (doorman/sign-in! app-pool (request-of v1-cookie) vic {:settings settings :tables tables :session-flags #{}})
            (is (= #{} (orders-seen-by app-pool {:visitor-id v1})) "the visitor has nothing left")
            (is (= #{order-v} (orders-seen-by app-pool {:person-id vic})) "the person has it")
            (is (= #{order-w} (orders-seen-by app-pool {:visitor-id v2})) "another visitor's rows stay")
            (is (nil? (:doorman/visitor-id (bridge/run-steps-until-answered [find-step] {:bridge/request (request-of v1-cookie)
                                                                                           :bridge/log log})))
                "the visitor's cookie finds nobody"))
          (finally
            (cellar/stop-pool! app-pool)
            (cellar/stop-pool! owner-pool)))))))
