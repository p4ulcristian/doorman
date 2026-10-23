(ns doorman.tokens-test
  "A person's tokens: made by a signed-in session, shown once, kept as a
   SHA-256; a call with Authorization: Bearer is that person's, held by the
   same check and gates; listed, revoked, ended, and ended with the person.
   Anna is Staff in shop A, Bob in shop B. Runs against Postgres when
   DOORMAN_TEST_PORT is set; the rest doesn't need it."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [doorman.test-answers :refer [refusal refusal-json read-without-request-ids without-request-id]]
   [cellar.api :as cellar]
   [doorman.api :as doorman]
   [doorman.test-roles :refer [add-member!]]
   [doorman.cookie :as cookie]
   [doorman.test-log :as test-log :refer [log]]
   [doorman.test-settings :as test-settings]
   [doorman.test-wire :refer [wire]]
   [bridge.api :as bridge])
  (:import
   (java.time Duration Instant)))

(def ^:private port test-settings/port)

(def ^:private settings {:cookie-name "app_session" :session-days 1 :visitor-session-days 1 :renew-after-minutes 60
                         :allowed-origin "http://app.test" :secure-cookie? false
                         :token-lead "shop_" :token-shown-characters 11 :token-longest-days 90})

(def ^:private scope-kinds
  (doorman/check-scope-kinds
   {:shop {:table :shops :parent nil :single? false
           :denies  [{:deny :writes :session :impersonated}]
           :actions {:orders/see #{:read} :orders/edit #{:read :insert :update}}
           :roles   {"Staff" #{:orders/see :orders/edit}}}}))

(def ^:private shops
  {:table :shops :columns [[:shop/id :uuid {:primary-key? true}]]
   :indexes [] :uniques [] :checks [] :references [] :triggers [] :on-change nil :access :public})

(def ^:private orders
  {:table :orders
   :columns [[:order/id :uuid {:primary-key? true :default :random-uuid}]
             [:order/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]]
   :indexes [{:columns [[:order/shop-id :ascending :nulls-last]] :method :btree :ops {} :unique? false :where nil}] :uniques [] :checks [] :references [] :triggers [] :on-change nil
   :access [{:allow #{:orders/see :orders/edit} :only :all-of-the-action :to [:member :order/shop-id]}]})

(def ^:private tables [shops orders])

(defn- id [n] (parse-uuid (format "00000000-0000-4000-8000-%012d" n)))

(def ^:private shop-a (id 1001))
(def ^:private shop-b (id 2002))
(def ^:private paul (id 1))   ; Owner of both shops
(def ^:private anna (id 2))   ; Staff in A
(def ^:private bob  (id 3))   ; Staff in B

(defn- query [text] (cellar/make-query-from-sql-text "a test statement" text))

(defn- answer-with [f] {:name :answer :enter #(bridge/add-answer-to-context % (f (:doorman/transaction %) (:bridge/message-data %)))})

(def ^:private insert-order (query "INSERT INTO orders (order__shop_id) VALUES (:order/shop-id)"))

(def ^:private handlers
  {:orders/place    {:action :orders/edit :scope [:shop :order/shop-id]
                     :params-shape [:map [:order/shop-id :uuid]] :answer-shape [:map]
                     :refusals {} :rate-limit nil :run-steps-in nil
                     :steps [(answer-with (fn [t data] (cellar/run-statement! t insert-order data) {}))]}
   ;; names shop A, but writes the order into the shop of another param: a bug, or a crafted param
   :orders/misplace {:action :orders/edit :scope [:shop :shop/id]
                     :params-shape [:map [:shop/id :uuid] [:order/shop-id :uuid]] :answer-shape [:map]
                     :refusals {} :rate-limit nil :run-steps-in nil
                     :steps [(answer-with (fn [t data] (cellar/run-statement! t insert-order (select-keys data [:order/shop-id])) {}))]}
   :orders/all      {:action :orders/see :scope :every-scope-holding-the-action
                     :params-shape [:map] :answer-shape [:vector :uuid]
                     :refusals {} :rate-limit nil :run-steps-in nil
                     :steps [(answer-with (fn [t _] (mapv :order/shop-id (cellar/find-rows t (query "SELECT order__shop_id FROM orders") {}))))]}})

;; -----------------------------------------------------------------------------
;; Without Postgres

(deftest the-token-settings-are-required-and-checked
  (let [errors #(try (doorman/make-find-session-step nil %) nil (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= #{:token-lead :token-shown-characters :token-longest-days}
           (set (keys (:errors (errors (dissoc settings :token-lead :token-shown-characters :token-longest-days))))))
        "each missing one is named")
    (is (= #{:token-shown-characters} (set (keys (:errors (errors (assoc settings :token-shown-characters 27))))))
        "the list shows at most the lead and half of the 43 random characters: 5 + 21")
    (is (nil? (errors (assoc settings :token-shown-characters 26))))))

(deftest a-token-s-key-for-rate-limits-is-its-hash
  (is (= (cookie/turn-cookie-value-into-fingerprint "shop_abc")
         (doorman/find-session-key-of-request {:headers {"authorization" "Bearer shop_abc"}} settings)))
  (is (= (cookie/turn-cookie-value-into-fingerprint "xyz")
         (doorman/find-session-key-of-request {:headers {"cookie" "app_session=xyz"}} settings)))
  (is (nil? (doorman/find-session-key-of-request {:headers {"authorization" "Basic dXNlcjpwYXNz"}} settings))
      "another kind of Authorization is no token"))

;; -----------------------------------------------------------------------------
;; Against Postgres

(defn- run-sql! [pool text] (cellar/run-statement! pool (query text) {}))

(defn- make-database! [owner-pool]
  (run-sql! owner-pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS doorman CASCADE; DROP SCHEMA IF EXISTS cellar CASCADE")
  (run-sql! owner-pool "DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'doorman_app') THEN CREATE ROLE doorman_app LOGIN; END IF; END $$")
  (let [ready (into (doorman/put-gates-on-tables tables scope-kinds) (conj doorman/access-tables doorman/sessions-table doorman/tokens-table))]
    (with-out-str @(:slow-part (cellar/migrate! owner-pool (test-settings/declare-tables-and-access ready tables scope-kinds) [] test-settings/migrate-options)))
    (cellar/grant-tables-to-role! owner-pool ready "doorman_app"))
  (run-sql! owner-pool (str "INSERT INTO shops VALUES ('" shop-a "'), ('" shop-b "')"))
  (cellar/run-in-transaction! owner-pool {:read-only? false}
                              (fn [transaction]
                                (doorman/add-scope! transaction scope-kinds :shop shop-a paul nil)
                                (doorman/add-scope! transaction scope-kinds :shop shop-b paul nil)
                                (add-member! transaction scope-kinds [:shop shop-a] anna #{"Staff"})
                                (add-member! transaction scope-kinds [:shop shop-b] bob #{"Staff"}))))

(defn- count-rows [pool text] (count (cellar/find-rows pool (query text) {})))

(defn- in-days [days] (.plus (Instant/now) (Duration/ofDays days)))

(defn- refusal-of [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (select-keys (ex-data e) [:type :reason]))))

(deftest tokens-against-postgres
  (if-not port
    (println "doorman.tokens-test: skipped, DOORMAN_TEST_PORT is not set")
    (let [owner-pool (test-settings/start-pool! (System/getenv "DOORMAN_TEST_USER") 2)]
      (make-database! owner-pool)
      (let [app-pool   (test-settings/start-pool! "doorman_app" 4)
            step       (doorman/make-find-session-step app-pool settings)
            ready      (doorman/put-access-into-handlers handlers {:pool app-pool :tables tables :scope-kinds scope-kinds
                                                                   :find-session-step step})
            cookie-of  (fn [person flags]
                         (first (str/split (:set-cookie (doorman/sign-in! app-pool {:headers {}} person
                                                                          {:settings settings :tables [] :session-flags flags}))
                                           #";")))
            browser    (fn [person] {:request-method :post :headers {"origin" "http://app.test" "cookie" (cookie-of person #{})}})
            bearer     (fn [text] {:request-method :post :headers {"authorization" (str "Bearer " text)}})
            make!      (fn [request name days] (doorman/make-token! app-pool request {:settings settings :name name :ends-at (in-days days)}))
            ask        (fn [request handler-name params]
                         (without-request-id (get (bridge/answer-message ready :no-rate-limits request {handler-name params} {:wire wire :log log :meter (test-settings/make-meter) :metric-buckets test-settings/metric-buckets})
                                                  handler-name)))
            asker-of   (fn [request]
                         (let [context (bridge/run-steps-until-answered [step] {:bridge/request request :bridge/log log})]
                           (or (:bridge/answer context)
                               ((juxt :doorman/person-id :doorman/visitor-id :doorman/session-flags) context))))
            orders-in  #(count-rows owner-pool (str "SELECT 1 AS row__found FROM orders WHERE order__shop_id = '" % "'"))
            anna-token (make! (browser anna) "catalog script" 30)]
        (try
          (testing "made by a signed-in session: the text shown once, only its SHA-256 kept"
            (let [{:keys [token-id text shown-start]} anna-token]
              (is (uuid? token-id))
              (is (re-matches #"shop_[A-Za-z0-9_-]{43}" text) "the lead and 43 random characters")
              (is (= (subs text 0 11) shown-start) ":token-shown-characters, 11")
              (is (= [{:token/fingerprint (cookie/turn-cookie-value-into-fingerprint text) :token/shown-start shown-start}]
                     (cellar/find-rows owner-pool (query "SELECT token__fingerprint, token__shown_start FROM doorman.tokens") {})))
              (is (zero? (count-rows owner-pool (str "SELECT 1 AS row__found FROM doorman.tokens WHERE token__fingerprint = '" text "' OR token__shown_start = '" text "'"))))))

          (testing "a token's call is its person's: no Origin needed, no session flags, no visitor"
            (is (= [anna nil #{}] (asker-of (bearer (:text anna-token)))))
            (is (= [anna nil #{}] (asker-of (assoc-in (bearer (:text anna-token)) [:headers "origin"] "https://evil.test")))
                "a browser never sends the header on its own, so another site's Origin doesn't matter")
            (reset! test-log/lines [])
            (let [context (bridge/run-steps-until-answered [step] {:bridge/request (bearer (:text anna-token)) :bridge/log log})]
              ((:bridge/log context) :info :test/later-step {}))
            (is (= [{"person-id" (str anna) "token-id" (str (:token-id anna-token))}] (mapv #(get % "context") @test-log/lines))
                "the lines say who asked, and by which token"))

          (testing "a wrong token is nobody; a request with a cookie and a token is refused"
            (is (= [nil nil #{}] (asker-of (bearer (str (:text anna-token) "x")))))
            (is (= [nil nil #{}] (asker-of (bearer "shop_nothing"))))
            (is (= (refusal :doorman/not-allowed)
                   (asker-of (assoc-in (browser bob) [:headers "authorization"] (str "Bearer " (:text anna-token))))))
            (is (= (refusal :doorman/not-allowed)
                   (ask (assoc-in (browser bob) [:headers "authorization"] (str "Bearer " (:text anna-token))) :orders/place {:order/shop-id shop-a}))
                "nothing runs"))

          (testing "held by the same check and gates as her browser's calls"
            (is (= {} (ask (bearer (:text anna-token)) :orders/place {:order/shop-id shop-a})) "in A, where she is Staff")
            (is (= (refusal :doorman/not-found) (ask (bearer (:text anna-token)) :orders/place {:order/shop-id shop-b})) "in B: the check")
            (is (= (refusal :bridge/failed) (ask (bearer (:text anna-token)) :orders/misplace {:shop/id shop-a :order/shop-id shop-b}))
                "a handler acting in A that writes into B: the gate")
            (is (= [1 0] [(orders-in shop-a) (orders-in shop-b)]))
            (run-sql! owner-pool (str "INSERT INTO orders (order__shop_id) VALUES ('" shop-b "')"))
            (is (= [(str shop-a)] (ask (bearer (:text anna-token)) :orders/all {})) "she reads A's orders, not B's")
            (is (= [(str shop-b)] (ask (bearer (:text (make! (browser bob) "bob's" 1))) :orders/all {})) "Bob's token, B's"))

          (testing "who makes a token: a signed-in session, without flags"
            (is (= {:type :doorman/no-token-for-asker :reason :nobody} (refusal-of #(make! {:request-method :post :headers {"origin" "http://app.test"}} "x" 1))))
            (is (= {:type :doorman/no-token-for-asker :reason :token} (refusal-of #(make! (bearer (:text anna-token)) "x" 1)))
                "a token makes no token")
            (is (= {:type :doorman/no-token-for-asker :reason :session-flags}
                   (refusal-of #(make! {:request-method :post :headers {"origin" "http://app.test" "cookie" (cookie-of anna #{:impersonated})}} "x" 1)))
                "an impersonating admin's token would shed the flag's deny")
            (is (= {:type :doorman/refused-request :reason :doorman/not-allowed} (refusal-of #(make! (assoc-in (browser anna) [:headers "origin"] "https://evil.test") "x" 1)))
                "another site's request")
            (is (= {:type :doorman/bad-token-end} (refusal-of #(make! (browser anna) "x" 91))) "past :token-longest-days, 90")
            (is (= {:type :doorman/bad-token-end} (refusal-of #(make! (browser anna) "x" -1))) "in the past")
            (is (= {:type :doorman/bad-options}
                   (refusal-of #(doorman/make-token! app-pool (browser anna) {:settings settings :name "" :ends-at (in-days 1)})))
                "a name is required"))

          (testing "a person lists their own tokens, newest first, never the text"
            (let [second-token (make! (browser anna) "AI helper" 5)
                  listed       (doorman/list-tokens app-pool (browser anna) settings)]
              (is (= ["AI helper" "catalog script"] (mapv :name listed)))
              (is (= #{:token-id :name :shown-start :created-at :ends-at} (set (keys (first listed)))))
              (is (= listed (doorman/list-tokens app-pool (bearer (:text second-token)) settings)) "by a token too")
              (is (= ["bob's"] (mapv :name (doorman/list-tokens app-pool (browser bob) settings))) "Bob sees only his")
              (is (= [] (doorman/list-tokens app-pool {:request-method :get :headers {}} settings)) "nobody: none")

              (testing "and revokes one: it is nobody at once; another's is never touched"
                (is (false? (doorman/revoke-token! app-pool (browser bob) settings (:token-id second-token))) "Bob can't end Anna's")
                (is (= [anna nil #{}] (asker-of (bearer (:text second-token)))))
                (is (true? (doorman/revoke-token! app-pool (browser anna) settings (:token-id second-token))))
                (is (= [nil nil #{}] (asker-of (bearer (:text second-token)))))
                (is (= ["catalog script"] (mapv :name (doorman/list-tokens app-pool (browser anna) settings)))))))

          (testing "an ended token is nobody, left out of the list, and forgotten by the nightly job"
            (let [ending (make! (browser anna) "short" 1)]
              (is (= [anna nil #{}] (asker-of (bearer (:text ending)))))
              (run-sql! owner-pool (str "UPDATE doorman.tokens SET token__ends_at = now() - interval '1 minute' WHERE token__id = '" (:token-id ending) "'"))
              (is (= [nil nil #{}] (asker-of (bearer (:text ending)))))
              (is (= ["catalog script"] (mapv :name (doorman/list-tokens app-pool (browser anna) settings))))
              (is (= 1 (doorman/delete-ended-tokens! app-pool)))))

          (testing "a removed member's token reaches the scope no more; signing out everywhere ends it"
            (cellar/run-in-transaction! owner-pool {:read-only? false}
                                        #(doorman/remove-membership! % [:shop shop-a] anna))
            (is (= [anna nil #{}] (asker-of (bearer (:text anna-token)))) "still her token")
            (is (= (refusal :doorman/not-found) (ask (bearer (:text anna-token)) :orders/place {:order/shop-id shop-a}))
                "but A is not hers: memberships are read on every call")
            (let [ended (doorman/sign-out-everywhere! app-pool anna)]
              (is (= 1 (:tokens ended)) "her one token left ended")
              (is (pos? (:sessions ended)) "with her sessions"))
            (is (= [nil nil #{}] (asker-of (bearer (:text anna-token))))))
          (finally
            (cellar/stop-pool! app-pool)
            (cellar/stop-pool! owner-pool)))))))
