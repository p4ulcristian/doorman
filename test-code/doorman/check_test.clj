(ns doorman.check-test
  "The check, end to end: a session cookie, a message over http, a record
   in a shop. Runs when DOORMAN_TEST_PORT is set."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [doorman.api :as doorman]
   [doorman.test-settings :as test-settings]
   [cellar.api :as cellar]
   [doorman.test-log :as test-log :refer [log]]
   [doorman.test-wire :refer [wire]]
   [bridge.api :as bridge])
  (:import
   (java.net URI)
   (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

(def ^:private port (some-> (System/getenv "DOORMAN_TEST_PORT") parse-long))

(def ^:private scope-kinds
  (doorman/check-scope-kinds
   {:shop {:table :shops :parent nil :single? false :denies []
           :actions {:orders/see #{:read}} :roles {"Staff" #{:orders/see}}}}))

(def ^:private shops
  {:table     :shops
   :columns   [[:shop/id :uuid {:primary-key? true}]]
   :indexes   []
   :references []
   :triggers   []
   :gates     nil
   :on-change nil})

(def ^:private orders
  {:table     :orders
   :columns   [[:order/id :uuid {:primary-key? true}]
               [:order/shop-id :uuid {:references :shop/id}]]
   :indexes   []
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

(def ^:private settings {:cookie-name "app_session" :session-days 1 :visitor-session-days 1 :renew-after-minutes 60 :allowed-origin "http://app.test" :secure-cookie? false})

(def ^:private answer-the-order
  {:name  :answer-the-order
   :enter (fn [{:bridge/keys [log message-data] :as context}]
            (log :info :test/answering {:order/id (:order/id message-data)})
            (bridge/add-answer-to-context context {:order/id (:order/id message-data)}))})

(def ^:private carl #uuid "00000000-0000-4000-8000-00000000000e")

(def ^:private handlers
  {:orders/remind {:action       :orders/see                ; no :record: an email, say
                   :scope        :every-scope-holding-the-action
                   :params-shape [:map]
                   :answer-shape [:map]
                   :rate-limit   nil
                   :run-steps-in nil
                   :steps        [{:name :remind :enter #(bridge/add-answer-to-context % {})}]}
   :orders/one {:action       :orders/see
                :record       [:orders :order/id]
                :params-shape [:map [:order/id :uuid]]
                :answer-shape [:map [:order/id :uuid]]
                :rate-limit   nil
                :run-steps-in nil
                :steps        [answer-the-order]}})

(defn- run-sql! [pool text] (cellar/run-statement! pool (cellar/make-query-from-sql-text "test" text) {}))

(defn- ask [port cookie order-id]
  (.body (.send (HttpClient/newHttpClient)
                (cond-> (HttpRequest/newBuilder (URI. (str "http://localhost:" port "/mail/http")))
                  true   (.header "origin" "http://app.test")
                  cookie (.header "cookie" cookie)
                  true   (-> (.POST (HttpRequest$BodyPublishers/ofString (str "{\"orders/one\":{\"order/id\":\"" order-id "\"}}")))
                             (.build)))
                (HttpResponse$BodyHandlers/ofString))))

(defn- make-database! [pool]
  (run-sql! pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS doorman CASCADE; DROP SCHEMA IF EXISTS cellar CASCADE")
  (with-out-str @(:slow-part (cellar/migrate! pool (into [shops orders doorman/sessions-table] doorman/access-tables) [] test-settings/migrate-options)))
  (doorman/install-access-functions! pool [shops orders] scope-kinds)
  (run-sql! pool (str "INSERT INTO shops VALUES ('" shop-7 "'), ('" shop-9 "')"))
  (run-sql! pool (str "INSERT INTO orders VALUES ('" order-42 "', '" shop-7 "'), ('" order-99 "', '" shop-9 "')"))
  (cellar/run-in-transaction! pool {:read-only? false}
    (fn [transaction]
      (doorman/add-scope! transaction scope-kinds :shop shop-7 paul nil)
      (doorman/add-scope! transaction scope-kinds :shop shop-9 bob nil)
      (doorman/add-membership! transaction [:shop shop-7] anna "Staff"))))

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

(def ^:private no-session-step {:name :no-session :enter identity})

(deftest a-handler-must-declare-its-action-and-shapes
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"has no :action"
                        (doorman/put-access-into-handlers {:x/y {:steps []}} {:pool (Object.) :tables [shops orders] :scope-kinds scope-kinds
                                                                              :find-session-step no-session-step}))))

(deftest a-handler-writes-out-bridge-s-keys
  (let [problems (try (doorman/put-access-into-handlers
                       {:x/y (dissoc (:orders/remind handlers) :rate-limit :run-steps-in)
                        :x/z (assoc (:orders/remind handlers) :run-steps-in (fn [run context] (run context)))}
                       {:pool (Object.) :tables [shops orders] :scope-kinds scope-kinds :find-session-step no-session-step})
                      (catch clojure.lang.ExceptionInfo e (:problems (ex-data e))))]
    (is (= [":x/y has no :rate-limit (nil written out where nothing applies)"
            ":x/y has no :run-steps-in (nil written out where nothing applies)"
            ":x/z has its own :run-steps-in; doorman runs the steps in its transaction, so it must be nil"]
           problems)
        "every problem at once")))

(deftest the-options-are-required
  (let [errors (try (doorman/put-access-into-handlers handlers {:tables [shops orders] :scope-kinds scope-kinds})
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :doorman/bad-options (:type errors)))
    (is (= #{:pool :find-session-step} (set (keys (:errors errors)))) "each missing one is named")))

(deftest the-leak-test-takes-the-apps-wire
  (let [errors (try (doorman/find-leaks {} {} {} {:seed 7 :size 30})
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :doorman/bad-options (:type errors)))
    (is (= #{:wire :log :scope-kinds} (set (keys (:errors errors)))) "doorman makes no wire and no log: the app hands its own in")))

(deftest the-check-end-to-end
  (if-not port
    (println "doorman.check-test: skipped, DOORMAN_TEST_PORT is not set")
    (let [pool   (test-settings/start-pool! (System/getenv "DOORMAN_TEST_USER") 4)
          ready  (doorman/put-access-into-handlers handlers {:pool pool :tables [shops orders] :scope-kinds scope-kinds
                                                            :find-session-step (doorman/make-find-session-step pool settings)})
          server (bridge/start-server! (get-in (first (bridge/make-routes-for-messages ready server-settings (assoc server-options :meter (test-settings/make-meter)))) [1 :post :handler])
                                          server-settings {:log log})
          cookie #(first (str/split (:set-cookie (doorman/sign-in! pool {:headers {}} % {:settings settings :tables [] :session-flags #{}})) #";"))]
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
            (is (= "{\"orders/one\":{\"error\":\"Not found.\"}}" (ask (:port server) anna-cookie order-99)))))
        (testing "Bob, Owner of shop 9 only, asks for order 42: not found"
          (is (= "{\"orders/one\":{\"error\":\"Not found.\"}}" (ask (:port server) (cookie bob) order-42))))
        (testing "a handler without a :record: only for someone who holds its action somewhere"
          (let [remind #(.body (.send (HttpClient/newHttpClient)
                                      (cond-> (HttpRequest/newBuilder (URI. (str "http://localhost:" (:port server) "/mail/http")))
                                        true (.header "origin" "http://app.test")
                                        %    (.header "cookie" %)
                                        true (-> (.POST (HttpRequest$BodyPublishers/ofString "{\"orders/remind\":{}}")) (.build)))
                                      (HttpResponse$BodyHandlers/ofString)))]
            (is (= "{\"orders/remind\":{}}" (remind (cookie anna))) "Anna is Staff somewhere")
            (is (= "{\"orders/remind\":{\"error\":\"Not found.\"}}" (remind (cookie carl))) "Carl is in no shop")
            (is (= "{\"orders/remind\":{\"error\":\"Not found.\"}}" (remind nil)) "nobody signed in")))
        (testing "nobody signed in: not found"
          (is (= "{\"orders/one\":{\"error\":\"Not found.\"}}" (ask (:port server) nil order-42))))
        (finally
          (bridge/stop-server! server)
          (cellar/stop-pool! pool))))))

(deftest an-action-that-only-reads
  (let [kinds (doorman/check-scope-kinds
               {:shop {:table :shops :parent nil :single? false :denies []
                       :actions {:orders/see #{:read} :orders/edit #{:read :update}} :roles {}}})]
    (is (true? (doorman/does-action-only-read? kinds :orders/see)) "runs read-only")
    (is (false? (doorman/does-action-only-read? kinds :orders/edit)) "may write")
    (is (false? (doorman/does-action-only-read? kinds :orders/unknown)) "no kind has it")))
