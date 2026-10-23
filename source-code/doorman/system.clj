(ns doorman.system
  "A named system actor: code of the app's that runs with nobody asking (a
   provider's webhook), held by the gates like anyone, allowed exactly what
   the sentences give it by name:

     {:allow :email-addresses/mark-bounced :to [:system :postbox]}

   run-as-system! runs a function in one transaction that tells Postgres
   the actor is asking (doorman.asking {\"system\": \"postbox\"}, read by
   doorman.find_asking_system()); no person, no visitor, no scope held,
   no session flag. It reads and writes the rows its sentences give it,
   the :anyone ones, and the tables without sentences; nothing else. No
   role passes the gates for it: the app's own role, which owns nothing,
   runs it. find-system-leaks proves it for the app's tests."
  (:require
   [cellar.api :as cellar]
   [doorman.asking :as asking]
   [doorman.sentences :as sentences]))

(defn check-system-name
  "Returns the name; throws for anything but a keyword without a
   namespace, like :postbox."
  [system-name]
  (when-not (and (simple-keyword? system-name) (re-matches #"[a-z0-9-]+" (name system-name)))
    (throw (ex-info (str "A system actor is named by a keyword without a namespace, like :postbox, not " (pr-str system-name))
                    {:type :doorman/bad-system-name :system system-name})))
  system-name)

(defn run-as-system!
  "Runs (f transaction) in one transaction of its own, as the named system
   actor. A transaction handed in is refused: who asks would change under
   its other statements."
  [pool system-name {:keys [read-only?]} f]
  (check-system-name system-name)
  (when (cellar/is-transaction? pool)
    (throw (ex-info "run-as-system! runs in a transaction of its own: hand it the pool"
                    {:type :doorman/system-in-a-transaction :system system-name})))
  (cellar/run-in-transaction! pool {:read-only? read-only?}
                              (fn [transaction]
                                (asking/set-system! transaction system-name)
                                (f transaction))))

(defn- is-foreign-key-violation?
  "A delete that reached the row but was refused because others point at
   it reached it all the same."
  [^Throwable e]
  (loop [t e]
    (cond (nil? t) false
          (and (instance? java.sql.SQLException t) (= "23503" (.getSQLState ^java.sql.SQLException t))) true
          :else (recur (.getCause t)))))

(defn- try-and-undo!
  "How many rows (statement-text) reached as the system actor, the
   transaction undone either way."
  [pool system-name statement-text id]
  (let [undo   (Object.)
        result (atom 0)]
    (try
      (run-as-system! pool system-name {:read-only? false}
                      (fn [transaction]
                        (let [query (cellar/make-query-from-sql-text "a system leak check" statement-text)]
                          (reset! result (if (.startsWith ^String statement-text "SELECT")
                                           (count (cellar/find-rows transaction query {:leak/id id}))
                                           (cellar/run-statement! transaction query {:leak/id id}))))
                        (throw (ex-info "undo" {:undo undo}))))
      (catch clojure.lang.ExceptionInfo e
        (when-not (identical? undo (:undo (ex-data e))) (throw e)))
      (catch Exception e
        (if (is-foreign-key-violation? e) (reset! result 1) (throw e))))
    @result))

(defn find-system-leaks
  "Every row of foreign-ids ({table-key [id …]}, rows the actor must not
   reach) the named system actor reads, changes or deletes:
   [{:system :table :id :reached #{:read :update :delete}}]. Each try runs
   in its own transaction, undone. A table needs a one-column primary key."
  [pool tables system-name foreign-ids]
  (check-system-name system-name)
  (let [tables-by-key (into {} (map (juxt :table identity)) tables)]
    (vec (for [[table-key ids] foreign-ids
               :let [table (or (get tables-by-key table-key)
                               (throw (ex-info (str table-key " is none of the tables") {:type :doorman/unknown-table :table table-key})))
                     pk    (sentences/find-primary-key-columns table)
                     _     (when-not (= 1 (count pk))
                             (throw (ex-info (str table-key " needs a one-column primary key for the system leak test")
                                             {:type :doorman/unknown-table :table table-key})))
                     name  (cellar/turn-table-key-into-table-name table-key)
                     id    (sentences/column-name (first pk))
                     where (str " WHERE " id " = :leak/id")]
               row-id ids
               :let [reached (set (for [[operation text] [[:read (str "SELECT 1 AS leak__found FROM " name where)]
                                                          [:update (str "UPDATE " name " SET " id " = " id where)]
                                                          [:delete (str "DELETE FROM " name where)]]
                                        :when (pos? (try-and-undo! pool system-name text row-id))]
                                    operation))]
               :when (seq reached)]
           {:system system-name :table table-key :id row-id :reached reached}))))
