(ns doorman.conditions
  "Every table's sentences checked, then turned into SQL conditions: one per
   action, and one per operation (read, insert, update, delete) for the
   gates, joining the actions that do that operation."
  (:require
   [clojure.string :as str]
   [doorman.sentences :as sentences]))

(def ^:private writes #{:insert :update :delete})

(defn- find-what-actions-do
  "{:orders/see #{:read} …}: every action of every scope kind."
  [scope-kinds]
  (apply merge (map :actions (vals scope-kinds))))

(defn- find-allowed-actions [{:keys [allow]}]
  (if (set? allow) allow #{allow}))

(defn- find-columns-named [{to :to condition :when}]
  (concat (when (vector? to) [(second to)])
          (when condition (filter #(and (keyword? %) (namespace %)) (flatten condition)))))

(defn- find-problems-of-sentence [{:keys [table columns]} actions-do sentence]
  (let [column-keys (set (map first columns))]
    (concat
     (for [action (find-allowed-actions sentence)
           :when (not (contains? actions-do action))]
       (str table ": " action " is no scope kind's action"))
     (for [column (find-columns-named sentence)
           :when (not (contains? column-keys column))]
       (str table ": " column " is no column of the table"))
     (for [action (find-allowed-actions sentence)
           :when (and (#{:anyone :signed-in} (:to sentence))
                      (some writes (get actions-do action))
                      (nil? (:when sentence)))]
       (str table ": " action " writes, so " (:to sentence) " may have it only with a :when")))))

(defn- has-sentences? [table]
  (sequential? (:access table)))

(defn- find-scope-tables [scope-kinds]
  (set (map :table (vals scope-kinds))))

(defn- names-a-scope? [{:keys [columns]} tables scope-kinds]
  (let [scope-tables (find-scope-tables scope-kinds)
        pk-of-scope  (set (for [{:keys [table columns]} tables
                                :when (contains? scope-tables table)
                                [k _ options] columns :when (:primary-key? options)]
                            k))]
    (some (fn [[_ _ options]] (contains? pk-of-scope (:references options))) columns)))

(defn- find-problems-of-table [table tables scope-kinds]
  (when (and (nil? (:access table)) (names-a-scope? table tables scope-kinds))
    [(str (:table table) " names a scope but has no :access: say who may see it, or :access :public")]))

(defn check-sentences
  "Throws with every problem of every table's sentences at once."
  [tables scope-kinds]
  (let [actions-do (find-what-actions-do scope-kinds)
        problems   (concat
                    (mapcat #(find-problems-of-table % tables scope-kinds) tables)
                    (for [table tables :when (has-sentences? table)
                          sentence (:access table), problem (find-problems-of-sentence table actions-do sentence)]
                      problem))]
    (when (seq problems)
      (throw (ex-info (str "The access sentences have problems:\n  " (str/join "\n  " problems))
                      {:type :doorman/bad-sentences :problems (vec problems)})))))

(defn- make-conditions-of-table [table place actions-do]
  (let [place      (assoc place :table table)
        actions    (set (mapcat find-allowed-actions (:access table)))
        by-action  (into {} (for [action actions] [action (sentences/make-condition-for-action action place)]))
        operation  (fn [op] (let [doing (filter #(contains? (get actions-do %) op) actions)]
                              (if (seq doing) (str/join " OR " (map #(str "(" (get by-action %) ")") doing)) "false")))]
    {:actions    by-action
     :operations (into {} (for [op [:read :insert :update :delete]] [op (operation op)]))}))

(defn make-sql-conditions
  "{table-key {:actions {action sql} :operations {:read sql …}}} for every
   table with sentences."
  [tables scope-kinds]
  (check-sentences tables scope-kinds)
  (let [place      {:tables-by-key (into {} (map (juxt :table identity)) tables) :scope-kinds scope-kinds}
        actions-do (find-what-actions-do scope-kinds)]
    (into {} (for [table tables :when (has-sentences? table)]
               [(:table table) (make-conditions-of-table table place actions-do)]))))
