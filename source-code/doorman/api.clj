(ns doorman.api
  "Who is asking, and what may they do. The api of doorman.

   Who is asking: a session in Postgres, a cookie of random bytes in the
   browser. The app's own login proves who someone is, then calls sign-in!;
   signing out deletes rows, so access ends at once on every server. A
   visitor (not signed in) gets a session of their own when the app first
   keeps something for them (start-visitor-session!); the rows kept name
   its id, and become the person's at sign-in. A server calling the app
   gets no session: its route reads none.

   What may they do: the app says, in its code, its kinds of scope (a shop
   under a company, a back office of one scope), their actions, the roles
   a new scope starts with and the denies (a session flag, a scope's
   state) (check-scope-kinds). doorman keeps, as rows: the scopes (the
   app's own row ids, under a parent scope or none), each scope's roles,
   and the memberships (a person in a scope, with one role there, which
   counts in the scopes below it). A scope's \"Owner\" holds every action
   of its kind and the kinds below. The :access sentences on the app's
   table maps become SQL conditions (make-sql-conditions): the check in
   front of every handler's steps (put-access-into-handlers), and the
   gates, Postgres's row rules (put-gates-on-tables, for cellar), with the
   functions they call (install-access-functions!). The leak test proves
   them (find-leaks); explain-refusal says why one message would be
   refused.

     (def find-session (doorman/make-find-session-step pool settings))
     (def ready (doorman/put-access-into-handlers handlers {:pool pool :tables tables :scope-kinds scope-kinds
                                                            :find-session-step find-session}))

     (doorman/add-scope! transaction scope-kinds :shop shop-id founder-id nil)
     (doorman/add-membership! transaction [:shop shop-id] anna-id \"Staff\")
     (doorman/list-actions-of-person transaction scope-kinds anna-id)
     ;=> {[:shop #uuid \"…\"] #{:orders/see :orders/edit}}

   Its tables are maps (sessions-table, access-tables) the app passes to
   cellar, in their own folder, doorman."
  (:require
   [doorman.check :as check]
   [doorman.conditions :as conditions]
   [doorman.explain :as explain]
   [doorman.functions :as functions]
   [doorman.leaks :as leaks]
   [doorman.memberships :as memberships]
   [doorman.my-actions :as my-actions]
   [doorman.scope-kinds :as scope-kinds]
   [doorman.sessions :as sessions]
   [doorman.settings :as settings]
   [cellar.api :as cellar]))

;; -----------------------------------------------------------------------------
;; Who is asking

(def settings-shape
  "The settings, every key required (see doorman.settings):

     {:cookie-name \"app_session\" :session-days 30 :visitor-session-days 7
      :renew-after-minutes 60 :allowed-origin \"https://shop.example\" :secure-cookie? true}"
  settings/settings-shape)

(def sessions-table
  "The sessions, in doorman's own folder: a person's (its person and
   flags, the flags' names joined by spaces) or a visitor's (its own id)."
  {:table     :doorman/sessions
   :columns   [[:session/fingerprint :string       {:primary-key? true}]
               [:session/person-id   :uuid         {:optional? true}]   ; nil: a visitor's
               [:session/visitor-id  :uuid         {:optional? true}]   ; nil: a person's
               [:session/flags       :string       {:default ""}]
               [:session/created-at  :time/instant {:default :now}]
               [:session/ends-at     :time/instant]]
   :indexes   [[:session/person-id] [:session/ends-at]]
   :gates     nil
   :on-change nil})

(defn sign-in!
  "Makes a session for a person whose login the app has proven, on the
   app's own route (request is its Ring request). In one transaction: the
   rows of the request's visitor session, if it has a live one, become the
   person's (every table with a :visitor column that :moves-to a person
   column: the person set, the visitor emptied); the request's session
   ends, whatever it was, so a key planted before the login is worthless
   after it; the new one is made, with the session's flags. Returns
   {:set-cookie text} for the answer's Set-Cookie header. The options,
   every key required, are checked first:

     (doorman/sign-in! pool request person-id {:settings settings :tables tables :session-flags #{}})
     (doorman/sign-in! pool request person-id {:settings settings :tables tables :session-flags #{:impersonated}})"
  [pool request person-id options]
  (sessions/sign-in! pool request person-id options))

(defn start-visitor-session!
  "A session for a visitor, someone not signed in, the first time the app
   keeps something for them (a cart): made on the app's own route
   (request is its Ring request), since a message's answer sets no cookie.
   Returns {:visitor-id id :set-cookie text}: the visitor's id, which the
   rows the app keeps for them name in their :visitor column, and the
   Set-Cookie text. A request that has a live session already makes none:
   {:visitor-id its visitor's id (nil for a person's) :set-cookie nil}. It
   lasts :visitor-session-days unseen. Checks the settings first."
  [pool request settings]
  (sessions/start-visitor-session! pool request settings))

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
   error; then the context gets :doorman/person-id (nil when nobody is
   signed in), :doorman/visitor-id (a visitor session's id, nil when
   someone is signed in or there is none) and :doorman/session-flags
   (#{} for none), and the person or the visitor is added to the
   message's log (mailroom's :mailroom/log, given logbook's with-context
   {:person-id …} or {:visitor-id …}), so every line the later steps write
   says who asked. Reads the request at :mailroom/request. Checks the
   settings first."
  [pool settings]
  (sessions/make-find-session-step pool settings))

;; -----------------------------------------------------------------------------
;; What may they do

(def access-tables
  "The scopes, their roles and actions, and the memberships, in doorman's folder."
  [{:table     :doorman/scopes
    :columns   [[:scope/kind        :string {:primary-key? true}]
                [:scope/id          :uuid   {:primary-key? true}]
                [:scope/parent-kind :string {:optional? true}]   ; the scope above it, if any
                [:scope/parent-id   :uuid   {:optional? true}]]
    :indexes   [[:scope/parent-kind :scope/parent-id]]
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
  "Returns the app's scope kinds; throws with every problem at once: a
   kind not writing out every key (:table :parent :single? :actions :roles
   :denies), a role naming an action of neither its kind nor a kind below
   it, an action in two kinds, an action not saying what it does to rows,
   a role named \"Owner\", an unknown :parent or a loop of them, a deny
   naming another kind's action or reading the state of a kind without a
   :table."
  [scope-kinds]
  (scope-kinds/check-scope-kinds scope-kinds))

(defn does-action-only-read?
  "Whether an action does nothing but :read (its kind says #{:read}): a
   handler of it runs in a read-only transaction, which Postgres keeps
   from writing. Every other action's handlers may write; groundwork keys their
   messages to run once. An action no kind has: false.

     (doorman/does-action-only-read? scope-kinds :orders/see)   ;=> true
     (doorman/does-action-only-read? scope-kinds :orders/edit)  ;=> false"
  [scope-kinds action]
  (scope-kinds/does-action-only-read? scope-kinds action))

(defn add-role!
  "Gives a scope a role allowing these actions, each one of the kind's or
   of a kind below it."
  [transaction scope-kinds scope role-name actions]
  (memberships/add-role! transaction scope-kinds scope role-name actions))

(defn add-membership!
  "Puts a person in a scope with one of the scope's roles."
  [transaction scope person-id role-name]
  (memberships/add-membership! transaction scope person-id role-name))

(defn add-scope!
  "Makes a scope of the app's own row: its starting roles, Owner, and the
   founder as its Owner, under the scope parent-id names (of the kind's
   :parent kind; nil for none). A kind with :single? true refuses a
   second scope. Call it in the transaction that makes the row.

     (doorman/add-scope! transaction scope-kinds :shop shop-id founder-id company-id)
     (doorman/add-scope! transaction scope-kinds :company company-id founder-id nil)"
  [transaction scope-kinds kind scope-id founder-id parent-id]
  (memberships/add-scope! transaction scope-kinds kind scope-id founder-id parent-id))

(defn remove-membership!
  "Takes a person out of a scope. Whether they were in it."
  [transaction scope person-id]
  (memberships/remove-membership! transaction scope person-id))

(defn list-actions-of-person
  "Every scope a person is in, with the actions their role there allows,
   and every scope below those, with the actions of its kind (and the
   kinds below it) held above: {[:shop id] #{:orders/see …}}."
  [transaction scope-kinds person-id]
  (memberships/list-actions-of-person transaction scope-kinds person-id))

(defn make-sql-conditions
  "The :access sentences of every table map, checked (unknown actions or
   columns, an open write without a :when or a :via, a :via without its
   indexes, a [:member kind] of a kind with more than one scope: every
   problem at once) and turned into SQL conditions, the kinds' denies in
   them:

     {:orders {:actions    {:orders/see \"…\" :orders/edit \"…\"}
               :operations {:read \"…\" :insert \"…\" :update \"…\" :delete \"…\"}}}

   The check uses an action's condition; the gates use an operation's."
  [tables scope-kinds]
  (conditions/make-sql-conditions tables scope-kinds))

(def ^:private create-access-functions
  (cellar/make-query-from-sql-file "doorman/create_access_functions.sql"))

(defn install-access-functions!
  "Installs, or replaces, the folder doorman and the SQL functions the
   check and the gates read who is asking from (doorman.find_asking_person(),
   find_asking_visitor(), find_scopes_holding(kind, action),
   has_session_flag(flag), is_passing(), find_moving_visitor(),
   find_moving_person()), and those the app's sentences and denies need:
   a :via's (doorman.via_…), a scope state's (doorman.denied_…), the guard
   of a table a :via reads (doorman.unchanged_…), and, per table with a
   one-column primary key and per action of its sentences, the one a query
   file calls to narrow a list to the rows the asker may do that action
   on:

     SELECT order__id, order__note FROM orders
     WHERE order__id IN (SELECT * FROM doorman.\"may orders/edit on orders\"())

   The sentences are checked first. Run it as the owner, before migrate:
   Postgres refuses a gate whose function doesn't exist yet. The app's own
   role may call them once cellar/grant-tables-to-role! is given doorman's
   tables (it grants their folder, and the functions in it)."
  [pool tables scope-kinds]
  (let [statements (functions/make-function-statements tables scope-kinds)]
    (cellar/run-statement! pool create-access-functions {})
    (doseq [statement statements]
      (cellar/run-statement! pool (cellar/make-query-from-sql-text "a function of doorman's" statement) {}))))

(defn put-access-into-handlers
  "Every handler (mailroom's table) checked for :action, :params-shape and
   :answer-shape, and for a sentence allowing its action on its :record;
   then each gets the check in front of its steps and runs them in one
   transaction that tells Postgres who is asking (the person or visitor,
   the session's flags, the scopes holding each action). A message naming
   a record its asker may not reach is answered \"Not found.\", one they
   may see but not act on \"Not allowed.\", before the handler's own
   steps; a handler without a :record runs only for an asker who can have
   its action at all (\"Not found.\"), and not when a session flag denies
   it (\"Not allowed.\"). The options, every key
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

(def my-actions-answer-shape
  "The answer of make-my-actions-handler's handler: every scope the
   person is in with the actions they may do there, and an entry of nil
   kind and id for the actions open to them in every scope:

     [{:scope-kind nil   :scope-id nil      :actions [:profile/edit]}
      {:scope-kind :shop :scope-id #uuid \"…\" :actions [:orders/edit :orders/see]}]

   The same value in the browser: doorman.api's .cljs half, for the
   app's shapes by name (doorman.shapes, one .cljc for both sides)."
  my-actions/answer-shape)

(defn make-my-actions-handler
  "A handler, every mailroom key written out, answering \"what may I
   do?\" for the browser (parlor's \"can I?\"): the asking person's
   actions by scope (list-actions-of-person, a [:member column action]
   sentence's action where that action is held, a :via's in the scopes
   its related rows name), and, under nil kind and id, the actions open
   to them in every scope: :anyone sentences' for everyone; :signed-in and
   [:me column] sentences' for someone signed in; [:me <visitor column>]
   sentences' for a visitor; a [:member <kind of one scope> action]'s for
   whoever holds that action. A [:me …] action is had on the rows that
   name the person, which no scope lists: it is listed for every scope,
   and the row decides. A session flag a deny names takes its actions out;
   a scope's state, out of that scope. Nobody signed in gets the :anyone
   actions only. The params are {}; the answer is my-actions-answer-shape.
   It runs as the asker: the find-session step first, then a read-only
   transaction telling Postgres who is asking, in which its step reads
   doorman's tables (so doorbell's notes name them). It is
   ready as it is: not for put-access-into-handlers, which wants an
   :action. The app names it; groundwork: :doorman/my-actions.

     (assoc handlers :doorman/my-actions
            (doorman/make-my-actions-handler {:pool pool :tables tables :scope-kinds scope-kinds
                                              :find-session-step find-session-step
                                              :rate-limit {:max 60 :per-ms 60000}}))   ; or nil

   The options, every key required, are checked first."
  [options]
  (my-actions/make-my-actions-handler (settings/check-my-actions-options options)))

(defn explain-refusal
  "Why the asker's message would be refused, or not, as plain data, for a
   REPL: read-only, in one transaction that tells Postgres who is asking
   and reads past the gates, so the record is seen even when the asker
   can't. options, every key required: {:pool :handlers :tables
   :scope-kinds}, the app's handlers by name (each with its :action and
   :record); asker: {:person-id :visitor-id :session-flags}.

     (doorman/explain-refusal {:pool pool :handlers handlers :tables tables :scope-kinds scope-kinds}
                              {:person-id anna :visitor-id nil :session-flags #{}}
                              :orders/edit-one {:order/id order-99})
     ;=> {:handler :orders/edit-one :action :orders/edit :writes? true :asker {…}
     ;    :answer :not-found                                  ; what the check answers: :allowed :not-allowed :not-found
     ;    :record {:table :orders :id #uuid \"…\" :exists? true
     ;             :scopes [{:column :order/shop-id :scope-kind :shop :scope-id #uuid \"…9\"}]}
     ;    :memberships [{:scope-kind :company :scope-id #uuid \"…\" :role \"Director\"}]   ; there and above
     ;    :actions-in-scopes [{:scope-kind :shop :scope-id #uuid \"…9\" :actions [:orders/see]}]
     ;    :sentences [{:table :orders :sentence {:allow … :to [:member :order/shop-id]} :holds? false
     ;                 :to {:says [:member :order/shop-id] :holds? false :scope-kind :shop
     ;                      :scope-id #uuid \"…9\" :needs :orders/edit :held-there? false}
     ;                 :when {:says [:= :order/status \"open\"] :holds? true}}]
     ;    :denies [{:scope-kind :shop :deny {:deny :writes :when …} :says-no? false :scope-ids []}]}

   A handler without a :record: :record nil, and each sentence's :way
   (:anyone :signed-in :visitor [:holds action]) and whether it :holds?
   for the asker; the :answer is the check's. An unknown handler:
   {:handler … :answer :no-such-handler}."
  [options asker handler-name params]
  (explain/explain-refusal (settings/check-explain-options options) (settings/check-asker asker) handler-name params))

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
