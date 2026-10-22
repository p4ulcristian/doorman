(ns doorman.my-actions
  "\"What may I do?\", for the browser: a handler answering the asking
   person's actions, every scope they are in with the actions their role
   there allows, and the actions open to them in every scope:

     [{:scope-kind nil   :scope-id nil      :actions [:posts/read :profile/edit]}  ; in every scope
      {:scope-kind :shop :scope-id #uuid \"…\" :actions [:orders/edit :orders/see]}]

   The entry of nil kind and id holds the actions no membership is needed
   for: :anyone sentences' always, and, for someone signed in, :signed-in
   and [:me column] sentences' too. A [:me column] action is had on the
   rows that name the person, which no scope lists; it is in the nil entry,
   so \"may I?\" says yes in any scope and the row decides. Without such
   actions the nil entry is left out. Scopes come by kind, then id; actions
   sorted.

   It runs as the asking person: the find-session step first (the Origin
   checked, :doorman/person-id), then one read-only transaction, in which
   the step reads the memberships, so doorbell's notes name doorman's
   tables. It is a hint for screens: the check and the gates decide."
  (:require
   [cellar.api :as cellar]
   [doorman.memberships :as memberships]
   [doorman.sentences :as sentences]
   [mailroom.api :as mailroom]))

(def answer-shape
  "The answer, by wire: scope kinds and actions travel as their text and
   come back keywords."
  [:vector [:map {:closed true}
            [:scope-kind [:maybe :keyword]]
            [:scope-id [:maybe :uuid]]
            [:actions [:vector :keyword]]]])

(defn- sort-actions [actions]
  (vec (sort-by #(subs (str %) 1) actions)))

(defn make-answer
  "The answer, from the person (nil for nobody signed in), their actions
   by scope ({[:shop id] #{…}}) and the open actions
   (sentences/find-open-actions)."
  [person-id actions-by-scope {:keys [anyone signed-in]}]
  (let [everywhere (cond-> anyone person-id (into signed-in))]
    (into (if (seq everywhere)
            [{:scope-kind nil :scope-id nil :actions (sort-actions everywhere)}]
            [])
          (for [[[kind id] actions] (sort-by (fn [[[kind id]]] [(name kind) (str id)]) actions-by-scope)]
            {:scope-kind kind :scope-id id :actions (sort-actions actions)}))))

(defn- make-answer-step [scope-kinds open-actions]
  {:name  :doorman/answer-my-actions
   :enter (fn [{:doorman/keys [person-id transaction] :as context}]
            (mailroom/add-answer-to-context
             context
             (make-answer person-id
                          (if person-id (memberships/list-actions-of-person transaction scope-kinds person-id) {})
                          open-actions)))})

(defn- make-run-steps-in
  "The session step, then the steps in one read-only transaction at
   :doorman/transaction. Postgres isn't told who is asking: the step reads
   doorman's own tables, which have no gates."
  [{:keys [pool find-session-step]}]
  (fn [run context]
    (let [context (mailroom/run-steps-until-answered [find-session-step] context)]
      (if (mailroom/does-context-have-answer? context)
        context
        (cellar/run-in-transaction! pool {:read-only? true}
          (fn [transaction]
            (run (assoc context :doorman/transaction transaction))))))))

(defn make-my-actions-handler
  "The handler, mailroom's keys written out; the options checked already."
  [{:keys [tables scope-kinds rate-limit] :as options}]
  {:params-shape [:map {:closed true}]
   :answer-shape answer-shape
   :rate-limit   rate-limit
   :run-steps-in (make-run-steps-in options)
   :steps        [(make-answer-step scope-kinds (sentences/find-open-actions tables))]})
