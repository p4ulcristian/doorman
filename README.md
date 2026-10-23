# doorman

Who is asking, and what may they do: the door of a Clojure app on
Postgres. **Who is asking:** a session is a row in Postgres; the browser's
cookie holds only random bytes, the table only their fingerprint. The app's
own login proves who someone is and calls `sign-in!`, the one door that
names a person; signing out deletes rows, so access ends at once on every
server. A signed-in person makes tokens for themselves, for a script or an
AI acting as them: a call with `Authorization: Bearer <token>` is theirs,
with the same actions, scopes and gates. A visitor (not signed in)
gets a session of their own when the app first keeps something for them;
their rows become the person's at sign-in. **What may they do:** the
app's kinds of scope (a shop under a company, a back office of one scope)
with their actions, roles and denies; scopes, roles and memberships as
rows; the `:access` sentences on table maps turned into one SQL condition
per action; the check in front of every handler; the gates (Postgres's
row rules) from the same conditions; the leak test; and why a message
would be refused, as data.

```clojure
io.github.p4ulcristian/doorman {:git/tag "v0.7.9" :git/sha "8afc3d7"}
```

Needs [cellar](https://github.com/p4ulcristian/cellar) (Postgres, tables as
maps, their gates), [bridge](https://github.com/p4ulcristian/bridge) (a
handler's steps), [logbook](https://github.com/p4ulcristian/logbook) (the
message's log), malli with test.check (the settings checked, the leak
test's params) and jsonista. One public namespace, `doorman.api`; its parts
are `doorman.*`. One more namespace is a REPL's only, never an app's code:
`doorman.repl`'s `make-step-asking-as`, a step in place of the find-session
step that asks as a named asker (a composer's "ask as this person" tool). The leak test takes the app's
[wire](https://github.com/p4ulcristian/wire) under `:wire` and its log under
`:log`; doorman makes neither. The find-session step adds the person to the
message's log (bridge's `:bridge/log`, `{:person-id …}`), so every later
step's lines say who asked
([why](decisions/2026-10-22-the-session-step-adds-the-person-to-the-log.md)).

## The api

| half | name | does |
|---|---|---|
| who | `settings-shape` | the settings as a malli shape, every key required |
| who | `sessions-table` `tokens-table` | its tables, `doorman.sessions` and `doorman.tokens`, as maps for cellar |
| who | `sign-in!` `sign-out!` `sign-out-everywhere!` | a session made for a proven person, with its flags (the Set-Cookie text back), a visitor's rows moved to them and the request's old session ended, in one transaction; ended at once; everywhere: every session and token of the person |
| who | `make-token!` `list-tokens` `revoke-token!` | a token for the request's signed-in person (a session without flags; not a token): its text shown once, only its SHA-256 kept, a name and an end date required; their live tokens listed (never the text); one ended at once ([why](decisions/2026-10-23-doorman-owns-tokens-and-only-sign-in-names-a-person.md)) |
| who | `delete-ended-tokens!` | forgets ended tokens; for a nightly background job |
| who | `start-visitor-session!` | a session for a visitor, on the app's own route: its id, which the rows kept for them name, and the Set-Cookie text |
| who | `delete-ended-sessions!` | forgets ended sessions; for a nightly background job |
| who | `make-find-session-step` | the step first in a handler: who asks, by a bearer token (no Origin check; no session flags) or the session cookie (the Origin checked), both refused; `:doorman/person-id`, `:doorman/visitor-id` and `:doorman/session-flags` in the context, the person (and token) or visitor in `:bridge/log` |
| who | `run-as-asker-of-request!` | `(f transaction)` as the asker doorman finds in a request, by the step's rules, outside a message (the app's own route, a page): held by the gates as a message's steps are |
| who | `find-session-key-of-request` | a hash of the request's token, else its session cookie, for rate limits |
| what | `access-tables` | its tables, `doorman.scopes` `doorman.roles` `doorman.role_actions` `doorman.memberships` |
| what | `check-scope-kinds` | the app's kinds of scope, checked, every key written out |
| what | `does-action-only-read?` | whether an action only reads (its handlers run read-only); every other one's may write |
| what | `add-scope!` `add-role!` `add-membership!` `remove-membership!` `list-actions-of-person` | who belongs where; a scope under its parent scope; a scope's founder is its Owner, holding every action of its kind and below; a membership counts in the scopes below |
| what | `make-my-actions-handler` `my-actions-answer-shape` (the shape on both sides: `doorman.api`'s `.cljs` half has it too) | "what may I do?" for the browser: the asker's actions by scope, and the open ones under nil kind and id, every scope; the denies taken out; read-only, as the asker |
| what | `make-sql-conditions` | the sentences, checked, as SQL: one condition per action and per operation, the denies in them |
| what | `install-access-functions!` | the SQL functions the check and the gates call, and those the app's sentences and denies need (a `:via`'s, a scope state's, the guard's, `doorman."may <action> on <table>"()`), before migrate |
| what | `put-access-into-handlers` | every handler says where it acts (`:record`, or `:scope` `[kind param]`, `:every-scope-holding-the-action`, `:no-scope`); the check in front of its steps, which run in a transaction where Postgres hears only the handler's action, in those scopes ([why](decisions/2026-10-23-a-handler-says-where-it-acts-and-postgres-hears-only-that.md)) |
| what | `put-gates-on-tables` | the table maps with their gates, `:gates nil` on the ungated ones, and a record under a record (`:parent`) given its link to the parent and the fill of its carried columns, for cellar |
| what | `find-leaks` | the leak test, for the app's own tests, by the app's wire and log (`:wire`, `:log`); a record or scope it must not reach must be refused `:doorman/not-found` (`:doorman/signed-out` for an asker nobody signed in); a `:scope [kind param]` handler asked in each foreign scope (`:scope-kinds`) |
| what | `refusals` (both sides; the browser's half has `put-refusals-into-shapes` too) | doorman's reasons, `:doorman/signed-out` `:doorman/not-found` `:doorman/not-allowed`, added to every handler it wraps |
| what | `run-as-system!` | `(f transaction)` as a named system actor (a webhook's function): the rows the sentences give it by `[:system name]`, the open ones and the ungated tables, nothing else; the app's own role, no role that passes the gates |
| what | `find-system-leaks` | the leak test of a system actor: the rows it must not reach, read, changed and deleted as it, undone |
| what | `explain-refusal` | why a message would be refused, as data: the check's answer, the record's scopes, the asker's roles and actions there, each sentence's parts and the denies; read-only, for a REPL |

Nothing has a default: every setting and option is written out; a missing
one refuses, named, with every other problem at once. A handler writes out
every key bridge reads (`:steps :params-shape :answer-shape :refusals
:rate-limit :run-steps-in`); doorman wants both shapes real and `:run-steps-in` nil:
[the decision](decisions/2026-10-22-a-handler-writes-out-mailrooms-keys.md).

The check refuses with doorman's reasons, `doorman/refusals` (bridge's
named refusals; [why](https://github.com/p4ulcristian/bridge/blob/tidy-up/decisions/2026-10-23-a-refusal-is-a-named-reason-declared-on-its-handler.md)):
`:doorman/not-found` (the asker may not reach the record or scope, nor see
it), `:doorman/not-allowed` (they may see it, but not do this; a session
flag or a scope's state says no; a request from another Origin, or with a
cookie and a token at once), and `:doorman/signed-out` in place of either
when nobody is signed in (nobody, a visitor, a wrong, ended or revoked
token), whatever the record, so it tells nothing of whether one exists.
doorman adds the three to every handler it wraps; the app never writes
them. In the browser, `doorman/put-refusals-into-shapes` adds them to the
app's shapes by name. Each needs its text in the app's catalogs.

```clojure
(require '[doorman.api :as doorman]
         '[cellar.api :as cellar])

(def settings
  {:cookie-name          "app_session"              ; one per app: cookies don't tell ports apart
   :session-days         30                         ; a person's session unseen this long ends
   :visitor-session-days 7                          ; a visitor's too; then their rows are nobody's
   :renew-after-minutes  60                         ; a session seen again is pushed out at most this often
   :allowed-origin       "https://shop.example"     ; a request that changes something must come from here
   :secure-cookie?       true                       ; false only over plain http, in development
   :token-lead           "shop_"                    ; a token's text starts with it, then 43 random characters
   :token-shown-characters 11                       ; the list shows a token's first 11: the lead and 6 more
   :token-longest-days   365})                      ; a token ends at most this far from when it is made

(def scope-kinds
  (doorman/check-scope-kinds
   {:company  {:table :companies :parent nil :single? false
               :actions {:company/report #{:read}}
               :roles   {"Director" #{:company/report :orders/see}}      ; a kind below's action: in each shop
               :denies  []}
    :shop     {:table :shops :parent :company :single? false
               :actions {:orders/see #{:read} :orders/edit #{:read :insert :update}}
               :roles   {"Staff" #{:orders/see :orders/edit} "Bookkeeper" #{:orders/see}}
               :denies  [{:deny :writes :session :impersonated}                  ; an admin acting as someone
                         {:deny :writes :when [:= :shop/status "suspended"]}]}  ; keeps its reads
    :platform {:table nil :parent nil :single? true                              ; exactly one scope
               :actions {:platform/support #{:read}} :roles {"Support" #{:platform/support}} :denies []}}))

(def orders                                         ; :gates comes from put-gates-on-tables
  {:table     :orders
   :columns   [[:order/id :uuid {:primary-key? true}]
               [:order/shop-id :uuid {:references :shop/id}]
               [:order/customer-id :uuid {:optional? true}]
               [:order/visitor-id :uuid {:optional? true}]
               [:order/status :string]]
   :indexes   [[:order/shop-id]]
   :references []
   :triggers   []
   :on-change nil
   :visitor   {:column :order/visitor-id :moves-to :order/customer-id}   ; moved at sign-in
   :access    [{:allow #{:orders/see :orders/edit} :only :all-of-the-action :to [:member :order/shop-id]}
               {:allow #{:orders/see :orders/edit} :only :all-of-the-action :to [:me :order/customer-id]}
               {:allow #{:orders/see :orders/edit} :only :all-of-the-action :to [:me :order/visitor-id]}   ; the visitor asking
               {:allow :orders/see :only :all-of-the-action :to [:member :platform :platform/support]}
               {:allow :orders/see :only :all-of-the-action
                :via {:table :agreements                                                            ; a related row exists
                      :match {:order/shop-id :agreement/shop-id}
                      :to    [:member :agreement/partner-id :agreements/see]
                      :when  [:= :agreement/status "active"]}}]})

;; on workstations: editing an order reads them, and only reads
;;   {:allow :orders/edit :only #{:read} :to [:member :workstation/shop-id]}
;; on shops, the scope's own row:
;;   {:allow :shops/edit :only :all-of-the-action :to [:member :shop/id]}

;; the deploy, as the role that owns the tables
(let [tables (into (doorman/put-gates-on-tables app-tables scope-kinds)
                   (conj doorman/access-tables doorman/sessions-table doorman/tokens-table))]
  (doorman/install-access-functions! owner-pool app-tables scope-kinds)   ; before the gates: they call these
  (cellar/migrate! owner-pool tables changes migrate-options)   ; cellar's time limits for each part
  (cellar/grant-tables-to-role! owner-pool tables "app"))   ; the folder doorman too

;; the app, as its own role, which owns nothing
(def ready
  (doorman/put-access-into-handlers
   {:orders/one   {:action       :orders/see
                   :record       [:orders :order/id]               ; one row: it acts in that row's shop
                   :params-shape [:map [:order/id :uuid]]
                   :answer-shape [:map [:order/id :uuid]]
                   :refusals     {:order/archived nil}             ; bridge's: the steps' own reasons; doorman adds its three
                   :rate-limit   nil                               ; bridge's, nil for none
                   :run-steps-in nil                               ; nil: doorman's transaction runs them
                   :steps        [find-order add-order-answer]}    ; they use (:doorman/transaction context)
    :orders/place {:action :orders/edit
                   :scope  [:shop :order/shop-id]                  ; the shop the param names: held there, or :doorman/not-found
                   …}
    :orders/mine  {:action :orders/see
                   :scope  :every-scope-holding-the-action         ; or :no-scope: :anyone, :signed-in, [:me …] only
                   …}}
   {:pool app-pool :tables app-tables :scope-kinds scope-kinds
    :find-session-step (doorman/make-find-session-step app-pool settings)}))

;; "what may I do?", for the browser (parlor's "can I?"), as the asker, read-only
(def handlers-with-my-actions
  (assoc ready :doorman/my-actions
         (doorman/make-my-actions-handler {:pool app-pool :tables app-tables :scope-kinds scope-kinds
                                           :find-session-step (doorman/make-find-session-step app-pool settings)
                                           :rate-limit {:max 60 :per-ms 60000}})))   ; or nil
;=> [{:scope-kind nil :scope-id nil :actions […]} {:scope-kind :shop :scope-id #uuid "…" :actions [:orders/edit :orders/see]}]

;; a visitor, on the app's own route, the first time it keeps something for them
(let [{:keys [visitor-id set-cookie]} (doorman/start-visitor-session! app-pool request settings)]
  …)   ; the cart's row names visitor-id; set-cookie is nil when the request had a session already

;; at the end of the app's own login: the visitor's rows become the person's
(let [{:keys [set-cookie]} (doorman/sign-in! app-pool request person-id
                                             {:settings settings :tables app-tables :session-flags #{}})]
  {:status 302 :headers {"location" "/" "set-cookie" set-cookie}})

;; a signed-in person makes a token, in a message's step or on the app's own route;
;; the text is in the answer this once
(doorman/make-token! app-pool request {:settings settings :name "catalog script"
                                       :ends-at (.plus (Instant/now) (Duration/ofDays 90))})
;=> {:token-id #uuid "…" :text "shop_q3V…" :shown-start "shop_q3V9xK" :ends-at #inst "…"}
;; a script's call with it:  Authorization: Bearer shop_q3V…   is Anna's, with no session flags
(doorman/list-tokens app-pool request settings)              ;=> [{:token-id … :name "catalog script" :shown-start "shop_q3V9xK" …}]
(doorman/revoke-token! app-pool request settings token-id)   ;=> true

;; a page reading rows as whoever asks (its cookie, or a token), held by the gates
(doorman/run-as-asker-of-request! app-pool request {:settings settings :read-only? true :scope-kinds scope-kinds}
                                  (fn [transaction] (cellar/find-rows transaction list-orders {})))

;; in the REPL: why would Anna's message be refused?
(doorman/explain-refusal {:pool app-pool :handlers handlers :tables app-tables :scope-kinds scope-kinds}
                         {:person-id anna :visitor-id nil :session-flags #{}}
                         :orders/one {:order/id order-in-another-shop})
;=> {:answer :doorman/not-found :record {…} :memberships […] :sentences [{… :to {:holds? false :needs :orders/see …}}] :denies […]}

;; in the app's tests, with the app's wire (wire.api's make-wire-from-settings) and log (logbook's)
(is (= [] (doorman/find-leaks ready anna-request {:orders [order-in-another-shop] :shops [another-shop]}
                              {:wire wire :log log :scope-kinds scope-kinds :seed 7 :size 30})))
```

The words of a sentence: `:to` is `:anyone`, `:signed-in`, `[:member
column]` or `[:member <kind of one scope>]` (either with the action held
there as a third element), `[:me column]` (the person, or the visitor for
the table's `:visitor` column), `[:system name]` (a named system actor,
`run-as-system!`, never a person;
[why](decisions/2026-10-23-a-named-system-actor-is-allowed-what-its-sentences-give-it.md)),
or `:via {…}` instead of `:to`; `:when` is
`[:= col v]`, `[:not= col v]`, `[:in col [vs]]`, `[:none col]`, `[:and …]`,
`[:or …]`; `:only`, on every sentence, is `:all-of-the-action` (every
operation its kind declares) or the operations it allows on this table,
`#{:read}`. A `[:member column]` names the column's scope, or, on a scope
kind's own table, its primary key names the row itself, the scope. A
handler's steps act as its action alone: Postgres counts no other
action's sentences, and only the scopes the handler acts in. A list narrowed to the rows the asker may do an action on:
`WHERE order__id IN (SELECT * FROM doorman."may orders/edit on orders"())`.
A record under a record carries its parent's columns: the table map says
`:parent {:column :job/order-id :carries {:job/shop-id :order/shop-id}}`,
and `put-gates-on-tables` gives it cellar's link to the parent (`ON UPDATE
CASCADE`: a parent's change is carried down by Postgres, past the gates)
and a fill of the carried columns on insert, so the app's SQL never names
them and its sentences compare the child's own column
([why](decisions/2026-10-23-records-under-records-carry-their-parents-columns.md)).

## Tests

```sh
clojure -X:test     # the Postgres tests are skipped without a database

# with a throwaway Postgres:
initdb -D /tmp/doorman-pg -A trust -U "$USER"
pg_ctl -D /tmp/doorman-pg -o "-p 5499 -k /tmp -c listen_addresses=localhost" -l /tmp/doorman-pg.log start
DOORMAN_TEST_PORT=5499 DOORMAN_TEST_USER=$USER clojure -X:test
pg_ctl -D /tmp/doorman-pg stop
```

A gate refusing a row and a write in a read-only transaction log their
errors during the tests; that is expected. The tests need a superuser
for `DOORMAN_TEST_USER` (it makes the role `doorman_app`, which owns
nothing, and `doorman_owner`, not a superuser, which owns the tables of
the system actor's and the records-under-records tests, as in production;
and it writes the test rows past the gates).

Why it is the way it is: [`decisions/`](decisions/README.md).

MIT license.
