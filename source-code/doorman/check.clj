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
   person can have its action at all: held in some scope, or open to them
   by a sentence; the gates decide which rows."
  (:require
   [clojure.string :as str]
   [cellar.api :as cellar]
   [doorman.sentences :as sentences]
   [mailroom.api :as mailroom]
   [jsonista.core :as json]))

(def ^:private not-found {:error "Not found."})

(def ^:private not-allowed {:error "Not allowed."})

(def ^:private set-asking (cellar/make-query-from-sql-file "doorman/set_asking.sql"))

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

(defn- find-reach-of-action
  "How an action can be had without a membership, from the sentences that
   allow it: :anyone (everyone), :signed-in (anyone signed in: :signed-in
   or [:me column] sentences), or nil (only through a scope's role)."
  [tables action]
  (let [{:keys [anyone signed-in]} (sentences/find-open-actions tables)]
    (cond (contains? anyone action)    :anyone
          (contains? signed-in action) :signed-in)))

(defn- may-person-have-action?
  "For a handler without a :record: whether the person can have its action
   at all: held in some scope, or open to them by a sentence. Which rows,
   the gates decide."
  [reach action context]
  (or (= :anyone reach)
      (and (= :signed-in reach) (some? (:doorman/person-id context)))
      (some #(contains? % action) (vals (:doorman/actions context)))))

(defn- find-reach-of-record
  "{:allowed bool :visible bool} for the record the message names."
  [query record context]
  (cellar/find-one-row (:doorman/transaction context) query
                         {:record/id (get-in context [:mailroom/message-data (second record)])}))

(defn- make-check-step [{:keys [tables] :as setup} {:keys [record action] :as handler}]
  (let [query (when record (make-record-query setup handler))
        reach (find-reach-of-action tables action)]
    {:name  :doorman/check
     :enter (fn [context]
              (if query
                (let [{:keys [allowed visible]} (find-reach-of-record query record context)]
                  (cond allowed context
                        visible (mailroom/add-answer-to-context context not-allowed)
                        :else   (mailroom/add-answer-to-context context not-found)))
                (if (may-person-have-action? reach action context)
                  context
                  (mailroom/add-answer-to-context context not-found))))}))

(defn- turn-actions-into-asking
  "Who is asking, as the JSON text access's SQL functions read from
   doorman.asking."
  [person-id actions-by-scope]
  (json/write-value-as-string
   {:person  (some-> person-id str)
    :holding (reduce (fn [holding [[kind scope-id] actions]]
                       (reduce #(update %1 (str (name kind) " " (subs (str %2) 1)) (fnil conj []) (str scope-id))
                               holding actions))
                     {} actions-by-scope)}))

(defn- does-action-only-read?
  "An action that does nothing but :read runs in a read-only transaction:
   Postgres refuses any write its steps try."
  [{:keys [scope-kinds]} action]
  (= #{:read} (some #(get-in % [:actions action]) (vals scope-kinds))))

(defn- make-run-steps-in [{:keys [pool find-session-step list-actions] :as setup} {:keys [action]}]
  (fn [run context]
    (let [context (mailroom/run-steps-until-answered [find-session-step] context)]
      (if (mailroom/does-context-have-answer? context)
        context
        (cellar/run-in-transaction! pool {:read-only? (does-action-only-read? setup action)}
          (fn [transaction]
            (let [person-id (:doorman/person-id context)
                  actions   (if person-id (list-actions transaction person-id) {})]
              (cellar/find-one-row transaction set-asking {:asking (turn-actions-into-asking person-id actions)})
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
