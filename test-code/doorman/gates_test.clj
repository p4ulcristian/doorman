(ns doorman.gates-test
  "The gates, end to end: the app's own role owns nothing, so Postgres's
   row rules hold for it. A query with no WHERE still returns only the
   asking person's rows, and a new row in another shop is refused. Runs
   when DOORMAN_TEST_PORT is set."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [doorman.test-answers :refer [refusal refusal-json read-without-request-ids without-request-id]]
   [doorman.api :as doorman]
   [doorman.test-roles :refer [add-member!]]
   [doorman.test-settings :as test-settings]
   [cellar.api :as cellar]
   [doorman.test-log :refer [log]]
   [doorman.test-wire :refer [wire]]
   [bridge.api :as bridge])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

(def ^:private port (some-> (System/getenv "DOORMAN_TEST_PORT") parse-long))

(def ^:private scope-kinds
  (doorman/check-scope-kinds
   {:shop {:table   :shops
           :actions {:orders/see #{:read} :orders/edit #{:read :insert :update}}
           :roles   {"Staff" #{:orders/see :orders/edit}}
           :parent  nil :single? false :denies []}}))

(def ^:private shops
  {:table     :shops
   :columns   [[:shop/id :uuid {:primary-key? true}]]
   :indexes   []
   :uniques   []
   :checks    []
   :references []
   :triggers   []
   :on-change nil
   :access    :public})

(def ^:private orders
  {:table     :orders
   :columns   [[:order/id :uuid {:primary-key? true :default :random-uuid}]
               [:order/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]]
   :indexes   []
   :uniques   []
   :checks    []
   :references []
   :triggers   []
   :on-change nil
   :access    [{:allow #{:orders/see :orders/edit} :only :all-of-the-action :to [:member :order/shop-id]}]})

(def ^:private paul #uuid "00000000-0000-4000-8000-00000000000a")
(def ^:private anna #uuid "00000000-0000-4000-8000-00000000000b")
(def ^:private bob  #uuid "00000000-0000-4000-8000-00000000000d")
(def ^:private shop-7 #uuid "70000000-0000-4000-8000-000000000007")
(def ^:private shop-9 #uuid "90000000-0000-4000-8000-000000000009")
(def ^:private order-42 #uuid "42000000-0000-4000-8000-000000000042")
(def ^:private order-99 #uuid "99000000-0000-4000-8000-000000000099")

(def ^:private settings {:cookie-name "app_session" :session-days 1 :visitor-session-days 1 :renew-after-minutes 60 :allowed-origin "http://app.test" :secure-cookie? false
                         :token-lead "app_" :token-shown-characters 10 :token-longest-days 30})

(def ^:private find-all-orders (cellar/make-query-from-sql-text "all orders, no WHERE" "SELECT order__id FROM orders ORDER BY order__id"))
(def ^:private insert-order (cellar/make-query-from-sql-text "insert order" "INSERT INTO orders (order__shop_id) VALUES (:order/shop-id)"))

(def ^:private handlers
  {:orders/all   {:action :orders/see :scope :every-scope-holding-the-action :params-shape [:map] :answer-shape [:vector :uuid]
                  :refusals {} :rate-limit nil :run-steps-in nil
                  :steps  [{:name  :answer-all-orders
                            :enter #(bridge/add-answer-to-context
                                     % (mapv :order/id (cellar/find-rows (:doorman/transaction %) find-all-orders {})))}]}
   :orders/sneak {:action :orders/see :scope [:shop :order/shop-id] :params-shape [:map [:order/shop-id :uuid]] :answer-shape [:map]
                  :refusals {} :rate-limit nil :run-steps-in nil
                  :steps  [{:name  :insert-order-while-only-seeing
                            :enter #(do (cellar/run-statement! (:doorman/transaction %) insert-order
                                                                 (:bridge/message-data %))
                                        (bridge/add-answer-to-context % {}))}]}
   :orders/place {:action :orders/edit :scope [:shop :order/shop-id] :params-shape [:map [:order/shop-id :uuid]] :answer-shape [:map]
                  :refusals {} :rate-limit nil :run-steps-in nil
                  :steps  [{:name  :insert-order
                            :enter #(do (cellar/run-statement! (:doorman/transaction %) insert-order
                                                            (:bridge/message-data %))
                                        (bridge/add-answer-to-context % {}))}]}})

(defn- run-sql! [pool text] (cellar/run-statement! pool (cellar/make-query-from-sql-text "test" text) {}))

(defn- make-database! [owner-pool]
  (run-sql! owner-pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS doorman CASCADE; DROP SCHEMA IF EXISTS cellar CASCADE")
  (run-sql! owner-pool "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'doorman_app') THEN CREATE ROLE doorman_app LOGIN; END IF; END $$")
  (let [tables (into (doorman/put-gates-on-tables [shops orders] scope-kinds) (conj doorman/access-tables doorman/sessions-table doorman/tokens-table))]
    ;; doorman's made functions declared with its files: cellar makes them before the gates that call them
    (with-out-str @(:slow-part (cellar/migrate! owner-pool (test-settings/declare-tables-and-access tables [shops orders] scope-kinds) [] test-settings/migrate-options)))
    (cellar/grant-tables-to-role! owner-pool tables "doorman_app"))
  (run-sql! owner-pool (str "INSERT INTO shops VALUES ('" shop-7 "'), ('" shop-9 "')"))
  (run-sql! owner-pool (str "INSERT INTO orders VALUES ('" order-42 "', '" shop-7 "'), ('" order-99 "', '" shop-9 "')"))
  (cellar/run-in-transaction! owner-pool {:read-only? false}
    (fn [transaction]
      (doorman/add-scope! transaction scope-kinds :shop shop-7 paul nil)
      (doorman/add-scope! transaction scope-kinds :shop shop-9 bob nil)
      (add-member! transaction scope-kinds [:shop shop-7] anna #{"Staff"}))))

(defn- start-pool! [user]
  (test-settings/start-pool! user 4))

(defn- ask [port cookie message-text]
  (.body (.send (HttpClient/newHttpClient)
                (cond-> (HttpRequest/newBuilder (URI. (str "http://localhost:" port "/mail/http")))
                  true   (.header "origin" "http://app.test")
                  cookie (.header "cookie" cookie)
                  true   (-> (.POST (HttpRequest$BodyPublishers/ofString message-text)) (.build)))
                (HttpResponse$BodyHandlers/ofString))))

(def ^:private server-settings
  "bridge's settings: three addresses, no proxy in front."
  {:ip "127.0.0.1" :port 0 :proxy-protocol :disable :client-address-header nil :server-header nil
   :max-body-bytes 8388608 :max-request-head-bytes 8192 :max-ws-message-bytes 4194304
   :idle-timeout-ms 60000 :threads {:min 8 :max 50 :idle-timeout-ms 60000}
   :addresses {:http "/mail/http" :sse "/mail/sse" :ws "/mail/ws"}
   :ping-every-ms 25000 :sse-reconnect-after-ms 3000 :ws-reopen-after-ms 1000
   :stop-wait-ms 10000 :stop-wait-after-cut-ms 1000
   :rate-limit-buckets-before-cleanup 10000
   :metric-buckets {:answer-seconds [0.01 0.1 1]}})

(def ^:private server-options
  "bridge's options: askers by address, no hooks; a new meter is added
   where the routes are made."
  {:wire                wire
   :log                 log
   :find-rate-limit-key #(bridge/find-client-address % nil)
   :on-sse-open nil :on-sse-close nil :on-ws-open nil :on-ws-close nil})

(deftest a-table-that-names-a-scope-needs-access
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"names a scope but has no :access"
                        (doorman/put-gates-on-tables [shops (dissoc orders :access)] scope-kinds))))

(deftest the-gates-end-to-end
  (if-not port
    (println "doorman.gates-test: skipped, DOORMAN_TEST_PORT is not set")
    (let [owner-pool (start-pool! (System/getenv "DOORMAN_TEST_USER"))]
      (make-database! owner-pool)
      (let [app-pool (start-pool! "doorman_app")
            ready    (doorman/put-access-into-handlers handlers {:pool app-pool :tables [shops orders] :scope-kinds scope-kinds
                                                                :find-session-step (doorman/make-find-session-step app-pool settings)})
            server   (bridge/start-server! (get-in (first (bridge/make-routes-for-messages ready server-settings (assoc server-options :meter (test-settings/make-meter)))) [1 :post :handler])
                                              server-settings {:log log})
            cookie   #(first (str/split (:set-cookie (doorman/sign-in! app-pool {:headers {}} % {:settings settings :tables [] :session-flags #{}})) #";"))]
        (try
          (testing "the app's own role, which owns nothing, signs in and out, and the functions answer it"
            (let [request {:request-method :post :headers {"origin" "http://app.test" "cookie" (cookie anna)}}
                  step    (doorman/make-find-session-step app-pool settings)]
              (is (= anna (:doorman/person-id (bridge/run-steps-until-answered [step] {:bridge/request request :bridge/log log}))))
              (doorman/sign-out! app-pool request settings)
              (is (nil? (:doorman/person-id (bridge/run-steps-until-answered [step] {:bridge/request request :bridge/log log}))))
              (is (= {:asking/nobody true :asking/no-scopes true}
                     (cellar/find-one-row app-pool (cellar/make-query-from-sql-text
                                                    "the functions, as the app's role"
                                                    (str "SELECT doorman.find_asking_person() IS NULL AS asking__nobody, "
                                                         "cardinality(doorman.find_scopes_holding('shop', 'orders/see')) = 0 AS asking__no_scopes"))
                                          {}))
                  "outside a message, nobody is asking")
              (cookie bob)
              (cookie bob)
              (is (= {:sessions 2 :tokens 0} (doorman/sign-out-everywhere! app-pool bob)))))
          (testing "a query with no WHERE: Postgres returns only the asker's rows"
            (is (= (str "{\"orders/all\":[\"" order-42 "\"]}") (ask (:port server) (cookie anna) "{\"orders/all\":{}}")))
            (is (= (str "{\"orders/all\":[\"" order-99 "\"]}") (ask (:port server) (cookie bob) "{\"orders/all\":{}}")))
            (is (= (refusal-json "orders/all" :doorman/signed-out) (read-without-request-ids (ask (:port server) nil "{\"orders/all\":{}}")))
                "nobody signed in can hold orders/see: the list doesn't run at all"))
          (testing "a new row in another shop is refused by the check: the handler acts in the shop it names"
            (is (= (refusal-json "orders/place" :doorman/not-found)
                   (read-without-request-ids (ask (:port server) (cookie anna) (str "{\"orders/place\":{\"order/shop-id\":\"" shop-9 "\"}}")))))
            (is (= "{\"orders/place\":{}}"
                   (ask (:port server) (cookie anna) (str "{\"orders/place\":{\"order/shop-id\":\"" shop-7 "\"}}")))
                "in her own shop: fine"))
          (testing "an action that only reads runs read-only: a write in its steps is refused"
            (is (= (refusal-json "orders/sneak" :bridge/failed)
                   (read-without-request-ids (ask (:port server) (cookie anna) (str "{\"orders/sneak\":{\"order/shop-id\":\"" shop-7 "\"}}"))))
                "Anna may edit shop 7's orders, but orders/see only reads"))
          (testing "run-as-asker-of-request!: a transaction as the request's asker, outside a message, held by the same gates"
            (let [as (fn [request read-only? f]
                       (doorman/run-as-asker-of-request! app-pool request {:settings settings :read-only? read-only? :scope-kinds scope-kinds} f))
                  anna-request {:request-method :get :headers {"cookie" (cookie anna)}}]
              (let [seen (set (as anna-request true #(mapv :order/id (cellar/find-rows % find-all-orders {}))))]
                (is (contains? seen order-42) "her shop's order")
                (is (not (contains? seen order-99)) "not shop 9's"))
              (is (= [] (as {:request-method :get :headers {}} true #(mapv :order/id (cellar/find-rows % find-all-orders {}))))
                  "nobody: no rows")
              (is (thrown? Exception (as anna-request false #(cellar/run-statement! % insert-order {:order/shop-id shop-9})))
                  "a row in another shop is refused")
              (is (thrown? Exception (as anna-request true #(cellar/run-statement! % insert-order {:order/shop-id shop-7})))
                  "read-only: no write at all")
              (is (= :doorman/refused-request
                     (:type (ex-data (try (as (assoc anna-request :request-method :post) true identity)
                                          (catch clojure.lang.ExceptionInfo e e)))))
                  "a request that changes something without the app's Origin is refused, as by the step")
              (is (= :doorman/bad-options
                     (:type (ex-data (try (as anna-request nil identity) (catch clojure.lang.ExceptionInfo e e))))))))
          (testing "the leak test: Anna, asked for shop 9's order, gets nothing of it"
            (let [anna-request {:request-method :post
                                :headers {"origin" "http://app.test" "cookie" (cookie anna)}}]
              (is (= [] (doorman/find-leaks (select-keys ready [:orders/all]) anna-request {:orders [order-99]} {:wire wire :log log :meter (test-settings/make-meter) :metric-buckets test-settings/metric-buckets :scope-kinds scope-kinds :seed 7 :size 30})))
              (testing "and a handler that reads past the gates is caught"
                (let [leaky {:orders/leaky {:action :orders/see :scope :every-scope-holding-the-action :params-shape [:map] :answer-shape [:vector :uuid]
                                            :refusals {} :rate-limit nil :run-steps-in nil
                                            :steps [{:name  :read-with-the-owner
                                                     :enter #(bridge/add-answer-to-context
                                                              % (mapv :order/id (cellar/find-rows owner-pool find-all-orders {})))}]}}
                      ready-leaky (doorman/put-access-into-handlers leaky {:pool app-pool :tables [shops orders] :scope-kinds scope-kinds
                                                                          :find-session-step (doorman/make-find-session-step app-pool settings)})]
                  (is (= [{:handler :orders/leaky :gave-back [(str order-99)]}]
                         (doorman/find-leaks ready-leaky anna-request {:orders [order-99]} {:wire wire :log log :meter (test-settings/make-meter) :metric-buckets test-settings/metric-buckets :scope-kinds scope-kinds :seed 7 :size 30})))))))
          (finally
            (bridge/stop-server! server)
            (cellar/stop-pool! app-pool)
            (cellar/stop-pool! owner-pool)))))))
