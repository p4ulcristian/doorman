(ns groundwork-access.check-test
  "The check, end to end: a session cookie, a message over http, a record
   in a shop. Runs when ACCESS_TEST_PORT is set."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [groundwork-access.test-answers :refer [refusal refusal-json read-without-request-ids without-request-id]]
   [groundwork-access.api :as access]
   [groundwork-access.test-roles :refer [add-member!]]
   [groundwork-access.check :as check]
   [groundwork-access.shapes :as shapes]
   [groundwork-access.test-settings :as test-settings]
   [groundwork-postgres.api :as postgres]
   [groundwork-access.test-log :as test-log :refer [log]]
   [groundwork-access.test-shaper :refer [shaper]]
   [groundwork-bridge.api :as bridge])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

(def ^:private port (some-> (System/getenv "ACCESS_TEST_PORT") parse-long))

(def ^:private scope-kinds
  (access/check-scope-kinds
   {:shop {:table :shops :parent nil :single? false :denies []
           :actions {:orders/see #{:read}} :roles {"Staff" #{:orders/see}}}}))

(def ^:private shops
  {:table     :shops
   :columns   [[:shop/id :uuid {:primary-key? true}]]
   :indexes   []
   :uniques   []
   :checks    []
   :references []
   :triggers   []
   :gates     nil
   :on-change nil})

(def ^:private orders
  {:table     :orders
   :columns   [[:order/id :uuid {:primary-key? true}]
               [:order/shop-id :uuid {:references {:to :shop/id :on-delete :refuse :on-update :refuse :checked-at :statement}}]]
   :indexes   []
   :uniques   []
   :checks    []
   :references []
   :triggers   []
   :gates     nil
   :on-change nil
   :access    [{:allow :orders/see :only :all-of-the-action :to [:member :order/shop-id]}]})

(def ^:private paul #uuid "00000000-0000-4000-8000-00000000000a")
(def ^:private anna #uuid "00000000-0000-4000-8000-00000000000b")
(def ^:private bob  #uuid "00000000-0000-4000-8000-00000000000d")
(def ^:private shop-7 #uuid "70000000-0000-4000-8000-000000000007")
(def ^:private shop-9 #uuid "90000000-0000-4000-8000-000000000009")
(def ^:private order-42 #uuid "42000000-0000-4000-8000-000000000042")
(def ^:private order-99 #uuid "99000000-0000-4000-8000-000000000099")

(def ^:private settings {:cookie-name "app_session" :session-days 1 :visitor-session-days 1 :renew-after-minutes 60 :allowed-origin "http://app.test" :secure-cookie? false
                         :token-lead "app_" :token-shown-characters 10 :token-longest-days 30})

(def ^:private answer-the-order
  {:name  :answer-the-order
   :enter (fn [{:groundwork-bridge/keys [log message-data] :as context}]
            (log :info :test/answering {:order/id (:order/id message-data)})
            (bridge/add-answer-to-context context {:order/id (:order/id message-data)}))})

(def ^:private carl #uuid "00000000-0000-4000-8000-00000000000e")

(def ^:private handlers
  {:orders/remind {:action       :orders/see                ; no :record: an email, say
                   :scope        :every-scope-holding-the-action
                   :params-shape [:map]
                   :answer-shape [:map]
                   :refusals     {}
                   :rate-limit   nil
                   :run-steps-in nil
                   :steps        [{:name :remind :enter #(bridge/add-answer-to-context % {})}]}
   :orders/clear-cache {:action       :orders/see       ; work kept for after the commit, which fails
                        :scope        :every-scope-holding-the-action
                        :params-shape [:map]
                        :answer-shape [:map]
                        :refusals     {}
                        :rate-limit   nil
                        :run-steps-in nil
                        :steps        [{:name  :clear-cache
                                        :enter (fn [context]
                                                 (postgres/run-after-commit! (:groundwork-access/transaction context)
                                                                           #(throw (ex-info "the cache is gone" {})))
                                                 (bridge/add-answer-to-context context {}))}]}
   :orders/one {:action       :orders/see
                :record       [:orders :order/id]
                :params-shape [:map [:order/id :uuid]]
                :answer-shape [:map [:order/id :uuid]]
                :refusals     {}
                :rate-limit   nil
                :run-steps-in nil
                :steps        [answer-the-order]}})

(defn- run-sql! [pool text] (postgres/run-statement! pool (postgres/make-query-from-sql-text "test" text) {}))

(defn- ask [port cookie order-id]
  (.body (.send (HttpClient/newHttpClient)
                (cond-> (HttpRequest/newBuilder (URI. (str "http://localhost:" port "/mail/http")))
                  true   (.header "origin" "http://app.test")
                  cookie (.header "cookie" cookie)
                  true   (-> (.POST (HttpRequest$BodyPublishers/ofString (str "{\"orders/one\":{\"order/id\":\"" order-id "\"}}")))
                             (.build)))
                (HttpResponse$BodyHandlers/ofString))))

(defn- make-database! [pool]
  (run-sql! pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS groundwork_access CASCADE; DROP SCHEMA IF EXISTS groundwork_postgres CASCADE")
  (with-out-str @(:slow-part (postgres/migrate! pool (test-settings/declare-tables-and-access (into [shops orders access/sessions-table access/tokens-table] access/access-tables) [shops orders] scope-kinds) [] test-settings/migrate-options)))
  (run-sql! pool (str "INSERT INTO shops VALUES ('" shop-7 "'), ('" shop-9 "')"))
  (run-sql! pool (str "INSERT INTO orders VALUES ('" order-42 "', '" shop-7 "'), ('" order-99 "', '" shop-9 "')"))
  (postgres/run-in-transaction! pool {:read-only? false :log nil}
    (fn [transaction]
      (access/add-scope! transaction scope-kinds :shop shop-7 paul nil)
      (access/add-scope! transaction scope-kinds :shop shop-9 bob nil)
      (add-member! transaction scope-kinds [:shop shop-7] anna #{"Staff"}))))

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
  {:shaper                shaper
   :log                 log
   :find-rate-limit-key #(bridge/find-client-address % nil)
   :on-sse-open nil :on-sse-close nil :on-ws-open nil :on-ws-close nil})

(def ^:private no-session-step {:name :no-session :enter identity})

(deftest a-handler-must-declare-its-action-and-shapes
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"has no :action"
                        (access/put-access-into-handlers {:x/y {:steps []}} {:pool (Object.) :tables [shops orders] :scope-kinds scope-kinds
                                                                              :find-session-step no-session-step}))))

(deftest a-handler-writes-out-bridge-s-keys
  (let [problems (try (access/put-access-into-handlers
                       {:x/y (dissoc (:orders/remind handlers) :rate-limit :run-steps-in)
                        :x/z (assoc (:orders/remind handlers) :run-steps-in (fn [run context] (run context)))}
                       {:pool (Object.) :tables [shops orders] :scope-kinds scope-kinds :find-session-step no-session-step})
                      (catch clojure.lang.ExceptionInfo e (:problems (ex-data e))))]
    (is (= [":x/y has no :rate-limit (nil written out where nothing applies, {} for no :refusals)"
            ":x/y has no :run-steps-in (nil written out where nothing applies, {} for no :refusals)"
            ":x/z has its own :run-steps-in; groundwork-access runs the steps in its transaction, so it must be nil"]
           problems)
        "every problem at once")))

(deftest access-adds-its-reasons-to-every-handler
  (let [ready (access/put-access-into-handlers
               (assoc-in handlers [:orders/one :refusals] {:order/archived nil})
               {:pool (Object.) :tables [shops orders] :scope-kinds scope-kinds :find-session-step no-session-step})]
    (is (= {:groundwork-access/signed-out nil :groundwork-access/not-found nil :groundwork-access/not-allowed nil} (:refusals (:orders/remind ready))))
    (is (= #{:order/archived :groundwork-access/signed-out :groundwork-access/not-found :groundwork-access/not-allowed} (set (keys (:refusals (:orders/one ready)))))
        "beside the handler's own"))
  (let [problems (try (access/put-access-into-handlers
                       {:x/y (dissoc (:orders/remind handlers) :refusals)
                        :x/z (assoc (:orders/remind handlers) :refusals {:groundwork-access/not-found nil})}
                       {:pool (Object.) :tables [shops orders] :scope-kinds scope-kinds :find-session-step no-session-step})
                      (catch clojure.lang.ExceptionInfo e (:problems (ex-data e))))]
    (is (= [":x/y has no :refusals (nil written out where nothing applies, {} for no :refusals)"
            ":x/z declares :groundwork-access/not-found, groundwork-access's own: groundwork-access adds it to every handler it wraps"]
           problems)
        ":refusals written out; groundwork-access's own never the app's")))

(deftest the-browser-s-shapes-get-access-s-reasons-too
  (is (= {:orders/one {:params-shape nil :answer-shape nil
                       :refusals {:order/archived nil :groundwork-access/signed-out nil :groundwork-access/not-found nil :groundwork-access/not-allowed nil}}}
         (shapes/put-refusals-into-shapes {:orders/one {:params-shape nil :answer-shape nil :refusals {:order/archived nil}}}))
      "groundwork-access.api's .cljs half hands this on")
  (is (= :groundwork-access/bad-shapes
         (:type (ex-data (try (shapes/put-refusals-into-shapes {:orders/one {:refusals {:groundwork-access/signed-out nil}}})
                              (catch clojure.lang.ExceptionInfo e e)))))))

(deftest signed-out-says-nothing-of-the-record
  (is (= [:groundwork-access/signed-out :groundwork-access/signed-out :groundwork-access/not-found :groundwork-access/not-allowed]
         [(check/find-refusal-reason :groundwork-access/not-found nil) (check/find-refusal-reason :groundwork-access/not-allowed nil)
          (check/find-refusal-reason :groundwork-access/not-found anna) (check/find-refusal-reason :groundwork-access/not-allowed anna)])))

(deftest the-options-are-required
  (let [errors (try (access/put-access-into-handlers handlers {:tables [shops orders] :scope-kinds scope-kinds})
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :groundwork-access/bad-options (:type errors)))
    (is (= #{:pool :find-session-step} (set (keys (:errors errors)))) "each missing one is named")))

(deftest the-leak-test-takes-the-apps-shaper
  (let [errors (try (access/find-leaks {} {} {} {:seed 7 :size 30})
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :groundwork-access/bad-options (:type errors)))
    (is (= #{:shaper :log :meter :metric-buckets :scope-kinds} (set (keys (:errors errors))))
        "groundwork-access makes no shaper, log or meter: the app hands its own in")))

(deftest the-check-end-to-end
  (if-not port
    (println "groundwork-access.check-test: skipped, ACCESS_TEST_PORT is not set")
    (let [pool   (test-settings/start-pool! (System/getenv "ACCESS_TEST_USER") 4)
          ready  (access/put-access-into-handlers handlers {:pool pool :tables [shops orders] :scope-kinds scope-kinds
                                                            :find-session-step (access/make-find-session-step pool settings)})
          server (bridge/start-server! (get-in (first (bridge/make-routes-for-messages ready server-settings (assoc server-options :meter (test-settings/make-meter)))) [1 :post :handler])
                                          server-settings {:log log})
          cookie #(first (str/split (:set-cookie (access/sign-in! pool {:headers {}} % {:settings settings :tables [] :session-flags #{}})) #";"))]
      (try
        (make-database! pool)
        (let [anna-cookie (cookie anna)]
          (testing "Anna, Staff in shop 7, sees order 42"
            (reset! test-log/lines [])
            (is (= (str "{\"orders/one\":{\"order/id\":\"" order-42 "\"}}") (ask (:port server) anna-cookie order-42)))
            (let [[line] (test-log/find-lines "test/answering")]
              (is (= (str anna) (get-in line ["context" "person-id"]))
                  "the handler's own step writes who asked, which the session step added")
              (is (some? (get-in line ["context" "request-id"])) "beside the message's request id")))
          (testing "Anna asks for shop 9's order 99: not found"
            (is (= (refusal-json "orders/one" :groundwork-access/not-found) (read-without-request-ids (ask (:port server) anna-cookie order-99))))))
        (testing "Bob, Owner of shop 9 only, asks for order 42: not found"
          (is (= (refusal-json "orders/one" :groundwork-access/not-found) (read-without-request-ids (ask (:port server) (cookie bob) order-42)))))
        (testing "a handler without a :record: only for someone who holds its action somewhere"
          (let [remind #(.body (.send (HttpClient/newHttpClient)
                                      (cond-> (HttpRequest/newBuilder (URI. (str "http://localhost:" (:port server) "/mail/http")))
                                        true (.header "origin" "http://app.test")
                                        %    (.header "cookie" %)
                                        true (-> (.POST (HttpRequest$BodyPublishers/ofString "{\"orders/remind\":{}}")) (.build)))
                                      (HttpResponse$BodyHandlers/ofString)))]
            (is (= "{\"orders/remind\":{}}" (remind (cookie anna))) "Anna is Staff somewhere")
            (is (= (refusal-json "orders/remind" :groundwork-access/not-found) (read-without-request-ids (remind (cookie carl)))) "Carl is in no shop")
            (is (= (refusal-json "orders/remind" :groundwork-access/signed-out) (read-without-request-ids (remind nil))) "nobody signed in")))
        (testing "the check's transaction carries the message's log: a step's after-commit work that fails is written with its request id"
          (reset! test-log/lines [])
          (let [answer (.body (.send (HttpClient/newHttpClient)
                                     (-> (HttpRequest/newBuilder (URI. (str "http://localhost:" (:port server) "/mail/http")))
                                         (.header "origin" "http://app.test")
                                         (.header "cookie" (cookie anna))
                                         (.POST (HttpRequest$BodyPublishers/ofString "{\"orders/clear-cache\":{}}"))
                                         (.build))
                                     (HttpResponse$BodyHandlers/ofString)))
                [line] (test-log/find-lines "groundwork-postgres/after-commit-failed")]
            (is (= "{\"orders/clear-cache\":{}}" answer) "the commit stands")
            (is (some? (get-in line ["context" "request-id"])) "the message's request id")
            (is (= (str anna) (get-in line ["context" "person-id"])) "and who asked")))
        (testing "nobody signed in: signed out, whether the record exists or not"
          (is (= (refusal-json "orders/one" :groundwork-access/signed-out) (read-without-request-ids (ask (:port server) nil order-42))))
          (is (= (refusal-json "orders/one" :groundwork-access/signed-out) (read-without-request-ids (ask (:port server) nil order-99))))
          (is (= (refusal-json "orders/one" :groundwork-access/signed-out) (read-without-request-ids (ask (:port server) nil (random-uuid))))
              "no such order: the same answer"))
        (finally
          (bridge/stop-server! server)
          (postgres/stop-pool! pool))))))

(deftest an-action-that-only-reads
  (let [kinds (access/check-scope-kinds
               {:shop {:table :shops :parent nil :single? false :denies []
                       :actions {:orders/see #{:read} :orders/edit #{:read :update}} :roles {}}})]
    (is (true? (access/does-action-only-read? kinds :orders/see)) "runs read-only")
    (is (false? (access/does-action-only-read? kinds :orders/edit)) "may write")
    (is (false? (access/does-action-only-read? kinds :orders/unknown)) "no kind has it")))
