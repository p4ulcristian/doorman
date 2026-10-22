(ns doorman.memberships
  "Who belongs where, as rows: the scopes (the app's own row ids, under a
   parent scope or none), each scope's roles and their actions, and the
   memberships (a person in a scope, with one role there). A membership in
   a scope counts in every scope below it, for the actions of that scope's
   kind and the kinds below it."
  (:require
   [clojure.string]
   [cellar.api :as cellar]
   [doorman.scope-kinds :as scope-kinds]))

(defn- make-query [file-name]
  (cellar/make-query-from-sql-file (str "doorman/" file-name)))

(def ^:private insert-scope                  (make-query "insert_scope.sql"))
(def ^:private find-scope                    (make-query "find_scope.sql"))
(def ^:private lock-scope-kind               (make-query "lock_scope_kind.sql"))
(def ^:private count-scopes-of-kind          (make-query "count_scopes_of_kind.sql"))
(def ^:private insert-role                   (make-query "insert_role.sql"))
(def ^:private insert-role-action            (make-query "insert_role_action.sql"))
(def ^:private find-role                     (make-query "find_role.sql"))
(def ^:private insert-membership             (make-query "insert_membership.sql"))
(def ^:private delete-membership             (make-query "delete_membership.sql"))
(def ^:private find-actions-of-person        (make-query "find_actions_of_person.sql"))
(def ^:private find-scopes-below-memberships (make-query "find_scopes_below_memberships.sql"))
(def ^:private find-memberships-above        (make-query "find_memberships_above.sql"))

(defn- turn-keyword-into-text [k] (subs (str k) 1))

(defn turn-text-into-keyword
  "\"orders/see\" -> :orders/see (with its namespace, unlike (keyword s))."
  [text]
  (apply keyword (clojure.string/split text #"/" 2)))

(defn add-role! [transaction scope-kinds [kind scope-id] role-name actions]
  (let [allowed (scope-kinds/find-actions-at-or-below scope-kinds kind)]
    (doseq [action actions
            :when (not (contains? allowed action))]
      (throw (ex-info (str kind " has no action " action ", nor a kind below it") {:type :doorman/unknown-action :action action}))))
  (cellar/run-statement! transaction insert-role {:role/scope-kind (name kind) :role/scope-id scope-id :role/name role-name})
  (doseq [action actions]
    (cellar/run-statement! transaction insert-role-action {:role-action/scope-kind (name kind) :role-action/scope-id scope-id
                                                           :role-action/role role-name :role-action/action (turn-keyword-into-text action)})))

(defn add-membership! [transaction [kind scope-id] person-id role-name]
  (when-not (cellar/find-one-row transaction find-role {:role/scope-kind (name kind) :role/scope-id scope-id :role/name role-name})
    (throw (ex-info (str "The scope has no role " role-name) {:type :doorman/unknown-role :role role-name})))
  (cellar/run-statement! transaction insert-membership {:membership/person-id person-id :membership/scope-kind (name kind)
                                                        :membership/scope-id scope-id :membership/role role-name}))

(defn- check-parent! [transaction scope-kinds kind parent-id]
  (when parent-id
    (let [parent-kind (get-in scope-kinds [kind :parent])]
      (when-not parent-kind
        (throw (ex-info (str kind " has no :parent kind, so a scope of it has no parent")
                        {:type :doorman/bad-parent :kind kind :parent-id parent-id})))
      (when-not (cellar/find-one-row transaction find-scope {:scope/kind (name parent-kind) :scope/id parent-id})
        (throw (ex-info (str "There is no " parent-kind " " parent-id " to be the parent")
                        {:type :doorman/bad-parent :kind kind :parent-id parent-id}))))))

(defn- check-single! [transaction scope-kinds kind]
  (when (get-in scope-kinds [kind :single?])
    ;; one at a time, so two transactions can't both make the first
    (cellar/find-one-row transaction lock-scope-kind {:scope/lock-name (str "doorman.single/" (name kind))})
    (when (pos? (:scopes/count (cellar/find-one-row transaction count-scopes-of-kind {:scope/kind (name kind)})))
      (throw (ex-info (str kind " has exactly one scope, and it exists")
                      {:type :doorman/single-scope :kind kind})))))

(defn add-scope! [transaction scope-kinds kind scope-id founder-id parent-id]
  (when-not (contains? scope-kinds kind)
    (throw (ex-info (str kind " is no scope kind") {:type :doorman/unknown-kind :kind kind})))
  (check-parent! transaction scope-kinds kind parent-id)
  (check-single! transaction scope-kinds kind)
  (cellar/run-statement! transaction insert-scope {:scope/kind        (name kind)
                                                   :scope/id          scope-id
                                                   :scope/parent-kind (when parent-id (name (get-in scope-kinds [kind :parent])))
                                                   :scope/parent-id   parent-id})
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
      (update actions scope (fnil into #{}) (scope-kinds/find-actions-of-role scope-kinds kind role #{}))
      (update actions scope (fnil conj #{}) (some-> action turn-text-into-keyword)))))

(defn- add-scope-below [scope-kinds direct actions {:scope/keys [kind id] :membership/keys [scope-kind scope-id]}]
  (let [kind     (keyword kind)
        inherits (filter (scope-kinds/find-actions-at-or-below scope-kinds kind)
                         (get direct [(keyword scope-kind) scope-id]))]
    (update actions [kind id] (fnil into #{}) inherits)))

(defn list-actions-of-person [transaction scope-kinds person-id]
  (let [direct (-> (reduce #(add-row-to-actions scope-kinds %1 %2) {}
                           (cellar/find-rows transaction find-actions-of-person {:membership/person-id person-id}))
                   (update-vals #(disj % nil)))]
    (reduce #(add-scope-below scope-kinds direct %1 %2)
            direct
            (cellar/find-rows transaction find-scopes-below-memberships {:membership/person-id person-id}))))

(defn list-memberships-above
  "A scope and every scope above it, nearest first, with the person's
   role in each: [{:scope-kind :scope-id :depth :role}], :role nil where
   they aren't a member."
  [transaction [kind scope-id] person-id]
  (mapv (fn [{:scope/keys [kind id depth] :membership/keys [role]}]
          {:scope-kind (keyword kind) :scope-id id :depth depth :role role})
        (cellar/find-rows transaction find-memberships-above {:scope/kind (name kind) :scope/id scope-id
                                                              :membership/person-id person-id})))
