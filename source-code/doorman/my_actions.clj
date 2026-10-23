(ns doorman.my-actions
  "\"What may I do?\", for the browser: a handler answering the asker's
   actions, every scope they may do something in with those actions, and
   the actions open to them in every scope:

     [{:scope-kind nil   :scope-id nil      :actions [:posts/read :profile/edit]}  ; in every scope
      {:scope-kind :shop :scope-id #uuid \"…\" :actions [:orders/edit :orders/see]}]

   A scope's actions: those the person's roles hold there (and in the
   scopes above it); a sentence's action whose [:member column action]
   needs another action held there; and an action a :via gives through
   related rows that name the scope (a partner's agreement with a shop).

   The entry of nil kind and id holds the actions no scope lists: :anyone
   sentences' always; for someone signed in, :signed-in and [:me column]
   sentences'; for a visitor, [:me <visitor column>] sentences'; and a
   [:member <kind of one scope> action] or a :via's [:member …] whose
   related rows name no scope of the row, when the person holds that
   action somewhere. Such an action is listed for every scope, and the
   row decides. Without such actions the nil entry is left out.

   A session flag a deny names takes its actions out everywhere; a
   scope's state, out of that scope. Scopes come by kind, then id;
   actions sorted.

   It runs as the asker: the find-session step first (the Origin checked,
   the person or visitor), then one read-only transaction that tells
   Postgres who is asking (every action, in every scope holding it), in which the step reads doorman's tables and
   the vias' and denies' functions. It is a hint for screens: the check
   and the gates decide."
  (:require
   [cellar.api :as cellar]
   [doorman.asking :as asking]
   [doorman.conditions :as conditions]
   [doorman.scope-kinds :as scope-kinds]
   [doorman.shapes :as shapes]
   [doorman.sentences :as sentences]
   [bridge.api :as bridge]))

(def answer-shape
  "The answer, doorman.shapes's, the same on both sides."
  shapes/my-actions-answer-shape)

(defn- sort-actions [actions]
  (vec (sort-by #(subs (str %) 1) actions)))

(defn make-answer
  "The answer, from the actions by scope ({[:shop id] #{…}}) and the
   actions open everywhere (#{…})."
  [actions-by-scope everywhere]
  (into (if (seq everywhere)
          [{:scope-kind nil :scope-id nil :actions (sort-actions everywhere)}]
          [])
        (for [[[kind id] actions] (sort-by (fn [[[kind id]]] [(name kind) (str id)]) actions-by-scope)
              :when (seq actions)]
          {:scope-kind kind :scope-id id :actions (sort-actions actions)})))

(defn- holds-anywhere? [actions-by-scope action]
  (boolean (some #(contains? % action) (vals actions-by-scope))))

(defn- find-plan
  "What the answer is made of, read once from the sentences: the open
   actions; [:member column held] sentences giving another action than
   the one held; vias whose related rows name the row's scope; and the
   actions open to whoever holds an action somewhere."
  [tables scope-kinds]
  (let [tables-by-key (into {} (map (juxt :table identity)) tables)
        sentences     (for [{:keys [access] :as table} tables :when (sequential? access)
                            sentence access
                            action (sentences/find-allowed-actions sentence)]
                        [table sentence action])]
    {:open      (sentences/find-open-actions tables)
     :held-for  (distinct (for [[table {:keys [to]} action] sentences
                                :when (and (vector? to) (= :member (first to)) (sentences/is-column-word? (second to)))
                                :let [held (sentences/find-held-action to action)]
                                :when (not= held action)]
                            {:action action :held held
                             :kind   (sentences/find-kind-of-member-column table (second to) tables-by-key scope-kinds)}))
     :via-scope (distinct (for [[table {:keys [via]} action] sentences :when via
                                [i [mine _]] (map-indexed vector (sentences/find-matched-pairs via))
                                :let [kind (sentences/find-kind-named-by-column table mine tables-by-key scope-kinds)]
                                :when kind]
                            {:action action :kind kind :function (sentences/find-via-function-name via tables-by-key)
                             :out (str "via_" (inc i))}))
     :held-anywhere (distinct (for [[table {:keys [to via]} action] sentences
                                    :let [to (if via (:to via) to)]
                                    :when (and (vector? to) (= :member (first to))
                                               (or (not (sentences/is-column-word? (second to)))
                                                   (and via (not-any? #(sentences/find-kind-named-by-column table % tables-by-key scope-kinds)
                                                                      (keys (:match via))))))]
                                {:action action :needed (sentences/find-held-action to action)}))}))

(defn- find-via-scopes [transaction {:keys [function out]}]
  (map :scope/id (cellar/find-rows transaction (cellar/make-query-from-sql-text
                                                (str "the scopes " function " names")
                                                (str "SELECT DISTINCT " out " AS scope__id FROM " function "()"))
                                   {})))

(defn- find-denied-scopes [transaction deny]
  (set (map :scope/id (cellar/find-rows transaction (cellar/make-query-from-sql-text
                                                     "the scopes in a denied state"
                                                     (str "SELECT " (conditions/find-denied-function-name deny) "() AS scope__id"))
                                        {}))))

(defn- add-held-for [actions-by-scope held-for]
  (reduce (fn [result {:keys [action held kind]}]
            (reduce (fn [result [[scope-kind _ :as scope] actions]]
                      (if (and (= kind scope-kind) (contains? actions held))
                        (update result scope (fnil conj #{}) action)
                        result))
                    result actions-by-scope))
          actions-by-scope held-for))

(defn- take-out-denied [actions-by-scope everywhere scope-kinds session-flags denied-by-state]
  (let [flagged   (set (for [deny (scope-kinds/find-denies scope-kinds)
                             :when (and (:session deny) (contains? session-flags (:session deny)))
                             action (:actions deny)]
                         action))
        by-state  (fn [[kind id] actions]
                    (reduce (fn [actions [{deny-kind :kind :as deny} denied-ids]]
                              (if (and (= kind deny-kind) (contains? denied-ids id))
                                (remove (:actions deny) actions)
                                actions))
                            actions denied-by-state))]
    [(into {} (for [[scope actions] actions-by-scope]
                [scope (set (remove flagged (by-state scope actions)))]))
     (set (remove flagged everywhere))]))

(defn- make-answer-step [tables scope-kinds]
  (let [{:keys [open held-for via-scope held-anywhere]} (find-plan tables scope-kinds)
        state-denies (filter :when (scope-kinds/find-denies scope-kinds))]
    {:name  :doorman/answer-my-actions
     :enter (fn [{:doorman/keys [person-id visitor-id session-flags transaction] :as context}]
              (let [held     (asking/set-asking-with-every-action! transaction scope-kinds
                                                                       {:person-id person-id :visitor-id visitor-id :session-flags session-flags})
                    by-scope (-> (add-held-for held held-for)
                                 (as-> by-scope
                                       (reduce (fn [by-scope {:keys [action kind] :as via}]
                                                 (reduce #(update %1 [kind %2] (fnil conj #{}) action)
                                                         by-scope (find-via-scopes transaction via)))
                                               by-scope via-scope)))
                    open-now (cond-> (:anyone open)
                               person-id                        (into (:signed-in open))
                               (and (nil? person-id) visitor-id) (into (:visitor open))
                               true                             (into (for [{:keys [action needed]} held-anywhere
                                                                            :when (holds-anywhere? held needed)]
                                                                        action)))
                    denied   (for [deny state-denies] [deny (find-denied-scopes transaction deny)])
                    [by-scope open-now] (take-out-denied by-scope open-now scope-kinds session-flags denied)]
                (bridge/add-answer-to-context context (make-answer by-scope open-now))))}))

(defn- make-run-steps-in
  "The session step, then the steps in one read-only transaction at
   :doorman/transaction."
  [{:keys [pool find-session-step]}]
  (fn [run context]
    (let [context (bridge/run-steps-until-answered [find-session-step] context)]
      (if (bridge/does-context-have-answer? context)
        context
        (cellar/run-in-transaction! pool {:read-only? true :log (:bridge/log context)}
                                    (fn [transaction]
                                      (run (assoc context :doorman/transaction transaction))))))))

(defn make-my-actions-handler
  "The handler, bridge's keys written out, doorman's reasons its
   :refusals (the find-session step's); the options checked already."
  [{:keys [tables scope-kinds rate-limit] :as options}]
  (conditions/check-sentences tables scope-kinds)
  {:params-shape [:map {:closed true}]
   :answer-shape answer-shape
   :refusals     shapes/refusals
   :rate-limit   rate-limit
   :run-steps-in (make-run-steps-in options)
   :steps        [(make-answer-step tables scope-kinds)]})
