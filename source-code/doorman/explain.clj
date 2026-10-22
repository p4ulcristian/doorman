(ns doorman.explain
  "Why a message would be refused, as plain data, for a REPL: the action
   it needs, what the asker holds where the record is, each sentence that
   gives the action and which of its parts holds, and the denies.

   It reads in one read-only transaction that tells Postgres who is asking
   (as the check does) and reads past the gates (doorman.passing), so it
   sees the record even when the asker can't; each part is the same SQL
   the check and the gates run."
  (:require
   [clojure.string :as str]
   [cellar.api :as cellar]
   [doorman.asking :as asking]
   [doorman.check :as check]
   [doorman.conditions :as conditions]
   [doorman.memberships :as memberships]
   [doorman.scope-kinds :as scope-kinds]
   [doorman.sentences :as sentences]))

(defn- ask-one [transaction what sql params]
  (cellar/find-one-row transaction (cellar/make-query-from-sql-text what sql) params))

(defn- find-row [transaction table id]
  (let [pk (first (sentences/find-primary-key-columns table))]
    (ask-one transaction (str "the row of " (:table table))
             (str "SELECT * FROM " (cellar/turn-table-key-into-table-name (:table table))
                  " WHERE " (sentences/column-name pk) " = :explain/id")
             {:explain/id id})))

(defn- does-sql-hold? [transaction table id sql]
  (let [pk (first (sentences/find-primary-key-columns table))]
    (boolean (:explain/holds (ask-one transaction "whether a part holds"
                                      (str "SELECT (" sql ") IS TRUE AS explain__holds FROM "
                                           (cellar/turn-table-key-into-table-name (:table table))
                                           " WHERE " (sentences/column-name pk) " = :explain/id")
                                      {:explain/id id})))))

(defn- find-row-scopes
  "The scopes the row's columns name: [{:column :scope-kind :scope-id}]."
  [table row {:keys [tables-by-key scope-kinds]}]
  (vec (for [[column] (:columns table)
             :let [kind (sentences/find-kind-named-by-column table column tables-by-key scope-kinds)]
             :when kind]
         {:column column :scope-kind kind :scope-id (get row column)})))

(defn- explain-to
  "What a :to word says of the row, and whether it holds."
  [to action table row actions-by-scope {:keys [tables-by-key scope-kinds]} holds?]
  (cond
    (#{:anyone :signed-in} to) {:says to :holds? holds?}
    (= :me (first to))
    {:says to :holds? holds? :column (second to) :value (get row (second to))
     :asker-is (if (= (second to) (sentences/find-visitor-column table)) :visitor :person)}
    (= :member (first to))
    (let [[_ x] to
          needs (sentences/find-held-action to action)]
      (if (sentences/is-column-word? x)
        (let [kind  (sentences/find-kind-named-by-column table x tables-by-key scope-kinds)
              scope [kind (get row x)]]
          {:says to :holds? holds? :scope-kind kind :scope-id (get row x) :needs needs
           :held-there? (contains? (get actions-by-scope scope) needs)})
        {:says to :holds? holds? :scope-kind x :needs needs
         :held-there? (boolean (some (fn [[[kind] actions]] (and (= kind x) (contains? actions needs))) actions-by-scope))}))))

(defn- explain-sentence [transaction table id row sentence action actions-by-scope place]
  (let [place     (assoc place :table table)
        {:keys [to via] condition :when} sentence
        part-sql  (if via (sentences/turn-via-into-sql via place) (sentences/turn-who-into-sql to action table place))]
    (cond-> {:table    (:table table)
             :sentence sentence
             :holds?   (does-sql-hold? transaction table id (sentences/turn-sentence-into-sql sentence action place))}
      to        (assoc :to (explain-to to action table row actions-by-scope place (does-sql-hold? transaction table id part-sql)))
      via       (assoc :via {:says via :holds? (does-sql-hold? transaction table id part-sql)})
      condition (assoc :when {:says condition :holds? (does-sql-hold? transaction table id (sentences/turn-when-into-sql condition))}))))

(defn- find-denied-ids [transaction deny]
  (set (map :scope/id (cellar/find-rows transaction (cellar/make-query-from-sql-text
                                                     "the scopes in a denied state"
                                                     (str "SELECT " (conditions/find-denied-function-name deny) "() AS scope__id"))
                                        {}))))

(defn- explain-denies
  "Each deny of the action: whether it says no here (a session flag the
   asker's session has; a scope of the row in the denied state)."
  [transaction scope-kinds action session-flags row-scopes]
  (vec (for [{:keys [kind deny session] :as read-deny} (scope-kinds/find-denies scope-kinds)
             :when (scope-kinds/does-deny-apply? read-deny action nil)]
         (if session
           {:scope-kind kind :deny deny :says-no? (contains? session-flags session)}
           (let [denied (find-denied-ids transaction read-deny)
                 hits   (filter #(and (= kind (:scope-kind %)) (contains? denied (:scope-id %))) row-scopes)]
             {:scope-kind kind :deny deny :says-no? (boolean (seq hits)) :scope-ids (mapv :scope-id hits)})))))

(defn- explain-memberships
  "The asker's role in each scope the row names and in those above it."
  [transaction person-id row-scopes]
  (when person-id
    (vec (distinct (for [{:keys [scope-kind scope-id]} row-scopes :when scope-id
                         membership (memberships/list-memberships-above transaction [scope-kind scope-id] person-id)
                         :when (:role membership)]
                     (dissoc membership :depth))))))

(defn- find-answer [transaction table id action conditions scope-kinds]
  (let [action-sql (get-in conditions [(:table table) :actions action])
        reads      (for [[other sql] (get-in conditions [(:table table) :actions])
                         :when (contains? (scope-kinds/find-what-action-does scope-kinds other) :read)]
                     sql)]
    (cond (does-sql-hold? transaction table id action-sql) :allowed
          (and (seq reads) (does-sql-hold? transaction table id (str/join " OR " (map #(str "(" % ")") reads)))) :not-allowed
          :else :not-found)))

(defn- explain-record [transaction {:keys [tables scope-kinds]} conditions asker action [table-key id-key] params actions-by-scope]
  (let [place      {:tables-by-key (into {} (map (juxt :table identity)) tables) :scope-kinds scope-kinds}
        table      (get-in place [:tables-by-key table-key])
        id         (get params id-key)
        row        (when id (find-row transaction table id))
        row-scopes (when row (find-row-scopes table row place))]
    {:record            {:table table-key :id id :exists? (some? row) :scopes (or row-scopes [])}
     :answer            (if row (find-answer transaction table id action conditions scope-kinds) :not-found)
     :memberships       (explain-memberships transaction (:person-id asker) row-scopes)
     :actions-in-scopes (vec (for [{:keys [scope-kind scope-id]} row-scopes :when scope-id]
                               {:scope-kind scope-kind :scope-id scope-id
                                :actions (vec (sort-by str (get actions-by-scope [scope-kind scope-id])))}))
     :sentences         (if row
                          (mapv #(explain-sentence transaction table id row % action actions-by-scope place)
                                (sentences/find-sentences-of-action table action))
                          [])
     :denies            (explain-denies transaction scope-kinds action (:session-flags asker) (or row-scopes []))}))

(defn- explain-no-record [transaction {:keys [tables scope-kinds]} asker action actions-by-scope]
  (let [tables-by-key (into {} (map (juxt :table identity)) tables)
        context       {:doorman/person-id (:person-id asker) :doorman/visitor-id (:visitor-id asker)
                       :doorman/actions actions-by-scope}
        explained     (vec (for [table tables
                                 sentence (sentences/find-sentences-of-action table action)
                                 :let [way (sentences/find-way-of-sentence sentence action table tables-by-key)]]
                             {:table (:table table) :sentence sentence :way way :holds? (check/does-way-hold? way context)}))
        flag-deny     (check/find-flag-deny scope-kinds action (:session-flags asker))]
    {:record            nil
     :answer            (cond (not-any? :holds? explained) :not-found
                              flag-deny                   :not-allowed
                              :else                       :allowed)
     :memberships       nil
     :actions-in-scopes (vec (for [[[kind id] actions] (sort-by (comp str key) actions-by-scope)]
                               {:scope-kind kind :scope-id id :actions (vec (sort-by str actions))}))
     :sentences         explained
     :denies            (explain-denies transaction scope-kinds action (:session-flags asker) [])}))

(defn explain-refusal
  "The explanation, plain data; the options and the asker checked already."
  [{:keys [pool handlers tables scope-kinds] :as options} asker handler-name params]
  (let [{:keys [action record] :as handler} (get handlers handler-name)]
    (if-not handler
      {:handler handler-name :answer :no-such-handler}
      (let [conditions (conditions/make-sql-conditions tables scope-kinds)]
        (cellar/run-in-transaction! pool {:read-only? true}
                                    (fn [transaction]
                                      (let [actions-by-scope (if (:person-id asker)
                                                               (memberships/list-actions-of-person transaction scope-kinds (:person-id asker))
                                                               {})]
                                        (asking/set-asking! transaction asker actions-by-scope)
                                        (asking/set-passing! transaction)
                                        (merge {:handler handler-name
                                                :action  action
                                                :writes? (scope-kinds/does-action-write? scope-kinds action)
                                                :asker   asker}
                                               (if record
                                                 (explain-record transaction options conditions asker action record params actions-by-scope)
                                                 (explain-no-record transaction options asker action actions-by-scope))))))))))
