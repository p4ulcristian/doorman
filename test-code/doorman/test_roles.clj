(ns doorman.test-roles
  "A member added by the names of the scope's roles, as the tests write
   them: doorman's memberships hold role ids, which the scope's roles
   list."
  (:require
   [doorman.api :as doorman]))

(defn find-role-id
  "The id of the scope's role of this name."
  [transaction scope-kinds scope role-name]
  (or (some #(when (= role-name (:name %)) (:role-id %)) (doorman/list-roles transaction scope-kinds scope))
      (throw (ex-info (str "The scope has no role " role-name) {:scope scope :name role-name}))))

(defn add-member!
  "Puts the person in the scope holding the roles of these names (#{} for none)."
  [transaction scope-kinds scope person-id role-names]
  (doorman/add-membership! transaction scope person-id (set (map #(find-role-id transaction scope-kinds scope %) role-names))))
