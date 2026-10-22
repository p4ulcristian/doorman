(ns doorman.scope-kinds
  "The app's kinds of scope, in its code:

     {:shop {:table   :shops
             :actions {:orders/see #{:read} :orders/edit #{:read :insert :update}}
             :roles   {\"Staff\" #{:orders/see :orders/edit} \"Bookkeeper\" #{:orders/see}}}}

   Every scope also has the role \"Owner\", holding every action of its kind."
  (:require
   [clojure.string :as str]))

(def owner-role "Owner")

(defn- find-problems-of-kind [[kind {:keys [actions roles]}]]
  (concat
   (when (contains? roles owner-role)
     [(str kind ": \"Owner\" is doorman's own role, holding every action")])
   (for [[role-name role-actions] roles
         action role-actions
         :when (not (contains? actions action))]
     (str kind ": role " role-name " names " action ", which isn't one of its actions"))
   (for [[action does] actions
         :when (not (and (set? does) (seq does) (every? #{:read :insert :update :delete} does)))]
     (str kind ": action " action " must say what it does: a set of :read :insert :update :delete"))))

(defn check-scope-kinds
  "Returns the scope kinds; throws with every problem at once."
  [scope-kinds]
  (let [problems (mapcat find-problems-of-kind scope-kinds)]
    (when (seq problems)
      (throw (ex-info (str "The scope kinds have problems:\n  " (str/join "\n  " problems))
                      {:type :doorman/bad-scope-kinds :problems (vec problems)})))
    scope-kinds))

(defn find-actions-of-role
  "The actions a role allows in a kind: Owner holds every one."
  [scope-kinds kind role-name role-actions]
  (if (= owner-role role-name)
    (set (keys (get-in scope-kinds [kind :actions])))
    role-actions))

(defn does-action-only-read?
  "Whether the action does nothing but :read in its kind: its handlers run
   in a read-only transaction. An action no kind has is not."
  [scope-kinds action]
  (= #{:read} (some #(get-in % [:actions action]) (vals scope-kinds))))
