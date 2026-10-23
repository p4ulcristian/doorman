(ns doorman.api
  "Who is asking, and what may they do. The api of doorman.

   Who is asking: a session in Postgres, a cookie of random bytes in the
   browser. The app's own login proves who someone is, then calls sign-in!,
   the one door that names a person; signing out deletes rows, so access
   ends at once on every server. A signed-in person makes tokens for
   themselves (make-token!), for a script or an AI acting as them: a call
   with Authorization: Bearer <token> is theirs, with the same actions,
   scopes and gates. Every call after sign-in carries a proof doorman made
   and reads itself, the cookie or a token; no code names the person. A
   visitor (not signed in) gets a session of their own when the app first
   keeps something for them (start-visitor-session!); the rows kept name
   its id, and become the person's at sign-in. A server calling the app
   gets no session: its route reads none.

   What may they do: the app says, in its code, its kinds of scope (a shop
   under a company, a back office of one scope), their actions, the roles
   a new scope starts with and the denies (a session flag, a scope's
   state) (check-scope-kinds). doorman keeps, as rows: the scopes (the
   app's own row ids, under a parent scope or none), each scope's roles
   (an id, a name unique in the scope, its actions), and the memberships
   (a person in a scope, holding a set of its roles, #{} for none yet,
   which counts in the scopes below it); a person's actions in a scope
   are the union of their roles'. A scope's \"Owner\" holds every action
   of its kind and the kinds below. The :access sentences on the app's
   table maps become SQL conditions (make-sql-conditions): the check in
   front of every handler's steps (put-access-into-handlers), and the
   gates, Postgres's row rules (put-gates-on-tables, for cellar), with the
   functions they call (function-files, doorman's own, and
   make-access-functions, those made from the sentences, both declared
   to cellar). The leak test proves
   them (find-leaks); explain-refusal says why one message would be
   refused.

     (def find-session (doorman/make-find-session-step pool settings))
     (def ready (doorman/put-access-into-handlers handlers {:pool pool :tables tables :scope-kinds scope-kinds
                                                            :find-session-step find-session}))

     (let [roles (doorman/add-scope! transaction scope-kinds :shop shop-id founder-id nil)]
       (doorman/add-membership! transaction [:shop shop-id] anna-id #{(get roles \"Staff\")}))
     (doorman/list-actions-of-person transaction scope-kinds anna-id)
     ;=> {[:shop #uuid \"…\"] #{:orders/see :orders/edit}}

   Its tables are maps (sessions-table, tokens-table, access-tables) the
   app passes to cellar, in their own folder, doorman. Asking as a named
   person is a REPL's only, outside this api: doorman.repl."
  (:require
   [doorman.askers :as askers]
   [doorman.check :as check]
   [doorman.conditions :as conditions]
   [doorman.explain :as explain]
   [doorman.functions :as functions]
   [doorman.leaks :as leaks]
   [doorman.memberships :as memberships]
   [doorman.my-actions :as my-actions]
   [doorman.parents :as parents]
   [doorman.scope-kinds :as scope-kinds]
   [doorman.sessions :as sessions]
   [doorman.settings :as settings]
   [doorman.shapes :as shapes]
   [doorman.system :as system]
   [doorman.tokens :as tokens]
   [cellar.api :as cellar]))

;; -----------------------------------------------------------------------------
;; Who is asking

(def settings-shape
  "The settings, every key required (see doorman.settings):

     {:cookie-name \"app_session\" :session-days 30 :visitor-session-days 7
      :renew-after-minutes 60 :allowed-origin \"https://shop.example\" :secure-cookie? true
      :token-lead \"shop_\" :token-shown-characters 11 :token-longest-days 365}"
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
   :indexes   [{:columns [[:session/person-id :ascending :nulls-last]]
                :method :btree :ops {} :unique? false :where nil}
               {:columns [[:session/ends-at :ascending :nulls-last]]
                :method :btree :ops {} :unique? false :where nil}]
   :uniques   []
   :checks    []
   :references []
   :triggers   []
   :gates     nil
   :on-change nil})

(def tokens-table
  "The tokens people made, in doorman's own folder: never their text, its
   SHA-256 (:token/fingerprint), the characters the list shows, a name,
   the person, the end."
  {:table     :doorman/tokens
   :columns   [[:token/id          :uuid         {:primary-key? true}]
               [:token/fingerprint :string       {:unique? true}]
               [:token/person-id   :uuid]
               [:token/name        :string]
               [:token/shown-start :string]
               [:token/created-at  :time/instant {:default :now}]
               [:token/ends-at     :time/instant]]
   :indexes   [{:columns [[:token/person-id :ascending :nulls-last]]
                :method :btree :ops {} :unique? false :where nil}
               {:columns [[:token/ends-at :ascending :nulls-last]]
                :method :btree :ops {} :unique? false :where nil}]
   :uniques   []
   :checks    []
   :references []
   :triggers   []
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
  "Ends every session and every token of a person at once, in one
   transaction (signing out everywhere, a person the app removes).
   Returns how many of each ended: {:sessions 2 :tokens 1}."
  [pool person-id]
  (sessions/sign-out-everywhere! pool person-id))

(defn delete-ended-sessions!
  "Forgets sessions that have ended; for a nightly background job. Returns how many."
  [pool]
  (sessions/delete-ended-sessions! pool))

(defn make-token!
  "A token for the signed-in person of the request (its session's cookie),
   for a script or an AI acting as them: {:token-id id :text \"shop_…\"
   :shown-start \"shop_AbCdEf\" :ends-at instant}. The text is shown this
   once: the table keeps only its SHA-256. It is the settings' :token-lead
   and 43 random characters. options, every key required, checked first:
   {:settings :name \"catalog script\" :ends-at instant}, the end after now
   and at most :token-longest-days from now. Refused (throws
   :doorman/no-token-for-asker): nobody signed in; a token's call (a token
   makes no token); a session with flags (the token, having none, would
   shed them).

     (doorman/make-token! pool request {:settings settings :name \"catalog script\"
                                        :ends-at (.plus (Instant/now) (Duration/ofDays 90))})"
  [pool request options]
  (askers/make-token! pool request (settings/check-token-options options)))

(defn list-tokens
  "The tokens of the request's person (by its cookie or a token) that
   haven't ended, newest first, never their text: [{:token-id :name
   :shown-start :created-at :ends-at}]; [] for nobody. Checks the
   settings first."
  [pool request settings]
  (askers/list-tokens pool request (settings/check-settings settings)))

(defn revoke-token!
  "Ends one token of the request's person (by its cookie or a token) at
   once. Whether it was theirs and there; another person's is never
   touched. Checks the settings first."
  [pool request settings token-id]
  (askers/revoke-token! pool request (settings/check-settings settings) token-id))

(defn delete-ended-tokens!
  "Forgets tokens that have ended; for a nightly background job. Returns how many."
  [pool]
  (tokens/delete-ended-tokens! pool))

(defn find-session-key-of-request
  "A key that tells one asker apart from another, without a database read:
   a hash of the request's token (Authorization: Bearer), else of its
   cookie (the one the settings name), or nil with neither: for rate
   limits. Never the token or the cookie itself."
  [request settings]
  (askers/find-session-key-of-request request settings))

(defn make-find-session-step
  "A step for the start of a handler: who is asking, from the request's
   proof (read at :bridge/request), doorman's own and nothing else:

   - a token, Authorization: Bearer <text>: its person, with no session
     flags (a token has none) and no visitor; no Origin check, since a
     browser never sends the header on its own; a wrong, ended or revoked
     token is nobody;
   - else the session's cookie: a request that changes something must
     come from the app's own pages (its Origin), or it is refused
     :doorman/not-allowed; the session's person or visitor and its flags;
   - a request carrying both is refused :doorman/not-allowed.

   A refusal's why is written to the message's log, :info
   :doorman/request-refused {:because …}.

   Then the context gets :doorman/person-id (nil when nobody is signed
   in), :doorman/visitor-id (a visitor session's id, nil when someone is
   signed in or there is none) and :doorman/session-flags (#{} for none),
   and the person (with the token's id, {:person-id … :token-id …}) or the
   visitor is added to the message's log (bridge's :bridge/log, given
   logbook's with-context), so every line the later steps write says who
   asked. Checks the settings first."
  [pool settings]
  (askers/make-find-session-step pool settings))

;; -----------------------------------------------------------------------------
;; What may they do

(def access-tables
  "The scopes, their roles (an id, a name unique in the scope) and the
   actions each allows, the memberships and the roles each holds, in
   doorman's folder."
  [{:table     :doorman/scopes
    :columns   [[:scope/kind        :string {:primary-key? true}]
                [:scope/id          :uuid   {:primary-key? true}]
                [:scope/parent-kind :string {:optional? true}]   ; the scope above it, if any
                [:scope/parent-id   :uuid   {:optional? true}]]
    :indexes   [{:columns [[:scope/parent-kind :ascending :nulls-last] [:scope/parent-id :ascending :nulls-last]]
                 :method :btree :ops {} :unique? false :where nil}]
    :uniques   []
    :checks    []
    :references []
    :triggers   []
    :gates     nil
    :on-change nil}
   {:table     :doorman/roles
    :columns   [[:role/id         :uuid   {:primary-key? true}]
                [:role/scope-kind :string]
                [:role/scope-id   :uuid]
                [:role/name       :string]]                       ; unique in its scope: doorman's functions keep it so
    :indexes   [{:columns [[:role/scope-kind :ascending :nulls-last] [:role/scope-id :ascending :nulls-last]]
                 :method :btree :ops {} :unique? false :where nil}]
    :uniques   []
    :checks    []
    :references []
    :triggers   []
    :gates     nil
    :on-change nil}
   {:table     :doorman/role-actions
    :columns   [[:role-action/role-id :uuid   {:primary-key? true :references {:to :role/id :on-delete :refuse :on-update :refuse
                                                                                :checked-at :statement}}]
                [:role-action/action  :string {:primary-key? true}]]
    :indexes   []
    :uniques   []
    :checks    []
    :references []
    :triggers   []
    :gates     nil
    :on-change nil}
   {:table     :doorman/memberships
    :columns   [[:membership/person-id  :uuid   {:primary-key? true}]
                [:membership/scope-kind :string {:primary-key? true}]
                [:membership/scope-id   :uuid   {:primary-key? true}]]
    :indexes   [{:columns [[:membership/scope-kind :ascending :nulls-last] [:membership/scope-id :ascending :nulls-last]]
                 :method :btree :ops {} :unique? false :where nil}]
    :uniques   []
    :checks    []
    :references []
    :triggers   []
    :gates     nil
    :on-change nil}
   {:table     :doorman/membership-roles                           ; none for a member with no role yet
    :columns   [[:membership-role/person-id  :uuid   {:primary-key? true}]
                [:membership-role/scope-kind :string {:primary-key? true}]
                [:membership-role/scope-id   :uuid   {:primary-key? true}]
                [:membership-role/role-id    :uuid   {:primary-key? true}]]
    :indexes   [{:columns [[:membership-role/role-id :ascending :nulls-last]]
                 :method :btree :ops {} :unique? false :where nil}]
    :uniques   []
    :checks    []
    ;; the role is one of the membership's scope's
    :references [{:columns   [:membership-role/role-id :membership-role/scope-kind :membership-role/scope-id]
                  :to        [:role/id :role/scope-kind :role/scope-id]
                  :on-delete :refuse
                  :on-update :refuse
                  :checked-at :statement}]
    :triggers   []
    :gates     nil
    :on-change nil}])

(def ^:private access-tables-of-v0-7
  "v0.7's roles, role actions and memberships (a role was its name, a
   member held one), under the names the move's first step gives them."
  [{:table     :doorman/roles-of-v0-7
    :columns   [[:role/scope-kind :string {:primary-key? true}]
                [:role/scope-id   :uuid   {:primary-key? true}]
                [:role/name       :string {:primary-key? true}]]
    :indexes   []
    :uniques   []
    :checks    []
    :references []
    :triggers   []
    :gates     nil
    :on-change nil}
   {:table     :doorman/role-actions-of-v0-7
    :columns   [[:role-action/scope-kind :string {:primary-key? true}]
                [:role-action/scope-id   :uuid   {:primary-key? true}]
                [:role-action/role       :string {:primary-key? true}]
                [:role-action/action     :string {:primary-key? true}]]
    :indexes   []
    :uniques   []
    :checks    []
    :references []
    :triggers   []
    :gates     nil
    :on-change nil}
   {:table     :doorman/memberships-of-v0-7
    :columns   [[:membership/person-id  :uuid   {:primary-key? true}]
                [:membership/scope-kind :string {:primary-key? true}]
                [:membership/scope-id   :uuid   {:primary-key? true}]
                [:membership/role       :string]]
    :indexes   [{:columns [[:membership/scope-kind :ascending :nulls-last] [:membership/scope-id :ascending :nulls-last]]
                 :method :btree :ops {} :unique? false :where nil}]
    :uniques   []
    :checks    []
    :references []
    :triggers   []
    :gates     nil
    :on-change nil}])

(def access-move-from-v0-7
  "How a database of doorman v0.7 (a role was its name, a member held
   one) comes to access-tables, with its rows, by cellar alone, in two
   migrate! runs, one after the other, before the app starts:

   1. :old-tables and :renames: v0.7's three tables renamed
      (doorman.roles_of_v0_7, role_actions_of_v0_7, memberships_of_v0_7)
      and kept, by their maps; today's made beside them, empty.
   2. :copy-and-drops (after :renames, which stay in the changes): each
      role copied with an id of its own, its actions with it, each
      membership's one role name turned into the set holding that role's
      id; then the old tables dropped. One transaction, cellar's instant
      part.

     ;; 1.
     @(:slow-part (cellar/migrate! pool (update declared :tables into (:old-tables doorman/access-move-from-v0-7))
                                   (into changes (:renames doorman/access-move-from-v0-7)) options))
     ;; 2.
     (cellar/migrate! pool declared (-> changes (into (:renames doorman/access-move-from-v0-7))
                                        (into (:copy-and-drops doorman/access-move-from-v0-7)))
                      options)

   declared is what the app passes cellar, {:tables :extensions
   :functions :views}.

   Between the two the new tables are empty: nobody holds anything there,
   so the app doesn't start before step 2."
  {:old-tables     access-tables-of-v0-7
   :renames        [{:id "doorman-2026-10-23-roles-of-v0-7-renamed" :rename-table [:doorman/roles :doorman/roles-of-v0-7]}
                    {:id "doorman-2026-10-23-role-actions-of-v0-7-renamed" :rename-table [:doorman/role-actions :doorman/role-actions-of-v0-7]}
                    {:id "doorman-2026-10-23-memberships-of-v0-7-renamed" :rename-table [:doorman/memberships :doorman/memberships-of-v0-7]}]
   :copy-and-drops [{:id "doorman-2026-10-23-access-rows-of-v0-7-copied" :run-sql-file "doorman/move_access_rows_of_v0_7.sql"}
                    {:id "doorman-2026-10-23-roles-of-v0-7-dropped" :drop-table :doorman/roles-of-v0-7
                     :why "roles have ids: its rows are in doorman.roles"}
                    {:id "doorman-2026-10-23-role-actions-of-v0-7-dropped" :drop-table :doorman/role-actions-of-v0-7
                     :why "roles have ids: its rows are in doorman.role_actions"}
                    {:id "doorman-2026-10-23-memberships-of-v0-7-dropped" :drop-table :doorman/memberships-of-v0-7
                     :why "a member holds a set of roles: its rows are in doorman.memberships and doorman.membership_roles"}]})

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
  "Gives a scope a role: a name, not yet one of the scope's, nor
   \"Owner\", and a set of actions, each one of the kind's or of a kind
   below it (#{} for none yet). Returns its id, which memberships hold.
   Refused (throws): :doorman/role-name-taken, :doorman/owner-role,
   :doorman/unknown-action, :doorman/bad-role-name, :doorman/bad-actions.

     (doorman/add-role! transaction scope-kinds [:shop shop-id] \"QC approver\" #{:qc/approve})   ;=> #uuid \"…\""
  [transaction scope-kinds scope role-name actions]
  (memberships/add-role! transaction scope-kinds scope role-name actions))

(defn rename-role!
  "Renames a role of the scope: one row, its members keep it. The new
   name is no other role's of the scope, nor \"Owner\"; Owner itself is
   never renamed. Refused (throws): :doorman/unknown-role (not the
   scope's), :doorman/role-name-taken, :doorman/owner-role,
   :doorman/bad-role-name. Returns nil.

     (doorman/rename-role! transaction [:shop shop-id] painter-id \"Coater\")"
  [transaction scope role-id role-name]
  (memberships/rename-role! transaction scope role-id role-name))

(defn set-role-actions!
  "The actions a role of the scope allows, in place of its old ones, each
   one of the kind's or of a kind below it; at once for every member
   holding it. Owner's are every action, never set. Refused (throws):
   :doorman/unknown-role, :doorman/owner-role, :doorman/unknown-action,
   :doorman/bad-actions. Returns nil."
  [transaction scope-kinds scope role-id actions]
  (memberships/set-role-actions! transaction scope-kinds scope role-id actions))

(defn remove-role!
  "Removes a role of the scope that no member holds. Refused (throws):
   :doorman/role-held while a member holds it ({:role-id :name :held-by
   n} in its data; take it from them first, set-membership-roles!),
   :doorman/unknown-role, :doorman/owner-role. Returns nil."
  [transaction scope role-id]
  (memberships/remove-role! transaction scope role-id))

(defn list-roles
  "The scope's roles by name, each with the actions it allows (Owner's:
   every action of the kind and the kinds below):
   [{:role-id #uuid \"…\" :name \"Owner\" :actions #{…}} {:role-id … :name \"Staff\" :actions #{:orders/see}}]."
  [transaction scope-kinds scope]
  (memberships/list-roles transaction scope-kinds scope))

(defn add-membership!
  "Puts a person in a scope, holding a set of the scope's roles there, by
   their ids: #{} for no role yet. Refused (throws):
   :doorman/unknown-role (a role not the scope's), :doorman/bad-roles
   (not a set of ids). Returns nil.

     (doorman/add-membership! transaction [:shop shop-id] anna #{employee-id qc-approver-id})
     (doorman/add-membership! transaction [:shop shop-id] tom #{})"
  [transaction scope person-id role-ids]
  (memberships/add-membership! transaction scope person-id role-ids))

(defn set-membership-roles!
  "The roles a member holds in the scope, in place of their old ones (#{}
   for none). Whether they are a member there; nothing changes for one
   who isn't. Refused (throws): :doorman/unknown-role,
   :doorman/bad-roles."
  [transaction scope person-id role-ids]
  (memberships/set-membership-roles! transaction scope person-id role-ids))

(defn add-scope!
  "Makes a scope of the app's own row: its starting roles, Owner, and the
   founder as a member holding Owner, under the scope parent-id names (of
   the kind's :parent kind; nil for none). A kind with :single? true
   refuses a second scope. Call it in the transaction that makes the row.
   Returns the new roles' ids by name, {\"Owner\" #uuid \"…\" \"Staff\" #uuid \"…\"}.

     (doorman/add-scope! transaction scope-kinds :shop shop-id founder-id company-id)
     (doorman/add-scope! transaction scope-kinds :company company-id founder-id nil)"
  [transaction scope-kinds kind scope-id founder-id parent-id]
  (memberships/add-scope! transaction scope-kinds kind scope-id founder-id parent-id))

(defn remove-membership!
  "Takes a person out of a scope, with the roles they held there. Whether
   they were in it."
  [transaction scope person-id]
  (memberships/remove-membership! transaction scope person-id))

(defn list-actions-of-person
  "Every scope a person is in, with the actions their roles there allow
   (the union of every role they hold; #{} for a member with none yet),
   and every scope below those, with the actions of its kind (and the
   kinds below it) held above: {[:shop id] #{:orders/see …}}."
  [transaction scope-kinds person-id]
  (memberships/list-actions-of-person transaction scope-kinds person-id))

(defn make-sql-conditions
  "The :access sentences of every table map, checked (unknown actions or
   columns, a sentence without :only or with an :only beyond what one of
   its actions does, an open write without a :when or a :via, a :via
   without its indexes, a [:member kind] of a kind with more than one
   scope: every problem at once) and turned into SQL conditions, the
   kinds' denies in them:

     {:orders {:actions    {:orders/see \"…\" :orders/edit \"…\"}
               :reads      {:orders/see \"…\" :orders/edit \"…\"}
               :operations {:read \"…\" :insert \"…\" :update \"…\" :delete \"…\"}}}

   The check uses an action's condition: its sentences whose :only allows
   what the action is for (its writes, for an action that writes; else
   :read), so a record the action only reads there is :doorman/not-allowed; the gates
   use an operation's, each action by the sentences whose :only allows that
   operation on the table, and each person's sentence only while its action
   is the one acted as (doorman.is_acting). :reads, each action that reads
   by its sentences allowing :read, is what a marked param's row is read
   by ([:uuid {:doorman/row-of :orders}])."
  [tables scope-kinds]
  (conditions/make-sql-conditions tables scope-kinds))

(def function-files
  "The .sql files of the SQL functions the check and the gates read who is
   asking from, for cellar's :functions beside make-access-functions'
   and the app's own (cellar makes them before the tables, so the gates
   can call them, and drops a function in doorman's folder that nothing
   declared makes):
   doorman.find_asking_person(), find_asking_visitor(),
   find_asking_system(), find_scopes_holding(kind, action),
   has_session_flag(flag), is_passing(), find_moving_visitor(),
   find_moving_person(), is_acting(action). They read two settings of the
   transaction: doorman.asking, who is asking, as JSON (doorman.asking):

     {\"person\": \"uuid or null\", \"visitor\": \"uuid or null\", \"flags\": [\"impersonated\", …],
      \"acting\": \"orders/edit\" or null, \"every_action\": false,
      \"holding\": {\"shop orders/edit\": [\"scope id\", …]},
      \"moving\": {\"visitor\": \"uuid\", \"person\": \"uuid\"}}   ; only while sign-in moves a visitor's rows
     {\"system\": \"postbox\"}                                ; only while a named system actor runs

   \"acting\" is the one action a handler's steps act as: a person's
   sentences count only for it (is_acting), and \"holding\" lists only the
   scopes the handler acts in; \"every_action\" true, every action counts
   (run-as-asker-of-request!, \"what may I do?\", explain-refusal, the
   check itself, a named system actor's :anyone rows). And
   doorman.passing, 'on' only while one of doorman's own functions reads
   past the gates (is_passing). The app's own role may call them once
   cellar/grant-tables-to-role! is given doorman's tables (it grants their
   folder, and the functions in it)."
  (mapv #(str "doorman/sql_functions/" % ".sql")
        ["find_asking_person" "find_asking_visitor" "find_asking_system" "find_scopes_holding"
         "has_session_flag" "is_passing" "find_moving_visitor" "find_moving_person" "is_acting"]))

(defn make-access-functions
  "The SQL functions the app's sentences and denies need, made from them,
   as cellar's :functions takes a made one, {:name :sql}, every key
   written out: a :via's (doorman.via_…), a scope state's
   (doorman.denied_…), the guard of a table a :via reads
   (doorman.unchanged_…), a record under a record's fill
   (doorman.fill_…), and, per table with a one-column primary key and per
   action of its sentences, the one a query file calls to narrow a list
   to the rows the asker may do that action on:

     SELECT order__id, order__note FROM orders
     WHERE order__id IN (SELECT * FROM doorman.\"may orders/edit on orders\"())

   The sentences are checked first. The app passes them to cellar's
   migrate! with function-files and its own (cellar's decision \":functions
   may be SQL text a library makes\"), so cellar makes them before the
   gates that call them, compares them on every plan, and drops one when
   the sentence that made it is gone:

     (cellar/migrate! owner-pool {:tables     (doorman/put-gates-on-tables tables scope-kinds)
                                  :extensions []
                                  :functions  (-> doorman/function-files
                                                  (into (doorman/make-access-functions tables scope-kinds))
                                                  (into app-function-files))
                                  :views      []}
                      changes migrate-options)"
  [tables scope-kinds]
  (functions/make-functions tables scope-kinds))

(defn put-access-into-handlers
  "Every handler (bridge's table) checked, with every problem at once: its
   :action, :params-shape and :answer-shape, and where it acts, exactly
   one of, written out:

     :record [:orders :order/id]              one row, by its param; a sentence on the table allows the action
     :scope  [:shop :shop/id]                 a scope kind, and the param holding the scope's id
     :scope  :every-scope-holding-the-action  a list over every scope where the action is held
     :scope  :no-scope                        only :anyone, :signed-in and [:me …] sentences

   Each also writes out bridge's keys, :refusals among them ({} for none),
   and never doorman's own reasons (refusals): doorman adds those to every
   handler's :refusals.

   Then each gets the check in front of its steps and runs them in one
   transaction that tells Postgres who is asking. The check refuses (by
   bridge's refuse): a record its asker may not reach :doorman/not-found,
   one they may see but not act on :doorman/not-allowed; a :scope [kind
   param] whose scope doesn't hold the action (a membership above it
   counts) :doorman/not-found, one a session flag or the scope's state
   denies :doorman/not-allowed; :every-scope-holding-the-action runs for
   an asker who can have the action at all, :no-scope for one an open
   sentence gives it to (:doorman/not-found), and not when a session flag
   denies it (:doorman/not-allowed). Refused while nobody is signed in
   (nobody, a visitor, a wrong, ended or revoked token), the reason is
   :doorman/signed-out, whatever the record: so it tells nothing of
   whether one exists. Let through, the steps' Postgres hears only
   the handler's action, in the scopes it acts in: the named one; the
   record's (its columns', and the kinds its action reaches through a
   [:member <kind of one scope>] or a :via); every one holding it; none. A
   step writing a row in another scope, by a bug or a crafted param, is
   refused by the gate. A param whose shape marks it as a row of a table,
   [:uuid {:doorman/row-of :workstations}] (in a :map, :vector,
   :sequential, :set, :maybe or :and of the :params-shape), must be a row
   the handler's action reads in those scopes, else :doorman/not-found
   (:doorman/signed-out for nobody), before any step; a mark doorman can't
   reach, or of a table the action reads by no sentence, stops the start.
   The options, every key required, are checked first:

     (doorman/put-access-into-handlers handlers {:pool pool :tables tables :scope-kinds scope-kinds
                                                 :find-session-step (doorman/make-find-session-step pool settings)})

   The handler's steps reach the database through :doorman/transaction in
   their context, which carries the message's log (:bridge/log, its
   request id): work a step keeps with cellar's run-after-commit! that
   fails is written there."
  [handlers options]
  (let [{:keys [tables scope-kinds] :as options} (settings/check-access-options options)]
    (check/put-access-into-handlers
     handlers
     (assoc options :conditions (conditions/make-sql-conditions tables scope-kinds)))))

(defn put-gates-on-tables
  "The table maps, each with sentences given :gates (its :operations
   conditions) for cellar to make Postgres's row rules from, and every
   other one :gates nil (no row rules), so each map says its gates. A
   record under a record (:parent) is given, beside its own :references
   and :triggers, the link to its parent (a reference over several
   columns, ON UPDATE CASCADE) and the fill of its carried columns
   (doorman.parents). A table that names a scope (references a scope
   kind's table) must have :access, or :access :public on purpose;
   otherwise this throws.

     (cellar/migrate! pool {:tables     (doorman/put-gates-on-tables tables scope-kinds)
                            :extensions []
                            :functions  (into doorman/function-files   ; with the app's own
                                              (doorman/make-access-functions tables scope-kinds))
                            :views      []}
                      changes migrate-options)"
  [tables scope-kinds]
  (let [conditions    (conditions/make-sql-conditions tables scope-kinds)
        tables-by-key (into {} (map (juxt :table identity)) tables)]
    (mapv (fn [{:keys [table] :as table-map}]
            (-> (if-let [operations (get-in conditions [table :operations])]
                  (assoc table-map :gates operations)
                  (assoc table-map :gates nil))
                (parents/put-link-and-fill-on-table tables-by-key)))
          tables)))

(def refusals
  "doorman's own reasons, each with its data's shape (all nil: none), the
   same on both sides: :doorman/signed-out, nobody is signed in and the
   check refused; :doorman/not-found; :doorman/not-allowed (the check, or
   a request that isn't the app's own). put-access-into-handlers adds them
   to every handler's :refusals, make-my-actions-handler's has them; each
   needs its text in the app's catalogs."
  shapes/refusals)

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
  "A handler, every bridge key written out, answering \"what may I
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
   transaction telling Postgres who is asking (every action), in which its step reads
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
   its :record or :scope); asker: {:person-id :visitor-id :session-flags}.

     (doorman/explain-refusal {:pool pool :handlers handlers :tables tables :scope-kinds scope-kinds}
                              {:person-id anna :visitor-id nil :session-flags #{}}
                              :orders/edit-one {:order/id order-99})
     ;=> {:handler :orders/edit-one :action :orders/edit :writes? true :asker {…}
     ;    :answer :doorman/not-found                          ; :allowed, or the check's reason: :doorman/not-found
     ;                                                        ; :doorman/not-allowed :doorman/signed-out
     ;    :record {:table :orders :id #uuid \"…\" :exists? true
     ;             :scopes [{:column :order/shop-id :scope-kind :shop :scope-id #uuid \"…9\"}]}
     ;    :memberships [{:scope-kind :company :scope-id #uuid \"…\"                    ; there and above
     ;                   :roles [{:role-id #uuid \"…\" :name \"Director\" :actions [:orders/see]}]}]
     ;    :actions-in-scopes [{:scope-kind :shop :scope-id #uuid \"…9\" :actions [:orders/see]}]
     ;    :sentences [{:table :orders :sentence {:allow … :to [:member :order/shop-id]} :holds? false
     ;                 :to {:says [:member :order/shop-id] :holds? false :scope-kind :shop
     ;                      :scope-id #uuid \"…9\" :needs :orders/edit :held-there? false
     ;                      :given-by []}                     ; the roles there or above giving it: {:scope-kind :scope-id :role-id :name :gives}
     ;                 :when {:says [:= :order/status \"open\"] :holds? true}}]
     ;    :denies [{:scope-kind :shop :deny {:deny :writes :when …} :says-no? false :scope-ids []}]}

   A handler with a :record has :scope nil. One with a :scope: :record
   nil, :scope {:says [:shop :shop/id] :scope-kind :shop :scope-id #uuid \"…\"
   :held-there? true :given-by [{:scope-kind :scope-id :role-id :name
   :gives}]} (or {:says :no-scope}), :memberships of the named scope
   (nil for another :scope), and each sentence's :way
   (:anyone :signed-in :visitor [:holds action]) and whether it :holds?
   for the asker (for :no-scope, only an open way can); the :answer is the
   check's. Every handler's :rows-of: its marked params
   ([:uuid {:doorman/row-of …}]), [{:path [:workstation-id] :table
   :workstations :id … :readable? false}], read as the check reads them
   once it lets the message through (an unreadable one makes the :answer
   :doorman/not-found); [] for a handler with no mark, nil when the check
   refuses before them. An unknown handler:
   {:handler … :answer :no-such-handler}."
  [options asker handler-name params]
  (explain/explain-refusal (settings/check-explain-options options) (settings/check-asker asker) handler-name params))

(defn run-as-system!
  "Runs (f transaction) in one transaction of its own, as a named system
   actor: code of the app's that runs with nobody asking (a provider's
   webhook, postbox's functions). The gates hold it like anyone: it reads
   and writes the rows the sentences give it by name, [:system name] (a
   :to word, never a person's), the :anyone ones, and the tables without
   sentences; nothing else. No person, no visitor, no scope held, no
   session flag; the denies, which hold people, don't hold it. The app's
   own role runs it, which owns nothing and passes no gate. options, every
   key required: {:read-only? false :log log}, :log the caller's (a job's,
   a webhook's), which the transaction carries for cellar's
   run-after-commit! (a failure is written there), or nil for one that
   keeps no work. A transaction handed in is refused.

     ;; on shop-emails: {:allow :shop-emails/mark-bounced :to [:system :postbox]}
     (doorman/run-as-system! pool :postbox {:read-only? false :log log}
                             (fn [transaction] (mark-bounced! transaction event)))"
  [pool system-name options f]
  (system/run-as-system! pool system-name (settings/check-system-options options) f))

(defn run-as-asker-of-request!
  "Runs (f transaction) in one transaction of its own, as the asker
   doorman finds in the request, by the find-session step's rules (its
   token, else its cookie and the Origin): code on the app's own route (a
   page that reads rows) held by the gates by the person's scopes and
   actions, the visitor, the session's flags: every action the person
   holds counts, in every scope holding it (no handler narrows it to one
   action and its scopes). A request the step refuses throws
   :doorman/refused-request, with its :reason. options, every key required: {:settings
   settings :read-only? false :scope-kinds scope-kinds :log log} (the
   checked scope kinds; :log the caller's, the request's with its request
   id, which the transaction carries for cellar's run-after-commit!, or
   nil for one that keeps no work). A transaction handed in is refused.

     (doorman/run-as-asker-of-request! pool request {:settings settings :read-only? true :scope-kinds scope-kinds :log log}
                                       (fn [transaction] (cellar/find-rows transaction list-orders {})))"
  [pool request options f]
  (askers/run-as-asker-of-request! pool request (settings/check-asker-run-options options) f))

(defn find-system-leaks
  "The leak test of a named system actor, for an app's own test suite:
   foreign-ids, {:orders [id …]}, rows its sentences don't give it; each
   is read, changed and deleted as the actor, in a transaction undone.
   Returns every row it reached, [{:system :table :id :reached #{:read
   :update :delete}}]; empty means none.

     (is (= [] (doorman/find-system-leaks pool tables :postbox {:orders [order-of-shop-9]})))"
  [pool tables system-name foreign-ids]
  (system/find-system-leaks pool tables system-name foreign-ids))

(defn find-leaks
  "The leak test, for an app's own test suite: every handler (made ready by
   put-access-into-handlers) asked by the person of request (signed in to
   one scope) with foreign-ids, the ids of rows and scopes she must not
   reach, by table, {:orders [id …] :shops [id …]}. A handler with a
   :record must refuse :doorman/not-found for each of its table's
   (:doorman/signed-out when nobody is signed in in request); one with
   :scope [kind param] must refuse the same with each id of its kind's
   :table (the options' :scope-kinds) in its param; any other must give
   none of them back. Its other params come from its :params-shape, by
   malli's generator, with the options' :seed and :size. Each message is
   answered as over http, by the options' :wire, the app's wire, :log,
   the app's log, and :meter, the app's meter, with :metric-buckets,
   bridge's histogram bounds (the settings' own): doorman makes none of
   them. Every option is required.
   Returns every leak; empty means none.

     (is (= [] (doorman/find-leaks ready-handlers anna-request {:orders [order-in-shop-9] :shops [shop-9]}
                                   {:wire wire :log log :meter meter :metric-buckets metric-buckets
                                    :scope-kinds scope-kinds :seed 7 :size 30})))"
  [handlers request foreign-ids options]
  (leaks/find-leaks handlers request foreign-ids (settings/check-leak-options options)))
