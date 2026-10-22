(ns doorman.memberships-test
  "The Postgres tests run when DOORMAN_TEST_PORT is set; they say
   they were skipped otherwise."
  (:require
   [clojure.test :refer [deftest is testing]]
   [doorman.api :as doorman]
   [doorman.test-settings :as test-settings]
   [cellar.api :as cellar]))

(def ^:private port (some-> (System/getenv "DOORMAN_TEST_PORT") parse-long))

(def ^:private scope-kinds
  (doorman/check-scope-kinds
   {:shop {:table   :shops
           :actions {:orders/see #{:read} :orders/edit #{:read :insert :update}}
           :roles   {"Staff" #{:orders/see :orders/edit} "Bookkeeper" #{:orders/see}}
           :parent nil :single? false :denies []}}))

(def ^:private paul #uuid "00000000-0000-4000-8000-00000000000a")
(def ^:private anna #uuid "00000000-0000-4000-8000-00000000000b")
(def ^:private dora #uuid "00000000-0000-4000-8000-00000000000c")
(def ^:private bob  #uuid "00000000-0000-4000-8000-00000000000d")
(def ^:private shop-7 #uuid "70000000-0000-4000-8000-000000000007")

(deftest the-scope-kinds-are-checked
  (testing "a role naming an action its kind doesn't have"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"names :orders/fly"
                          (doorman/check-scope-kinds {:shop {:table nil :parent nil :single? false :denies []
                                                                      :actions {:orders/see #{:read}} :roles {"Pilot" #{:orders/fly}}}}))))
  (testing "Owner is doorman's own"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Owner"
                          (doorman/check-scope-kinds {:shop {:table nil :parent nil :single? false :denies []
                                                                      :actions {:orders/see #{:read}} :roles {"Owner" #{}}}})))))

(deftest who-belongs-where
  (if-not port
    (println "doorman.memberships-test: skipped, DOORMAN_TEST_PORT is not set")
    (let [pool (test-settings/start-pool! (System/getenv "DOORMAN_TEST_USER") 2)]
      (try
        (cellar/run-statement! pool (cellar/make-query-from-sql-text "test" "DROP SCHEMA IF EXISTS doorman CASCADE; DROP SCHEMA IF EXISTS cellar CASCADE") {})
        (with-out-str @(:slow-part (cellar/migrate! pool doorman/access-tables [] test-settings/migrate-options)))
        (cellar/run-in-transaction! pool {:read-only? false}
          (fn [transaction]
            (doorman/add-scope! transaction scope-kinds :shop shop-7 paul nil)
            (doorman/add-membership! transaction [:shop shop-7] anna "Staff")
            (doorman/add-membership! transaction [:shop shop-7] dora "Bookkeeper")))
        (testing "each person's actions, by scope"
          (is (= {[:shop shop-7] #{:orders/see :orders/edit}} (doorman/list-actions-of-person pool scope-kinds paul)) "the founder is Owner: everything")
          (is (= {[:shop shop-7] #{:orders/see :orders/edit}} (doorman/list-actions-of-person pool scope-kinds anna)))
          (is (= {[:shop shop-7] #{:orders/see}} (doorman/list-actions-of-person pool scope-kinds dora)))
          (is (= {} (doorman/list-actions-of-person pool scope-kinds bob)) "Bob is in no scope"))
        (testing "a shop makes a role of its own, live"
          (doorman/add-role! pool scope-kinds [:shop shop-7] "Driver" #{:orders/see})
          (doorman/add-membership! pool [:shop shop-7] bob "Driver")
          (is (= {[:shop shop-7] #{:orders/see}} (doorman/list-actions-of-person pool scope-kinds bob))))
        (testing "a role the scope doesn't have, or an action its kind doesn't have, is refused"
          (is (thrown? clojure.lang.ExceptionInfo (doorman/add-membership! pool [:shop shop-7] bob "Pilot")))
          (is (thrown? clojure.lang.ExceptionInfo (doorman/add-role! pool scope-kinds [:shop shop-7] "Pilot" #{:orders/fly}))))
        (testing "leaving a scope"
          (is (true? (doorman/remove-membership! pool [:shop shop-7] dora)))
          (is (= {} (doorman/list-actions-of-person pool scope-kinds dora))))
        (finally (cellar/stop-pool! pool))))))
