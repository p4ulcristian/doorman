(ns doorman.sentences-test
  "Sentences into SQL: pure, no database."
  (:require
   [clojure.test :refer [deftest is testing]]
   [doorman.api :as doorman]))

(def ^:private scope-kinds
  {:shop {:table   :shops
          :actions {:orders/see #{:read} :orders/edit #{:read :insert :update} :orders/place #{:insert}}
          :roles   {}}})

(def ^:private shops
  {:table :shops :columns [[:shop/id :uuid {:primary-key? true}]]})

(def ^:private orders
  {:table   :orders
   :columns [[:order/id :uuid {:primary-key? true}]
             [:order/shop-id :uuid {:references :shop/id}]
             [:order/customer-id :uuid {:optional? true}]
             [:order/status :string]]
   :access  [{:allow #{:orders/see :orders/edit} :only :all-of-the-action :to [:member :order/shop-id]}
             {:allow :orders/see :only :all-of-the-action :to [:me :order/customer-id]}
             {:allow :orders/place :only :all-of-the-action :to :signed-in :when [:= :order/status "placed"]}]})

(def ^:private member-sees "order__shop_id = ANY ((SELECT doorman.find_scopes_holding('shop', 'orders/see'))::uuid[])")
(def ^:private member-edits "order__shop_id = ANY ((SELECT doorman.find_scopes_holding('shop', 'orders/edit'))::uuid[])")

(defn- acting [action sql] (str "((SELECT doorman.is_acting('" action "')) AND (" sql "))"))

(deftest each-action-gets-one-condition
  (let [conditions (doorman/make-sql-conditions [shops orders] scope-kinds)]
    (testing "its sentences, joined by OR"
      (is (= (acting "orders/see" (str "(" member-sees ") OR (order__customer_id = (SELECT doorman.find_asking_person()))"))
             (get-in conditions [:orders :actions :orders/see]))
          "a person's sentences count only while the handler acts as the action"))
    (testing "a :when narrows its sentence"
      (is (= (acting "orders/place" "((SELECT doorman.find_asking_person()) IS NOT NULL AND order__status = 'placed')")
             (get-in conditions [:orders :actions :orders/place]))))
    (testing "an operation joins the actions that do it"
      (is (= (str "(" (acting "orders/edit" (str "(" member-edits ")")) ")") (get-in conditions [:orders :operations :update])))
      (is (= "false" (get-in conditions [:orders :operations :delete])) "no action deletes orders"))))

(deftest the-sentences-are-checked
  (testing "an action no scope kind has"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":orders/fly is no scope kind's action"
                          (doorman/make-sql-conditions [shops (assoc orders :access [{:allow :orders/fly :only :all-of-the-action :to :anyone}])] scope-kinds))))
  (testing "a column the table doesn't have"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":order/owner-id is no column"
                          (doorman/make-sql-conditions [shops (assoc orders :access [{:allow :orders/see :only :all-of-the-action :to [:me :order/owner-id]}])] scope-kinds))))
  (testing "an open write without a :when"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"writes"
                          (doorman/make-sql-conditions [shops (assoc orders :access [{:allow :orders/edit :only :all-of-the-action :to :anyone}])] scope-kinds)))))

(def ^:private workstations
  {:table   :workstations
   :columns [[:workstation/id :uuid {:primary-key? true}]
             [:workstation/shop-id :uuid {:references :shop/id}]]
   :access  [{:allow :orders/edit :only #{:read} :to [:member :workstation/shop-id]}]})

(deftest only-narrows-a-sentence-to-some-operations
  (let [conditions (doorman/make-sql-conditions [shops orders workstations] scope-kinds)
        edits      "workstation__shop_id = ANY ((SELECT doorman.find_scopes_holding('shop', 'orders/edit'))::uuid[])"]
    (is (= (str "((SELECT doorman.is_passing())) OR ((" (acting "orders/edit" (str "(" edits ")")) "))")
           (get-in conditions [:workstations :operations :read])))
    (is (= "false" (get-in conditions [:workstations :operations :update])) ":only #{:read}: no update on this table")
    (is (= "false" (get-in conditions [:workstations :operations :insert])))
    (is (= (acting "orders/edit" (str "(" edits ")")) (get-in conditions [:workstations :actions :orders/edit]))
        "the check's condition: every sentence of the action")))

(deftest a-sentence-writes-out-only
  (let [problems #(try (doorman/make-sql-conditions [shops (assoc orders :access %)] scope-kinds) []
                       (catch clojure.lang.ExceptionInfo e (:problems (ex-data e))))]
    (is (= 2 (count (problems [{:allow :orders/see :to :signed-in}
                               {:allow :orders/see :only #{:delete} :to [:member :order/shop-id]}])))
        "every problem at once")
    (is (some #(re-find #"say which operations it allows" %) (problems [{:allow :orders/see :to :signed-in}])))
    (is (some #(re-find #":only #\{:delete\} is more than :orders/see does" %)
              (problems [{:allow :orders/see :only #{:delete} :to [:member :order/shop-id]}])))
    (is (some #(re-find #"is more than :orders/see does" %)
              (problems [{:allow #{:orders/see :orders/edit} :only #{:read :update} :to [:member :order/shop-id]}]))
        "with several actions, :only must fit each")
    (is (some #(re-find #"non-empty set" %) (problems [{:allow :orders/see :only #{} :to [:member :order/shop-id]}])))
    (is (= [] (problems [{:allow #{:orders/see :orders/edit} :only #{:read} :to [:member :order/shop-id]}])))))

(deftest a-member-sentence-may-name-the-scopes-own-row
  (let [kinds (assoc-in scope-kinds [:shop :actions :shops/edit] #{:read :update})
        shops (assoc shops :access [{:allow :shops/edit :only :all-of-the-action :to [:member :shop/id]}])]
    (is (= (acting "shops/edit" "(shop__id = ANY ((SELECT doorman.find_scopes_holding('shop', 'shops/edit'))::uuid[]))")
           (get-in (doorman/make-sql-conditions [shops orders] kinds) [:shops :actions :shops/edit]))
        "the row is the scope itself")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":order/status names no scope"
                          (doorman/make-sql-conditions [shops (assoc orders :access [{:allow :orders/see :only :all-of-the-action
                                                                                      :to [:member :order/status]}])]
                                                       kinds))
        "another column still names none")))
