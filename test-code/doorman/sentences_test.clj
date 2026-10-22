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
   :access  [{:allow #{:orders/see :orders/edit} :to [:member :order/shop-id]}
             {:allow :orders/see :to [:me :order/customer-id]}
             {:allow :orders/place :to :signed-in :when [:= :order/status "placed"]}]})

(def ^:private member-sees "order__shop_id = ANY ((SELECT doorman.find_scopes_holding('shop', 'orders/see'))::uuid[])")
(def ^:private member-edits "order__shop_id = ANY ((SELECT doorman.find_scopes_holding('shop', 'orders/edit'))::uuid[])")

(deftest each-action-gets-one-condition
  (let [conditions (doorman/make-sql-conditions [shops orders] scope-kinds)]
    (testing "its sentences, joined by OR"
      (is (= (str "(" member-sees ") OR (order__customer_id = (SELECT doorman.find_asking_person()))")
             (get-in conditions [:orders :actions :orders/see]))))
    (testing "a :when narrows its sentence"
      (is (= "((SELECT doorman.find_asking_person()) IS NOT NULL AND order__status = 'placed')"
             (get-in conditions [:orders :actions :orders/place]))))
    (testing "an operation joins the actions that do it"
      (is (= (str "((" member-edits "))") (get-in conditions [:orders :operations :update])))
      (is (= "false" (get-in conditions [:orders :operations :delete])) "no action deletes orders"))))

(deftest the-sentences-are-checked
  (testing "an action no scope kind has"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":orders/fly is no scope kind's action"
                          (doorman/make-sql-conditions [shops (assoc orders :access [{:allow :orders/fly :to :anyone}])] scope-kinds))))
  (testing "a column the table doesn't have"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":order/owner-id is no column"
                          (doorman/make-sql-conditions [shops (assoc orders :access [{:allow :orders/see :to [:me :order/owner-id]}])] scope-kinds))))
  (testing "an open write without a :when"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"writes"
                          (doorman/make-sql-conditions [shops (assoc orders :access [{:allow :orders/edit :to :anyone}])] scope-kinds)))))
