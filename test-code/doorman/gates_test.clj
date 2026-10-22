(ns doorman.gates-test
  "The gates, end to end: the app's own role owns nothing, so Postgres's
   row rules hold for it. A query with no WHERE still returns only the
   asking person's rows, and a new row in another shop is refused. Runs
   when DOORMAN_TEST_PORT is set."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [doorman.api :as doorman]
   [cellar.api :as cellar]
   [mailroom.api :as mailroom])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

(def ^:private port (some-> (System/getenv "DOORMAN_TEST_PORT") parse-long))

(def ^:private scope-kinds
  (doorman/check-scope-kinds
   {:shop {:table   :shops
           :actions {:orders/see #{:read} :orders/edit #{:read :insert :update}}
           :roles   {"Staff" #{:orders/see :orders/edit}}}}))

(def ^:private shops {:table :shops :columns [[:shop/id :uuid {:primary-key? true}]] :access :public})

(def ^:private orders
  {:table   :orders
   :columns [[:order/id :uuid {:primary-key? true :default :random-uuid}]
             [:order/shop-id :uuid {:references :shop/id}]]
   :access  [{:allow #{:orders/see :orders/edit} :to [:member :order/shop-id]}]})

(def ^:private paul #uuid "00000000-0000-4000-8000-00000000000a")
(def ^:private anna #uuid "00000000-0000-4000-8000-00000000000b")
(def ^:private bob  #uuid "00000000-0000-4000-8000-00000000000d")
(def ^:private shop-7 #uuid "70000000-0000-4000-8000-000000000007")
(def ^:private shop-9 #uuid "90000000-0000-4000-8000-000000000009")
(def ^:private order-42 #uuid "42000000-0000-4000-8000-000000000042")
(def ^:private order-99 #uuid "99000000-0000-4000-8000-000000000099")

(def ^:private settings {:cookie-name "app_session" :session-days 1 :renew-after-minutes 60 :allowed-origin "http://app.test" :secure-cookie? false})

(def ^:private find-all-orders (cellar/make-query-from-sql-text "all orders, no WHERE" "SELECT order__id FROM orders ORDER BY order__id"))
(def ^:private insert-order (cellar/make-query-from-sql-text "insert order" "INSERT INTO orders (order__shop_id) VALUES (:order/shop-id)"))

(def ^:private handlers
  {:orders/all   {:action :orders/see :params-shape [:map] :answer-shape [:vector :uuid]
                  :steps  [{:name  :answer-all-orders
                            :enter #(mailroom/add-answer-to-context
                                     % (mapv :order/id (cellar/find-rows (:doorman/transaction %) find-all-orders {})))}]}
   :orders/sneak {:action :orders/see :params-shape [:map [:order/shop-id :uuid]] :answer-shape [:map]
                  :steps  [{:name  :insert-order-while-only-seeing
                            :enter #(do (cellar/run-statement! (:doorman/transaction %) insert-order
                                                                 (:mailroom/message-data %))
                                        (mailroom/add-answer-to-context % {}))}]}
   :orders/place {:action :orders/edit :params-shape [:map [:order/shop-id :uuid]] :answer-shape [:map]
                  :steps  [{:name  :insert-order
                            :enter #(do (cellar/run-statement! (:doorman/transaction %) insert-order
                                                            (:mailroom/message-data %))
                                        (mailroom/add-answer-to-context % {}))}]}})

(defn- run-sql! [pool text] (cellar/run-statement! pool (cellar/make-query-from-sql-text "test" text) {}))

(defn- make-database! [owner-pool]
  (run-sql! owner-pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS doorman CASCADE; DROP SCHEMA IF EXISTS cellar CASCADE")
  (run-sql! owner-pool "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'doorman_app') THEN CREATE ROLE doorman_app LOGIN; END IF; END $$")
  (let [tables (into (doorman/put-gates-on-tables [shops orders] scope-kinds) (conj doorman/access-tables doorman/sessions-table))]
    (doorman/install-access-functions! owner-pool)        ; before the gates: their policies call these
    (with-out-str @(:slow-part (cellar/migrate! owner-pool tables [])))
    (cellar/grant-tables-to-role! owner-pool tables "doorman_app"))
  (run-sql! owner-pool (str "INSERT INTO shops VALUES ('" shop-7 "'), ('" shop-9 "')"))
  (run-sql! owner-pool (str "INSERT INTO orders VALUES ('" order-42 "', '" shop-7 "'), ('" order-99 "', '" shop-9 "')"))
  (cellar/run-in-transaction! owner-pool
    (fn [transaction]
      (doorman/add-scope! transaction scope-kinds :shop shop-7 paul)
      (doorman/add-scope! transaction scope-kinds :shop shop-9 bob)
      (doorman/add-membership! transaction [:shop shop-7] anna "Staff"))))

(defn- start-pool! [user]
  (cellar/start-pool! {:host "localhost" :port port :database "postgres" :user user :password nil
                    :tls :off :pool-size 4 :statement-timeout-ms 5000 :time-zone "UTC"}))

(defn- ask [port cookie message-text]
  (.body (.send (HttpClient/newHttpClient)
                (cond-> (HttpRequest/newBuilder (URI. (str "http://localhost:" port "/mail/http")))
                  true   (.header "origin" "http://app.test")
                  cookie (.header "cookie" cookie)
                  true   (-> (.POST (HttpRequest$BodyPublishers/ofString message-text)) (.build)))
                (HttpResponse$BodyHandlers/ofString))))

(def ^:private server-settings
  "mailroom's settings: three addresses, no proxy in front."
  {:ip "127.0.0.1" :port 0 :client-address-header nil
   :addresses {:http "/mail/http" :sse "/mail/sse" :ws "/mail/ws"}
   :ping-every-ms 25000 :stop-wait-ms 10000 :ws-reopen-after-ms 1000
   :rate-limit-buckets-before-cleanup 10000})

(def ^:private server-options
  "mailroom's options: askers by address, no hooks."
  {:find-rate-limit-key #(mailroom/find-client-address % nil)
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
            server   (mailroom/start-server! (get-in (first (mailroom/make-routes-for-messages ready server-settings server-options)) [1 :post :handler])
                                              server-settings)
            cookie   #(first (str/split (:set-cookie (doorman/sign-in! app-pool % settings)) #";"))]
        (try
          (testing "the app's own role, which owns nothing, signs in and out, and the functions answer it"
            (let [request {:request-method :post :headers {"origin" "http://app.test" "cookie" (cookie anna)}}
                  step    (doorman/make-find-session-step app-pool settings)]
              (is (= anna (:doorman/person-id (mailroom/run-steps-until-answered [step] {:mailroom/request request}))))
              (doorman/sign-out! app-pool request settings)
              (is (nil? (:doorman/person-id (mailroom/run-steps-until-answered [step] {:mailroom/request request}))))
              (is (= {:asking/nobody true :asking/no-scopes true}
                     (cellar/find-one-row app-pool (cellar/make-query-from-sql-text
                                                    "the functions, as the app's role"
                                                    (str "SELECT doorman.find_asking_person() IS NULL AS asking__nobody, "
                                                         "cardinality(doorman.find_scopes_holding('shop', 'orders/see')) = 0 AS asking__no_scopes"))
                                          {}))
                  "outside a message, nobody is asking")
              (cookie bob)
              (cookie bob)
              (is (= 2 (doorman/sign-out-everywhere! app-pool bob)))))
          (testing "a query with no WHERE: Postgres returns only the asker's rows"
            (is (= (str "{\"orders/all\":[\"" order-42 "\"]}") (ask (:port server) (cookie anna) "{\"orders/all\":{}}")))
            (is (= (str "{\"orders/all\":[\"" order-99 "\"]}") (ask (:port server) (cookie bob) "{\"orders/all\":{}}")))
            (is (= "{\"orders/all\":{\"error\":\"Not found.\"}}" (ask (:port server) nil "{\"orders/all\":{}}"))
                "nobody can hold orders/see: the list doesn't run at all"))
          (testing "a new row in another shop is refused by the gate"
            (is (= "{\"orders/place\":{\"error\":\"Something went wrong.\"}}"
                   (ask (:port server) (cookie anna) (str "{\"orders/place\":{\"order/shop-id\":\"" shop-9 "\"}}"))))
            (is (= "{\"orders/place\":{}}"
                   (ask (:port server) (cookie anna) (str "{\"orders/place\":{\"order/shop-id\":\"" shop-7 "\"}}")))
                "in her own shop: fine"))
          (testing "an action that only reads runs read-only: a write in its steps is refused"
            (is (= "{\"orders/sneak\":{\"error\":\"Something went wrong.\"}}"
                   (ask (:port server) (cookie anna) (str "{\"orders/sneak\":{\"order/shop-id\":\"" shop-7 "\"}}")))
                "Anna may edit shop 7's orders, but orders/see only reads"))
          (testing "the leak test: Anna, asked for shop 9's order, gets nothing of it"
            (let [anna-request {:request-method :post
                                :headers {"origin" "http://app.test" "cookie" (cookie anna)}}]
              (is (= [] (doorman/find-leaks (select-keys ready [:orders/all]) anna-request {:orders [order-99]} {:seed 7 :size 30})))
              (testing "and a handler that reads past the gates is caught"
                (let [leaky {:orders/leaky {:action :orders/see :params-shape [:map] :answer-shape [:vector :uuid]
                                            :steps [{:name  :read-with-the-owner
                                                     :enter #(mailroom/add-answer-to-context
                                                              % (mapv :order/id (cellar/find-rows owner-pool find-all-orders {})))}]}}
                      ready-leaky (doorman/put-access-into-handlers leaky {:pool app-pool :tables [shops orders] :scope-kinds scope-kinds
                                                                          :find-session-step (doorman/make-find-session-step app-pool settings)})]
                  (is (= [{:handler :orders/leaky :gave-back [(str order-99)]}]
                         (doorman/find-leaks ready-leaky anna-request {:orders [order-99]} {:seed 7 :size 30})))))))
          (finally
            (mailroom/stop-server! server)
            (cellar/stop-pool! app-pool)
            (cellar/stop-pool! owner-pool)))))))
