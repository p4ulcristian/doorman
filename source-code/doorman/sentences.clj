(ns doorman.sentences
  "The :access sentences on a table map, turned into SQL conditions: for
   each action, one condition a row must meet for the person asking.

     {:allow #{:orders/see :orders/edit} :to [:member :order/shop-id]}
     {:allow :orders/see :to [:me :order/customer-id] :when [:= :order/status \"placed\"]}

   :to    :anyone, :signed-in, [:member column] (a member of the scope the
          column names, whose role holds the action), [:me column] (the
          person the column names)
   :when  a condition on the row's own columns: [:= col value],
          [:not= col value], [:in col [values]], [:none col] (col is null),
          [:and …], [:or …]

   Who is asking is read from two SQL functions the gates install (piece 4):
   doorman.find_scopes_holding(kind, action) and
   doorman.find_asking_person(). Each is wrapped in (SELECT …), so
   Postgres asks once per query, not once per row; the array one is cast
   back to uuid[], or ANY would read it as a subquery of arrays."
  (:require
   [clojure.string :as str]
   [cellar.api :as cellar]))

(defn- quote-text [s] (str "'" (str/replace (str s) "'" "''") "'"))

(defn- turn-value-into-sql [value]
  (cond (string? value)  (quote-text value)
        (keyword? value) (quote-text (subs (str value) 1))
        (uuid? value)    (str (quote-text value) "::uuid")
        :else            (str value)))

(defn find-scope-kind-of-column
  "The scope kind a column names: the kind whose :table its reference points at."
  [{:keys [columns]} column-key tables-by-key scope-kinds]
  (let [references  (some (fn [[k _ options]] (when (= k column-key) (:references options))) columns)
        target      (some (fn [[table-key {table-columns :columns}]]
                            (when (some (fn [[k _ options]] (and (= k references) (:primary-key? options))) table-columns)
                              table-key))
                          tables-by-key)]
    (or (some (fn [[kind {:keys [table]}]] (when (= table target) kind)) scope-kinds)
        (throw (ex-info (str column-key " names no scope: it must reference the table of a scope kind")
                        {:type :doorman/column-names-no-scope :column column-key})))))

(defn- turn-to-into-sql [to action {:keys [table tables-by-key scope-kinds]}]
  (cond
    (= :anyone to)    "true"
    (= :signed-in to) "(SELECT doorman.find_asking_person()) IS NOT NULL"
    (= :member (first to))
    (let [kind (find-scope-kind-of-column table (second to) tables-by-key scope-kinds)]
      (str (cellar/turn-key-into-column-name (second to))
           " = ANY ((SELECT doorman.find_scopes_holding(" (quote-text (name kind)) ", "
           (quote-text (subs (str action) 1)) "))::uuid[])"))
    (= :me (first to))
    (str (cellar/turn-key-into-column-name (second to)) " = (SELECT doorman.find_asking_person())")
    :else (throw (ex-info (str "Not a :to doorman knows: " (pr-str to)) {:type :doorman/bad-to :to to}))))

(defn- turn-when-into-sql [[word & more :as condition]]
  (case word
    :=    (str (cellar/turn-key-into-column-name (first more)) " = " (turn-value-into-sql (second more)))
    :not= (str (cellar/turn-key-into-column-name (first more)) " <> " (turn-value-into-sql (second more)))
    :in   (str (cellar/turn-key-into-column-name (first more)) " IN (" (str/join ", " (map turn-value-into-sql (second more))) ")")
    :none (str (cellar/turn-key-into-column-name (first more)) " IS NULL")
    :and  (str "(" (str/join " AND " (map turn-when-into-sql more)) ")")
    :or   (str "(" (str/join " OR " (map turn-when-into-sql more)) ")")
    (throw (ex-info (str "Not a :when doorman knows: " (pr-str condition)) {:type :doorman/bad-when :when condition}))))

(defn- turn-sentence-into-sql [sentence action place]
  (str "(" (turn-to-into-sql (:to sentence) action place)
       (when-let [condition (:when sentence)] (str " AND " (turn-when-into-sql condition)))
       ")"))

(defn- find-allowed-actions [{:keys [allow]}]
  (if (set? allow) allow #{allow}))

(defn make-condition-for-action
  "The SQL condition for one action on a table: its sentences joined by OR,
   or \"false\" when no sentence allows it."
  [action place]
  (let [sentences (filter #(contains? (find-allowed-actions %) action) (get-in place [:table :access]))]
    (if (seq sentences)
      (str/join " OR " (map #(turn-sentence-into-sql % action place) sentences))
      "false")))
