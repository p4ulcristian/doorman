# doorman

Who is asking, and what may they do: the door of a Clojure app on
Postgres. **Who is asking:** a session is a row in Postgres; the browser's
cookie holds only random bytes, the table only their fingerprint. The app's
own login proves who someone is and calls `sign-in!`; signing out deletes
rows, so access ends at once on every server. **What may they do:** the
app's kinds of scope (a shop) with their actions and roles; scopes, roles
and memberships as rows; the `:access` sentences on table maps turned into
one SQL condition per action; the check in front of every handler; the
gates (Postgres's row rules) from the same conditions; and the leak test.

```clojure
io.github.p4ulcristian/doorman {:git/tag "v0.3.0" :git/sha "49af9f9"}
```

Needs [cellar](https://github.com/p4ulcristian/cellar) (Postgres, tables as
maps, their gates), [mailroom](https://github.com/p4ulcristian/mailroom) (a
handler's steps), [logbook](https://github.com/p4ulcristian/logbook) (the
message's log), malli with test.check (the settings checked, the leak
test's params) and jsonista. One public namespace, `doorman.api`; its parts
are `doorman.*`. The leak test takes the app's
[wire](https://github.com/p4ulcristian/wire) under `:wire` and its log under
`:log`; doorman makes neither. The find-session step adds the person to the
message's log (mailroom's `:mailroom/log`, `{:person-id …}`), so every later
step's lines say who asked
([why](decisions/2026-10-22-the-session-step-adds-the-person-to-the-log.md)).

## The api

| half | name | does |
|---|---|---|
| who | `settings-shape` | the settings as a malli shape, every key required |
| who | `sessions-table` | its table, `doorman.sessions`, as a map for cellar |
| who | `sign-in!` `sign-out!` `sign-out-everywhere!` | a session made for a proven person (the Set-Cookie text back); ended at once |
| who | `delete-ended-sessions!` | forgets ended sessions; for a nightly background job |
| who | `make-find-session-step` | the step first in a handler: the Origin checked, `:doorman/person-id` in the context and the person in `:mailroom/log` |
| who | `find-session-key-of-request` | a hash of the session cookie, for rate limits |
| what | `access-tables` | its tables, `doorman.scopes` `doorman.roles` `doorman.role_actions` `doorman.memberships` |
| what | `check-scope-kinds` | the app's kinds of scope, checked |
| what | `add-scope!` `add-role!` `add-membership!` `remove-membership!` `list-actions-of-person` | who belongs where; a scope's founder is its Owner, holding every action |
| what | `make-sql-conditions` | the sentences, checked, as SQL: one condition per action and per operation |
| what | `install-access-functions!` | `doorman.find_asking_person()` and `doorman.find_scopes_holding(kind, action)`, before migrate |
| what | `put-access-into-handlers` | the check in front of every handler's steps, which run in a transaction that knows who is asking |
| what | `put-gates-on-tables` | the table maps with their gates, `:gates nil` on the ungated ones, for cellar |
| what | `find-leaks` | the leak test, for the app's own tests, by the app's wire and log (`:wire`, `:log`) |

Nothing has a default: every setting and option is written out; a missing
one refuses, named, with every other problem at once. A handler writes out
every key mailroom reads (`:steps :params-shape :answer-shape :rate-limit
:run-steps-in`); doorman wants both shapes real and `:run-steps-in` nil:
[the decision](decisions/2026-10-22-a-handler-writes-out-mailrooms-keys.md).

```clojure
(require '[doorman.api :as doorman]
         '[cellar.api :as cellar])

(def settings
  {:cookie-name         "app_session"               ; one per app: cookies don't tell ports apart
   :session-days        30                          ; a session unseen this long ends
   :renew-after-minutes 60                          ; a session seen again is pushed out at most this often
   :allowed-origin      "https://shop.example"      ; a request that changes something must come from here
   :secure-cookie?      true})                      ; false only over plain http, in development

(def scope-kinds
  (doorman/check-scope-kinds
   {:shop {:table   :shops
           :actions {:orders/see #{:read} :orders/edit #{:read :insert :update}}
           :roles   {"Staff" #{:orders/see :orders/edit} "Bookkeeper" #{:orders/see}}}}))

(def orders                                         ; :gates comes from put-gates-on-tables
  {:table     :orders
   :columns   [[:order/id :uuid {:primary-key? true}] [:order/shop-id :uuid {:references :shop/id}]]
   :indexes   []
   :on-change nil
   :access    [{:allow #{:orders/see :orders/edit} :to [:member :order/shop-id]}]})

;; the deploy, as the role that owns the tables
(let [tables (into (doorman/put-gates-on-tables [shops orders] scope-kinds)
                   (conj doorman/access-tables doorman/sessions-table))]
  (doorman/install-access-functions! owner-pool)    ; before the gates: they call these
  (cellar/migrate! owner-pool tables changes migrate-options)   ; cellar's time limits for each part
  (cellar/grant-tables-to-role! owner-pool tables "app"))   ; the folder doorman too

;; the app, as its own role, which owns nothing
(def ready
  (doorman/put-access-into-handlers
   {:orders/one {:action       :orders/see
                 :record       [:orders :order/id]
                 :params-shape [:map [:order/id :uuid]]
                 :answer-shape [:map [:order/id :uuid]]
                 :rate-limit   nil                               ; mailroom's, nil for none
                 :run-steps-in nil                               ; nil: doorman's transaction runs them
                 :steps        [find-order add-order-answer]}}   ; they use (:doorman/transaction context)
   {:pool app-pool :tables [shops orders] :scope-kinds scope-kinds
    :find-session-step (doorman/make-find-session-step app-pool settings)}))

;; at the end of the app's own login
(let [{:keys [set-cookie]} (doorman/sign-in! app-pool person-id settings)]
  {:status 302 :headers {"location" "/" "set-cookie" set-cookie}})

;; in the app's tests, with the app's wire (wire.api's make-wire-from-settings) and log (logbook's)
(is (= [] (doorman/find-leaks ready anna-request {:orders [order-in-another-shop]} {:wire wire :log log :seed 7 :size 30})))
```

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
errors during the tests; that is expected.

Why it is the way it is: [`decisions/`](decisions/README.md).

MIT license.
