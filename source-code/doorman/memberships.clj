(ns doorman.memberships
  "Who belongs where, as rows: the scopes (the app's own row ids), each
   scope's roles and their actions, and the memberships (a person in a
   scope, with one role there)."
  (:require
   [clojure.string]
   [cellar.api :as cellar]
   [doorman.scope-kinds :as scope-kinds]))

(defn- make-query [file-name]
  (cellar/make-query-from-sql-file (str "doorman/" file-name)))

(def ^:private insert-scope           (make-query "insert_scope.sql"))
(def ^:private insert-role            (make-query "insert_role.sql"))
(def ^:private insert-role-action     (make-query "insert_role_action.sql"))
(def ^:private find-role              (make-query "find_role.sql"))
(def ^:private insert-membership      (make-query "insert_membership.sql"))
(def ^:private delete-membership      (make-query "delete_membership.sql"))
(def ^:private find-actions-of-person (make-query "find_actions_of_person.sql"))

(defn- turn-keyword-into-text [k] (subs (str k) 1))

(defn- turn-text-into-keyword
  "\"orders/see\" -> :orders/see (with its namespace, unlike (keyword s))."
  [text]
  (apply keyword (clojure.string/split text #"/" 2)))

(defn add-role! [transaction scope-kinds [kind scope-id] role-name actions]
  (doseq [action actions
          :when (not (contains? (get-in scope-kinds [kind :actions]) action))]
    (throw (ex-info (str kind " has no action " action) {:type :doorman/unknown-action :action action})))
  (cellar/run-statement! transaction insert-role {:role/scope-kind (name kind) :role/scope-id scope-id :role/name role-name})
  (doseq [action actions]
    (cellar/run-statement! transaction insert-role-action {:role-action/scope-kind (name kind) :role-action/scope-id scope-id
                                                           :role-action/role role-name :role-action/action (turn-keyword-into-text action)})))

(defn add-membership! [transaction [kind scope-id] person-id role-name]
  (when-not (cellar/find-one-row transaction find-role {:role/scope-kind (name kind) :role/scope-id scope-id :role/name role-name})
    (throw (ex-info (str "The scope has no role " role-name) {:type :doorman/unknown-role :role role-name})))
  (cellar/run-statement! transaction insert-membership {:membership/person-id person-id :membership/scope-kind (name kind)
                                                        :membership/scope-id scope-id :membership/role role-name}))

(defn add-scope! [transaction scope-kinds kind scope-id founder-id]
  (cellar/run-statement! transaction insert-scope {:scope/kind (name kind) :scope/id scope-id})
  (add-role! transaction scope-kinds [kind scope-id] scope-kinds/owner-role #{})
  (doseq [[role-name actions] (get-in scope-kinds [kind :roles])]
    (add-role! transaction scope-kinds [kind scope-id] role-name actions))
  (add-membership! transaction [kind scope-id] founder-id scope-kinds/owner-role))

(defn remove-membership! [transaction [kind scope-id] person-id]
  (pos? (cellar/run-statement! transaction delete-membership {:membership/person-id person-id :membership/scope-kind (name kind)
                                                              :membership/scope-id scope-id})))

(defn- add-row-to-actions [scope-kinds actions {:membership/keys [scope-kind scope-id role] :role-action/keys [action]}]
  (let [kind  (keyword scope-kind)
        scope [kind scope-id]]
    (if (= scope-kinds/owner-role role)
      (assoc actions scope (scope-kinds/find-actions-of-role scope-kinds kind role #{}))
      (update actions scope (fnil conj #{}) (some-> action turn-text-into-keyword)))))

(defn list-actions-of-person [transaction scope-kinds person-id]
  (-> (reduce #(add-row-to-actions scope-kinds %1 %2) {}
              (cellar/find-rows transaction find-actions-of-person {:membership/person-id person-id}))
      (update-vals #(disj % nil))))
