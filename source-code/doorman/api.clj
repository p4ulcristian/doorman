(ns doorman.api
  "Who is asking, and what may they do. The api of doorman.

   Who is asking: a session in Postgres, a cookie of random bytes in the
   browser. The app's own login proves who someone is, then calls sign-in!;
   signing out deletes rows, so access ends at once on every server.

   What may they do: the app says, in its code, its kinds of scope (a shop),
   their actions and the roles a new scope starts with (check-scope-kinds).
   doorman keeps, as rows: the scopes (the app's own row ids), each scope's
   roles, and the memberships (a person in a scope, with one role there). A
   scope's \"Owner\" holds every action of its kind. The :access sentences
   on the app's table maps become SQL conditions (make-sql-conditions): the
   check in front of every handler's steps (put-access-into-handlers), and
   the gates, Postgres's row rules (put-gates-on-tables, for cellar). The
   leak test proves them (find-leaks).

     (def find-session (doorman/make-find-session-step pool settings))
     (def ready (doorman/put-access-into-handlers handlers {:pool pool :tables tables :scope-kinds scope-kinds
                                                            :find-session-step find-session}))

     (doorman/add-scope! transaction scope-kinds :shop shop-id founder-id)
     (doorman/add-membership! transaction [:shop shop-id] anna-id \"Staff\")
     (doorman/list-actions-of-person transaction scope-kinds anna-id)
     ;=> {[:shop #uuid \"…\"] #{:orders/see :orders/edit}}

   Its tables are maps (sessions-table, access-tables) the app passes to
   cellar, in their own folder, doorman."
  (:require
   [doorman.check :as check]
   [doorman.conditions :as conditions]
   [doorman.leaks :as leaks]
   [doorman.memberships :as memberships]
   [doorman.scope-kinds :as scope-kinds]
   [doorman.sessions :as sessions]
   [doorman.settings :as settings]
   [cellar.api :as cellar]))

;; -----------------------------------------------------------------------------
;; Who is asking

(def settings-shape
  "The settings, every key required (see doorman.settings):

     {:cookie-name \"app_session\" :session-days 30 :renew-after-minutes 60
      :allowed-origin \"https://shop.example\" :secure-cookie? true}"
  settings/settings-shape)

(def sessions-table
  "The sessions, in doorman's own folder."
  {:table     :doorman/sessions
   :columns   [[:session/fingerprint :string       {:primary-key? true}]
               [:session/person-id   :uuid]
               [:session/created-at  :time/instant {:default :now}]
               [:session/ends-at     :time/instant]]
   :indexes   [[:session/person-id] [:session/ends-at]]
   :gates     nil
   :on-change nil})

(defn sign-in!
  "Makes a session for a person whose login the app has proven. Returns
   {:set-cookie text} for the answer's Set-Cookie header."
  [pool person-id settings]
  (sessions/sign-in! pool person-id settings))

(defn sign-out!
  "Ends the request's session at once. Returns {:set-cookie text} that
   deletes the cookie."
  [pool request settings]
  (sessions/sign-out! pool request settings))

(defn sign-out-everywhere!
  "Ends every session of a person at once (signing out everywhere, a
   removed member). Returns how many ended."
  [pool person-id]
  (sessions/sign-out-everywhere! pool person-id))

(defn delete-ended-sessions!
  "Forgets sessions that have ended; for a nightly background job. Returns how many."
  [pool]
  (sessions/delete-ended-sessions! pool))

(defn find-session-key-of-request
  "A key that tells one browser's session apart from another's, without a
   database read (a hash of its cookie, the one the settings name), or nil
   without a cookie: for rate limits. Never the cookie itself."
  [request settings]
  (sessions/find-session-key-of-request request settings))

(defn make-find-session-step
  "A step for the start of a handler: a request that changes something must
   come from the app's own pages (its Origin), or it is answered with an
   error; then :doorman/person-id is added to the context, nil when nobody
   is signed in, and someone signed in is added to the message's log
   (mailroom's :mailroom/log, given logbook's with-context {:person-id …}),
   so every line the later steps write says who asked. Reads the request
   at :mailroom/request. Checks the settings first."
  [pool settings]
  (sessions/make-find-session-step pool settings))

;; -----------------------------------------------------------------------------
;; What may they do

(def access-tables
  "The scopes, their roles and actions, and the memberships, in doorman's folder."
  [{:table     :doorman/scopes
    :columns   [[:scope/kind        :string {:primary-key? true}]
                [:scope/id          :uuid   {:primary-key? true}]
                [:scope/parent-kind :string {:optional? true}]   ; for parent scopes, later
                [:scope/parent-id   :uuid   {:optional? true}]]
    :indexes   []
    :gates     nil
    :on-change nil}
   {:table     :doorman/roles
    :columns   [[:role/scope-kind :string {:primary-key? true}]
                [:role/scope-id   :uuid   {:primary-key? true}]
                [:role/name       :string {:primary-key? true}]]
    :indexes   []
    :gates     nil
    :on-change nil}
   {:table     :doorman/role-actions
    :columns   [[:role-action/scope-kind :string {:primary-key? true}]
                [:role-action/scope-id   :uuid   {:primary-key? true}]
                [:role-action/role       :string {:primary-key? true}]
                [:role-action/action     :string {:primary-key? true}]]
    :indexes   []
    :gates     nil
    :on-change nil}
   {:table     :doorman/memberships
    :columns   [[:membership/person-id  :uuid   {:primary-key? true}]
                [:membership/scope-kind :string {:primary-key? true}]
                [:membership/scope-id   :uuid   {:primary-key? true}]
                [:membership/role       :string]]
    :indexes   [[:membership/scope-kind :membership/scope-id]]
    :gates     nil
    :on-change nil}])

(defn check-scope-kinds
  "Returns the app's scope kinds; throws with every problem at once (a role
   naming an action its kind doesn't have, an action not saying what it does
   to rows, a role named \"Owner\")."
  [scope-kinds]
  (scope-kinds/check-scope-kinds scope-kinds))

(defn add-role!
  "Gives a scope a role allowing these actions, each one of the kind's."
  [transaction scope-kinds scope role-name actions]
  (memberships/add-role! transaction scope-kinds scope role-name actions))

(defn add-membership!
  "Puts a person in a scope with one of the scope's roles."
  [transaction scope person-id role-name]
  (memberships/add-membership! transaction scope person-id role-name))

(defn add-scope!
  "Makes a scope of the app's own row: its starting roles, Owner, and the
   founder as its Owner. Call it in the transaction that makes the row."
  [transaction scope-kinds kind scope-id founder-id]
  (memberships/add-scope! transaction scope-kinds kind scope-id founder-id))

(defn remove-membership!
  "Takes a person out of a scope. Whether they were in it."
  [transaction scope person-id]
  (memberships/remove-membership! transaction scope person-id))

(defn list-actions-of-person
  "Every scope a person is in, with the actions their role there allows:
   {[:shop id] #{:orders/see …}}."
  [transaction scope-kinds person-id]
  (memberships/list-actions-of-person transaction scope-kinds person-id))

(defn make-sql-conditions
  "The :access sentences of every table map, checked (unknown actions or
   columns, an open write without a :when: every problem at once) and
   turned into SQL conditions:

     {:orders {:actions    {:orders/see \"…\" :orders/edit \"…\"}
               :operations {:read \"…\" :insert \"…\" :update \"…\" :delete \"…\"}}}

   The check uses an action's condition; the gates use an operation's."
  [tables scope-kinds]
  (conditions/make-sql-conditions tables scope-kinds))

(def ^:private create-access-functions
  (cellar/make-query-from-sql-file "doorman/create_access_functions.sql"))

(defn install-access-functions!
  "Installs, or replaces, the folder doorman and the two SQL functions the
   check and the gates read who is asking from: doorman.find_asking_person()
   and doorman.find_scopes_holding(kind, action). Run it as the owner,
   before migrate: Postgres refuses a gate whose function doesn't exist
   yet. The app's own role may call them once cellar/grant-tables-to-role!
   is given doorman's tables (it grants their folder, and the functions in
   it)."
  [pool]
  (cellar/run-statement! pool create-access-functions {}))

(defn put-access-into-handlers
  "Every handler (mailroom's table) checked for :action, :params-shape and
   :answer-shape, and for a sentence allowing its action on its :record;
   then each gets the check in front of its steps and runs them in one
   transaction that tells Postgres who is asking. A message found on no
   session, or naming a record its person may not reach, is answered
   \"Not found.\" before the handler's own steps. The options, every key
   required, are checked first:

     (doorman/put-access-into-handlers handlers {:pool pool :tables tables :scope-kinds scope-kinds
                                                 :find-session-step (doorman/make-find-session-step pool settings)})

   The handler's steps reach the database through :doorman/transaction in
   their context."
  [handlers options]
  (let [{:keys [tables scope-kinds] :as options} (settings/check-access-options options)]
    (check/put-access-into-handlers
     handlers
     (assoc options
            :conditions   (conditions/make-sql-conditions tables scope-kinds)
            :list-actions #(memberships/list-actions-of-person %1 scope-kinds %2)))))

(defn put-gates-on-tables
  "The table maps, each with sentences given :gates (its :operations
   conditions) for cellar to make Postgres's row rules from, and every
   other one :gates nil (no row rules), so each map says its gates. A
   table that names a scope (references a scope kind's table) must have
   :access, or :access :public on purpose; otherwise this throws.

     (cellar/migrate! pool (doorman/put-gates-on-tables tables scope-kinds) changes migrate-options)"
  [tables scope-kinds]
  (let [conditions (conditions/make-sql-conditions tables scope-kinds)]
    (mapv (fn [{:keys [table] :as table-map}]
            (if-let [operations (get-in conditions [table :operations])]
              (assoc table-map :gates operations)
              (assoc table-map :gates nil)))
          tables)))

(defn find-leaks
  "The leak test, for an app's own test suite: every handler (made ready by
   put-access-into-handlers) asked by the person of request (signed in to
   one scope) with foreign-ids, the ids of another scope's rows by table,
   {:orders [id …]}. A handler with a :record must answer \"Not found.\" for
   each; any other must give none of them back. Its other params come from
   its :params-shape, by malli's generator, with the options' :seed and
   :size. Each message is answered as over http, by the options' :wire, the
   app's wire, and :log, the app's log (doorman makes neither). Every
   option is required. Returns every leak; empty means none.

     (is (= [] (doorman/find-leaks ready-handlers anna-request {:orders [order-in-shop-9]}
                                   {:wire wire :log log :seed 7 :size 30})))"
  [handlers request foreign-ids options]
  (leaks/find-leaks handlers request foreign-ids (settings/check-leak-options options)))
