(ns doorman.when-some-test
  "A sentence's :when may say a column is set, [:some col]: in the check,
   the gates, a scope's state deny and explain-refusal. Shop A's own
   categories are read by anyone, Iron Rainbow's universal ones (no shop)
   are not; a part's public price shows once its shop set a price or a
   template; a shop with a suspension date is frozen. Anna is Staff in shop
   A; Bob is nobody signed in. Runs against Postgres when
   DOORMAN_TEST_PORT is set."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [doorman.test-answers :refer [refusal without-request-id]]
   [cellar.api :as cellar]
   [doorman.api :as doorman]
   [doorman.test-roles :refer [add-member!]]
   [doorman.test-log :refer [log]]
   [doorman.test-settings :as test-settings]
   [doorman.test-wire :refer [wire]]
   [bridge.api :as bridge]))

(def ^:private port test-settings/port)

(def ^:private scope-kinds
  (doorman/check-scope-kinds
   {:shop {:table :shops :parent nil :single? false
           :denies  [{:deny :writes :when [:some :shop/suspended-at]}]   ; a suspension date set: frozen
           :actions {:catalogue/see #{:read} :prices/see #{:read} :parts/edit #{:read :update}}
           :roles   {"Staff" #{:parts/edit}}}}))

(def ^:private shops
  {:table :shops :columns [[:shop/id :uuid {:primary-key? true}] [:shop/suspended-at :time/instant {:optional? true}]]
   :indexes [] :references [] :triggers [] :on-change nil :access :public})

(def ^:private categories
  {:table :categories
   :columns [[:category/id :uuid {:primary-key? true}]
             [:category/shop-id :uuid {:optional? true :references :shop/id}]   ; nil: the universal catalogue's
             [:category/name :string]]
   :indexes [[:category/shop-id]] :references [] :triggers [] :on-change nil
   :access [{:allow :catalogue/see :only #{:read} :to :anyone :when [:some :category/shop-id]}]})

(def ^:private parts
  {:table :parts
   :columns [[:part/id :uuid {:primary-key? true}]
             [:part/shop-id :uuid {:references :shop/id}]
             [:part/pricing :int {:optional? true}]
             [:part/template-id :uuid {:optional? true}]]
   :indexes [[:part/shop-id]] :references [] :triggers [] :on-change nil
   :access [{:allow :prices/see :only #{:read} :to :anyone :when [:or [:some :part/pricing] [:some :part/template-id]]}
            {:allow :parts/edit :only :all-of-the-action :to [:member :part/shop-id]}]})

(def ^:private tables [shops categories parts])

(defn- id [n] (parse-uuid (format "00000000-0000-4000-8000-%012d" n)))

(def ^:private shop-a (id 1001))
(def ^:private shop-c (id 3003))   ; suspended
(def ^:private paul (id 1))        ; Owner of both
(def ^:private anna (id 2))        ; Staff in A and C
(def ^:private category-a (id 11))
(def ^:private category-universal (id 12))
(def ^:private part-priced (id 21))
(def ^:private part-templated (id 22))
(def ^:private part-bare (id 23))
(def ^:private part-c (id 31))

(def ^:private settings {:cookie-name "app_session" :session-days 1 :visitor-session-days 1 :renew-after-minutes 60
                         :allowed-origin "http://app.test" :secure-cookie? false
                         :token-lead "app_" :token-shown-characters 10 :token-longest-days 30})

(defn- query [text] (cellar/make-query-from-sql-text "a test statement" text))

(defn- answer-with [f] {:name :answer :enter #(bridge/add-answer-to-context % (f (:doorman/transaction %) (:bridge/message-data %)))})

(def ^:private handlers
  {:categories/all {:action :catalogue/see :scope :no-scope
                    :params-shape [:map] :answer-shape [:vector :string]
                    :refusals {} :rate-limit nil :run-steps-in nil
                    :steps [(answer-with (fn [t _] (mapv :category/name (cellar/find-rows t (query "SELECT category__name FROM categories ORDER BY category__name") {}))))]}
   :categories/one {:action :catalogue/see :record [:categories :category/id]
                    :params-shape [:map [:category/id :uuid]] :answer-shape [:map [:category/id :uuid]]
                    :refusals {} :rate-limit nil :run-steps-in nil
                    :steps [(answer-with (fn [_ data] (select-keys data [:category/id])))]}
   :prices/all     {:action :prices/see :scope :no-scope
                    :params-shape [:map] :answer-shape [:vector :uuid]
                    :refusals {} :rate-limit nil :run-steps-in nil
                    :steps [(answer-with (fn [t _] (mapv :part/id (cellar/find-rows t (query "SELECT part__id FROM parts ORDER BY part__id") {}))))]}
   :parts/price    {:action :parts/edit :record [:parts :part/id]
                    :params-shape [:map [:part/id :uuid]] :answer-shape [:map [:count :int]]
                    :refusals {} :rate-limit nil :run-steps-in nil
                    :steps [(answer-with (fn [t data] {:count (cellar/run-statement! t (query "UPDATE parts SET part__pricing = 5 WHERE part__id = :part/id") data)}))]}})

(defn- run-sql! [pool text] (cellar/run-statement! pool (query text) {}))

(defn- make-database! [owner-pool]
  (run-sql! owner-pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS doorman CASCADE; DROP SCHEMA IF EXISTS cellar CASCADE")
  (run-sql! owner-pool "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'doorman_app') THEN CREATE ROLE doorman_app LOGIN; END IF; END $$")
  (let [ready (into (doorman/put-gates-on-tables tables scope-kinds) (conj doorman/access-tables doorman/sessions-table doorman/tokens-table))]
    (doorman/install-access-functions! owner-pool tables scope-kinds)
    (with-out-str @(:slow-part (cellar/migrate! owner-pool ready [] test-settings/migrate-options)))
    (cellar/grant-tables-to-role! owner-pool ready "doorman_app"))
  (run-sql! owner-pool (str "INSERT INTO shops VALUES ('" shop-a "', NULL), ('" shop-c "', now())"))
  (run-sql! owner-pool (str "INSERT INTO categories VALUES ('" category-a "', '" shop-a "', 'Wheels'), ('" category-universal "', NULL, 'Universal frames')"))
  (run-sql! owner-pool (str "INSERT INTO parts VALUES ('" part-priced "', '" shop-a "', 120, NULL), ('" part-templated "', '" shop-a "', NULL, '" (id 99) "'), "
                            "('" part-bare "', '" shop-a "', NULL, NULL), ('" part-c "', '" shop-c "', NULL, NULL)"))
  (cellar/run-in-transaction! owner-pool {:read-only? false}
                              (fn [transaction]
                                (doorman/add-scope! transaction scope-kinds :shop shop-a paul nil)
                                (doorman/add-scope! transaction scope-kinds :shop shop-c paul nil)
                                (add-member! transaction scope-kinds [:shop shop-a] anna #{"Staff"})
                                (add-member! transaction scope-kinds [:shop shop-c] anna #{"Staff"}))))

(deftest a-when-says-a-column-is-set
  (if-not port
    (println "doorman.when-some-test: skipped, DOORMAN_TEST_PORT is not set")
    (let [owner-pool (test-settings/start-pool! (System/getenv "DOORMAN_TEST_USER") 2)]
      (make-database! owner-pool)
      (let [app-pool (test-settings/start-pool! "doorman_app" 4)
            ready    (doorman/put-access-into-handlers handlers {:pool app-pool :tables tables :scope-kinds scope-kinds
                                                                 :find-session-step (doorman/make-find-session-step app-pool settings)})
            request  (fn [person]
                       {:request-method :post
                        :headers (cond-> {"origin" "http://app.test"}
                                   person (assoc "cookie" (first (str/split (:set-cookie (doorman/sign-in! app-pool {:headers {}} person
                                                                                                           {:settings settings :tables [] :session-flags #{}}))
                                                                            #";"))))})
            ask      (fn [person handler-name params]
                       (without-request-id (get (bridge/answer-message ready :no-rate-limits (request person) {handler-name params} {:wire wire :log log})
                                                handler-name)))]
        (try
          (testing "the gate: a shop's own categories are read by anyone, the universal ones are not"
            (is (= ["Wheels"] (ask nil :categories/all {})) "Bob, nobody signed in")
            (is (= ["Wheels"] (ask anna :categories/all {}))))
          (testing "the check: a universal category is not found"
            (is (= {:category/id (str category-a)} (ask nil :categories/one {:category/id category-a})))
            (is (= (refusal :doorman/signed-out) (ask nil :categories/one {:category/id category-universal})) "Bob, signed out")
            (is (= (refusal :doorman/not-found) (ask anna :categories/one {:category/id category-universal}))))
          (testing "[:or [:some …] [:some …]]: a price shows once the shop set a price or a template"
            (is (= [(str part-priced) (str part-templated)] (ask nil :prices/all {})))
            (is (= {:count 1} (ask anna :parts/price {:part/id part-bare})) "Anna sets the bare part's price")
            (is (= [(str part-priced) (str part-templated) (str part-bare)] (ask nil :prices/all {})) "and now it shows"))
          (testing "a scope's state deny by [:some …]: shop C has a suspension date, so it is frozen"
            (is (= (refusal :doorman/not-allowed) (ask anna :parts/price {:part/id part-c})) "the check")
            (is (= 0 (doorman/run-as-asker-of-request! app-pool (request anna) {:settings settings :read-only? false :scope-kinds scope-kinds}
                                                       #(cellar/run-statement! % (query (str "UPDATE parts SET part__pricing = 1 WHERE part__id = '" part-c "'")) {})))
                "the gate")
            (run-sql! owner-pool (str "UPDATE shops SET shop__suspended_at = NULL WHERE shop__id = '" shop-c "'"))
            (is (= {:count 1} (ask anna :parts/price {:part/id part-c})) "the date cleared, she writes again"))
          (testing "explain-refusal says the :when doesn't hold"
            (let [answer (doorman/explain-refusal {:pool app-pool :handlers handlers :tables tables :scope-kinds scope-kinds}
                                                  {:person-id anna :visitor-id nil :session-flags #{}}
                                                  :categories/one {:category/id category-universal})]
              (is (= :doorman/not-found (:answer answer)))
              (is (= [{:says [:some :category/shop-id] :holds? false}] (map :when (:sentences answer))))))
          (finally
            (cellar/stop-pool! app-pool)
            (cellar/stop-pool! owner-pool)))))))
