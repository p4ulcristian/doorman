(ns doorman.my-actions-test
  "\"What may I do?\": the my-actions handler answered as over http, by
   the app's wire, for people with and without memberships, and nobody.
   The Postgres one runs when DOORMAN_TEST_PORT is set."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [doorman.test-answers :refer [refusal refusal-json read-without-request-ids without-request-id]]
   [cellar.api :as cellar]
   [doorman.api :as doorman]
   [doorman.test-roles :refer [add-member!]]
   [doorman.shapes]
   [doorman.test-log :refer [log]]
   [doorman.test-settings :as test-settings]
   [doorman.test-wire :refer [wire]]
   [bridge.api :as bridge]
   [wire.api :as wire-api]))

(def ^:private port (some-> (System/getenv "DOORMAN_TEST_PORT") parse-long))

(def ^:private scope-kinds
  (doorman/check-scope-kinds
   {:shop {:table   :shops
           :actions {:orders/see #{:read} :orders/edit #{:read :insert :update}
                     :posts/read #{:read} :profile/edit #{:read :update} :comments/write #{:insert}}
           :roles   {"Staff" #{:orders/see :orders/edit}}
           :parent  nil :single? false :denies []}}))

(def ^:private shops
  {:table :shops :columns [[:shop/id :uuid {:primary-key? true}]] :indexes [] :references [] :triggers [] :gates nil :on-change nil :access :public})

(def ^:private orders
  {:table     :orders
   :columns   [[:order/id :uuid {:primary-key? true}] [:order/shop-id :uuid {:references :shop/id}]]
   :indexes   []
   :references []
   :triggers   []
   :gates     nil
   :on-change nil
   :access    [{:allow #{:orders/see :orders/edit} :only :all-of-the-action :to [:member :order/shop-id]}]})

(def ^:private posts
  {:table     :posts
   :columns   [[:post/id :uuid {:primary-key? true}] [:post/status :string]]
   :indexes   []
   :references []
   :triggers   []
   :gates     nil
   :on-change nil
   :access    [{:allow :posts/read :only :all-of-the-action :to :anyone}
               {:allow :comments/write :only :all-of-the-action :to :signed-in :when [:= :post/status "open"]}]})

(def ^:private profiles
  {:table     :profiles
   :columns   [[:profile/id :uuid {:primary-key? true}] [:profile/person-id :uuid]]
   :indexes   []
   :references []
   :triggers   []
   :gates     nil
   :on-change nil
   :access    [{:allow :profile/edit :only :all-of-the-action :to [:me :profile/person-id]}]})

(def ^:private tables [shops orders posts profiles])

(def ^:private paul #uuid "00000000-0000-4000-8000-00000000000a")
(def ^:private anna #uuid "00000000-0000-4000-8000-00000000000b")
(def ^:private carl #uuid "00000000-0000-4000-8000-00000000000e")
(def ^:private shop-7 #uuid "70000000-0000-4000-8000-000000000007")
(def ^:private shop-9 #uuid "90000000-0000-4000-8000-000000000009")

(def ^:private settings {:cookie-name "app_session" :session-days 1 :visitor-session-days 1 :renew-after-minutes 60
                         :allowed-origin "http://app.test" :secure-cookie? false
                         :token-lead "app_" :token-shown-characters 10 :token-longest-days 30})

(def ^:private no-session-step {:name :no-session :enter identity})

(deftest the-options-are-required
  (let [errors (try (doorman/make-my-actions-handler {:pool (Object.) :tables tables :scope-kinds scope-kinds
                                                      :find-session-step no-session-step})
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
    (is (= :doorman/bad-options (:type errors)))
    (is (= #{:rate-limit} (set (keys (:errors errors)))) "nil written out for no rate limit")))

(deftest the-handler-writes-out-bridge-s-keys
  (let [handler (doorman/make-my-actions-handler {:pool (Object.) :tables tables :scope-kinds scope-kinds
                                                  :find-session-step no-session-step :rate-limit {:max 60 :per-ms 60000}})]
    (is (= #{:params-shape :answer-shape :refusals :rate-limit :run-steps-in :steps} (set (keys handler))))
    (is (= doorman/refusals (:refusals handler)) "its find-session step refuses with doorman's reasons")
    (is (= doorman/my-actions-answer-shape (:answer-shape handler)))
    (is (= {:max 60 :per-ms 60000} (:rate-limit handler)))))

(defn- run-sql! [pool text] (cellar/run-statement! pool (cellar/make-query-from-sql-text "test" text) {}))

(defn- ask-as
  "The answer as the browser reads it: over http (JSON data by the answer
   shape), back into shaped data by the same shape."
  [handlers cookie origin]
  (let [answer (:doorman/my-actions
                (bridge/answer-message handlers :no-rate-limits
                                         {:request-method :post
                                          :headers        (cond-> {}
                                                            origin (assoc "origin" origin)
                                                            cookie (assoc "cookie" cookie))}
                                         {:doorman/my-actions {}}
                                         {:wire wire :log log :meter (test-settings/make-meter) :metric-buckets test-settings/metric-buckets}))]
    (if (bridge/is-error-answer? answer)
      (without-request-id answer)
      (:shaped-data (wire-api/turn-json-data-into-shaped-data-or-errors wire doorman/my-actions-answer-shape answer)))))

(deftest what-may-i-do
  (if-not port
    (println "doorman.my-actions-test: skipped, DOORMAN_TEST_PORT is not set")
    (let [pool     (test-settings/start-pool! (System/getenv "DOORMAN_TEST_USER") 2)
          handlers {:doorman/my-actions
                    (doorman/make-my-actions-handler {:pool pool :tables tables :scope-kinds scope-kinds
                                                      :find-session-step (doorman/make-find-session-step pool settings)
                                                      :rate-limit nil})}
          cookie   #(first (str/split (:set-cookie (doorman/sign-in! pool {:headers {}} % {:settings settings :tables [] :session-flags #{}})) #";"))
          ask      #(ask-as handlers %1 %2)]
      (try
        (run-sql! pool "DROP SCHEMA IF EXISTS public CASCADE; CREATE SCHEMA public; DROP SCHEMA IF EXISTS doorman CASCADE; DROP SCHEMA IF EXISTS cellar CASCADE")
        (with-out-str @(:slow-part (cellar/migrate! pool (into [shops doorman/sessions-table doorman/tokens-table] doorman/access-tables) [] test-settings/migrate-options)))
        (cellar/run-in-transaction! pool {:read-only? false}
          (fn [transaction]
            (doorman/add-scope! transaction scope-kinds :shop shop-9 paul nil)
            (doorman/add-scope! transaction scope-kinds :shop shop-7 paul nil)
            (add-member! transaction scope-kinds [:shop shop-7] anna #{"Staff"})))
        (testing "Anna, Staff in shop 7: what is open to someone signed in, everywhere; her role's in shop 7"
          (is (= [{:scope-kind nil :scope-id nil :actions [:comments/write :posts/read :profile/edit]}
                  {:scope-kind :shop :scope-id shop-7 :actions [:orders/edit :orders/see]}]
                 (ask (cookie anna) "http://app.test"))
              "[:me …]'s :profile/edit is listed for every scope; the row decides"))
        (testing "Paul, Owner of two shops: every action of the kind in each, by id"
          (let [every-action [:comments/write :orders/edit :orders/see :posts/read :profile/edit]]
            (is (= [{:scope-kind nil :scope-id nil :actions [:comments/write :posts/read :profile/edit]}
                    {:scope-kind :shop :scope-id shop-7 :actions every-action}
                    {:scope-kind :shop :scope-id shop-9 :actions every-action}]
                   (ask (cookie paul) "http://app.test")))))
        (testing "Carl, in no shop: only what is open"
          (is (= [{:scope-kind nil :scope-id nil :actions [:comments/write :posts/read :profile/edit]}]
                 (ask (cookie carl) "http://app.test"))))
        (testing "nobody signed in: what is open to anyone"
          (is (= [{:scope-kind nil :scope-id nil :actions [:posts/read]}] (ask nil "http://app.test"))))
        (testing "a request from another site is refused before anything is read"
          (is (= (refusal :doorman/not-allowed) (ask (cookie anna) "https://elsewhere.test"))))
        (testing "an app with no open actions: no entry of nil kind and id"
          (let [closed {:doorman/my-actions
                        (doorman/make-my-actions-handler {:pool pool :tables [shops orders] :scope-kinds scope-kinds
                                                          :find-session-step (doorman/make-find-session-step pool settings)
                                                          :rate-limit nil})}]
            (is (= [] (ask-as closed nil "http://app.test")))))
        (finally (cellar/stop-pool! pool))))))

(deftest the-answer-shape-is-one-on-both-sides
  (is (identical? doorman.shapes/my-actions-answer-shape doorman/my-actions-answer-shape)
      "the server's is doorman.shapes's, the .cljc the browser's half reads"))
