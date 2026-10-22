(ns doorman.check
  "The check: every handler declares its :action (and its shapes), and
   doorman puts the check in front of its steps; the app doesn't write it.

     {:orders/one {:action       :orders/see
                   :record       [:orders :order/id]     ; the row the message names, by this param
                   :params-shape [:map [:order/id :uuid]]
                   :answer-shape …
                   :rate-limit   nil
                   :run-steps-in nil                       ; doorman's transaction
                   :steps        [find-order add-order-answer]}}

   Each message then runs: find the session; in one transaction (read-only
   when the action only reads), tell
   Postgres who is asking (doorman.asking); the check: may this person do
   the action on the record? Not, and they can't see it either: \"Not
   found.\" (nothing tells them it exists); not, but they can see it: \"Not
   allowed.\"; either way no step runs. Yes: the
   handler's steps, with :doorman/transaction in the context. A
   handler without a :record (a list, a new row, an email) runs only if the
   asker can have its action at all: open to them by a sentence (anyone,
   anyone signed in, a visitor), or held somewhere (the action a [:member
   …] needs); otherwise \"Not found.\". A session flag a deny names
   answers \"Not allowed.\". The gates decide which rows."
  (:require
   [clojure.string :as str]
   [cellar.api :as cellar]
   [doorman.asking :as asking]
   [doorman.scope-kinds :as scope-kinds]
   [doorman.sentences :as sentences]
   [mailroom.api :as mailroom]))

(def ^:private not-found {:error "Not found."})

(def ^:private not-allowed {:error "Not allowed."})

(defn- find-problems-of-handler
  "Doorman's own keys, and mailroom's written out: :steps, :rate-limit
   (nil for none) and :run-steps-in, nil, since doorman runs the steps in
   its transaction and an app's own would be replaced unseen. Both shapes
   must be real ones: the leak test makes params from :params-shape, and
   an answer leaves only as :answer-shape allows."
  [conditions [handler-name {:keys [action record params-shape answer-shape run-steps-in] :as handler}]]
  (concat
   (when-not action [(str handler-name " has no :action")])
   (when-not params-shape [(str handler-name " has no :params-shape")])
   (when-not answer-shape [(str handler-name " has no :answer-shape")])
   (for [k [:steps :rate-limit :run-steps-in]
         :when (not (contains? handler k))]
     (str handler-name " has no " k " (nil written out where nothing applies)"))
   (when run-steps-in [(str handler-name " has its own :run-steps-in; doorman runs the steps in its transaction, so it must be nil")])
   (when (and record (not (get-in conditions [(first record) :actions action])))
     [(str handler-name ": no sentence on " (first record) " allows " action)])))

(defn check-handlers
  "Throws with every problem of every handler at once."
  [handlers conditions]
  (let [problems (mapcat #(find-problems-of-handler conditions %) handlers)]
    (when (seq problems)
      (throw (ex-info (str "The handlers have problems:\n  " (str/join "\n  " problems))
                      {:type :doorman/bad-handlers :problems (vec problems)})))))

(defn- find-primary-key-column [tables table-key]
  (some (fn [{:keys [table columns]}]
          (when (= table table-key)
            (some (fn [[k _ options]] (when (:primary-key? options) (cellar/turn-key-into-column-name k))) columns)))
        tables))

(defn- make-record-query
  "Whether the person may do the action on the record, and whether they
   may see it at all: one may see an order and not change it."
  [{:keys [tables conditions]} {:keys [action] [table-key] :record}]
  (let [row (str "SELECT 1 FROM " (cellar/turn-table-key-into-table-name table-key)
                 " WHERE " (find-primary-key-column tables table-key) " = :record/id")]
    (cellar/make-query-from-sql-text
     (str "may " action " on " table-key)
     (str "SELECT EXISTS (" row " AND (" (get-in conditions [table-key :actions action]) ")) AS allowed, "
          "EXISTS (" row " AND (" (get-in conditions [table-key :operations :read]) ")) AS visible"))))

(defn find-flag-deny
  "The deny a session's flags say no to an action with, or nil."
  [scope-kinds action session-flags]
  (first (filter #(and (:session %) (contains? session-flags (:session %))
                       (scope-kinds/does-deny-apply? % action nil))
                 (scope-kinds/find-denies scope-kinds))))

(defn does-way-hold?
  "Whether one way of having an action (sentences/find-way-of-sentence)
   holds for the asker: open to anyone, to anyone signed in, to a
   visitor, or to someone holding an action somewhere."
  [way {:doorman/keys [person-id visitor-id actions]}]
  (cond (= :anyone way)    true
        (= :signed-in way) (some? person-id)
        (= :visitor way)   (and (nil? person-id) (some? visitor-id))
        (vector? way)      (boolean (some #(contains? % (second way)) (vals actions)))
        :else              false))

(defn- may-person-have-action?
  "For a handler without a :record: whether the asker can have its action
   at all, by one of its ways. Which rows, the gates decide."
  [ways context]
  (boolean (some #(does-way-hold? % context) ways)))

(defn- find-reach-of-record
  "{:allowed bool :visible bool} for the record the message names."
  [query record context]
  (cellar/find-one-row (:doorman/transaction context) query
                       {:record/id (get-in context [:mailroom/message-data (second record)])}))

(defn- make-check-step [{:keys [tables scope-kinds] :as setup} {:keys [record action] :as handler}]
  (let [query (when record (make-record-query setup handler))
        ways  (get (sentences/find-ways-of-actions tables) action #{})]
    {:name  :doorman/check
     :enter (fn [context]
              (if query
                (let [{:keys [allowed visible]} (find-reach-of-record query record context)]
                  (cond allowed context
                        visible (mailroom/add-answer-to-context context not-allowed)
                        :else   (mailroom/add-answer-to-context context not-found)))
                (cond (not (may-person-have-action? ways context))
                      (mailroom/add-answer-to-context context not-found)
                      (find-flag-deny scope-kinds action (:doorman/session-flags context))
                      (mailroom/add-answer-to-context context not-allowed)
                      :else context)))}))

(defn- does-action-only-read?
  "An action that does nothing but :read runs in a read-only transaction:
   Postgres refuses any write its steps try."
  [{:keys [scope-kinds]} action]
  (scope-kinds/does-action-only-read? scope-kinds action))

(defn- make-run-steps-in [{:keys [pool find-session-step list-actions] :as setup} {:keys [action]}]
  (fn [run context]
    (let [context (mailroom/run-steps-until-answered [find-session-step] context)]
      (if (mailroom/does-context-have-answer? context)
        context
        (cellar/run-in-transaction! pool {:read-only? (does-action-only-read? setup action)}
                                    (fn [transaction]
                                      (let [{:doorman/keys [person-id visitor-id session-flags]} context
                                            actions (if person-id (list-actions transaction person-id) {})]
                                        (asking/set-asking! transaction {:person-id person-id :visitor-id visitor-id :session-flags session-flags} actions)
                                        (run (assoc context
                                                    :doorman/transaction transaction
                                                    :doorman/actions actions)))))))))

(defn put-access-into-handlers
  "Every handler checked, then given the check in front of its steps and the
   running of them in a transaction that knows who is asking."
  [handlers setup]
  (check-handlers handlers (:conditions setup))
  (update-vals handlers
               (fn [handler]
                 (-> handler
                     (update :steps #(into [(make-check-step setup handler)] %))
                     (assoc :run-steps-in (make-run-steps-in setup handler))))))
