# doorman is its own library

> **Changed in part** by [The app passes its wire; doorman never makes one](2026-10-22-the-app-passes-its-wire.md) (2026-10-22, v0.2.0): the settings are checked by malli itself, and doorman needs wire no more; `find-leaks` takes the app's wire.

> Changes in part [sessions v1](2026-10-22-sessions-v1.md), [access, piece 1](2026-10-22-access-piece-1-who-belongs-where.md), [piece 2](2026-10-22-access-piece-2-sentences-into-sql.md), [piece 3](2026-10-22-access-piece-3-the-check.md), [piece 4](2026-10-22-access-piece-4-the-gates.md), [piece 5](2026-10-22-access-piece-5-the-leak-test.md) and [Sessions live in Postgres](2026-10-12-sessions-live-in-postgres.md): their names, their keys, and every default they had.

**Decision:** doorman is a library of its own, in its own public repository, under the MIT license; an app or a framework takes it from git by tag. It was two of Groundwork's libraries, sessions (`groundwork.server.sessions`) and access (`groundwork.server.access`); now it is one library, the namespace `doorman`, answering two questions at the door: **who is asking** (a session in Postgres, a cookie of random bytes) and **what may they do** (scopes, roles and memberships, the `:access` sentences turned into SQL conditions, the check in front of every handler, the gates, the leak test).

- **One public namespace, `doorman.api`, holding both halves**; its parts are `doorman.*` (`sessions`, `cookie`, `origin`, `settings`, `memberships`, `scope-kinds`, `sentences`, `conditions`, `check`, `leaks`). The two apis had no name in common, so every public name is kept as it was.
- **It knows nothing of who uses it:**
  - its tables are in their own folder: `doorman.sessions`, `doorman.scopes`, `doorman.roles`, `doorman.role_actions`, `doorman.memberships` (`groundwork.…` before), their keys `:doorman/…`; its table maps say only what cellar reads, and what a framework on top needs on them (Groundwork's `:sync`) it adds itself;
  - who is asking is the per-transaction setting `doorman.asking`, read by `doorman.find_asking_person()` and `doorman.find_scopes_holding(kind, action)`, which `install-access-functions!` makes in the folder doorman;
  - the context's keys are `:doorman/person-id`, `:doorman/transaction`, `:doorman/actions`; its steps are `:doorman/find-session` and `:doorman/check`; what it throws is typed `:doorman/…`.
- **The app's own role uses it.** The app runs as a Postgres role that owns nothing, so the gates hold for it. `cellar/grant-tables-to-role!`, given doorman's tables with the app's, grants it the folder doorman, the rows of its tables and the functions in it, which the gates on the app's own tables call. A test signs in, is checked and passes and fails the gates as such a role.
- **No defaults.** Every value that sets behaviour is the app's, written out:
  - the settings, `settings-shape`, every key required, the map closed:

    ```clojure
    {:cookie-name         "app_session"               ; was groundwork_session, fixed
     :session-days        30
     :renew-after-minutes 60                          ; was an hour, fixed
     :allowed-origin      "https://shop.example"
     :secure-cookie?      true}
    ```

    `make-find-session-step`, `sign-in!` and `sign-out!` check them first (`:doorman/bad-settings`, every problem at once, each named). `find-session-key-of-request` takes them, for the cookie's name.
  - `put-access-into-handlers`'s options `:pool`, `:tables`, `:scope-kinds`, `:find-session-step` are checked, every key required (`:doorman/bad-options`).
  - `find-leaks` takes options, `{:seed 7 :size 30}`, both required: the seed was fixed at 7, the size was malli's own 30, unwritten.
- **Fixed, as the design:** the role "Owner", holding every action of its kind; the cookie's 32 random bytes and its SHA-256 fingerprint; the cookie's `Path=/`, `HttpOnly` and `SameSite=Lax`; the answers "Not found.", "Not allowed." and "This request didn't come from the app's own pages."; which requests need the Origin (every one but a plain GET); a read-only transaction for an action that only reads.

**Why one library, not two:** a request is let in by one door: who is asking and what they may do are asked together, at the start of every handler. Access already took the session step (`:find-session-step`) and read the person it puts in the context; sessions had no user but access and the app's login. Two libraries would be two repositories, two tags and two bumps for every change that crosses the line between them, which most do. One repository, with a `deps.edn` naming only cellar, mailroom, wire, malli, test.check and jsonista, proves it needs nothing more and lets an app take it alone.

**Why no defaults:** Groundwork's "Settings are required data" and "Each library brings its own settings" hold for every library; the app's settings tell the whole story, and a value in effect is never invisible. The cookie's name was a real problem as a constant: cookies don't tell ports apart, so two apps on one host (localhost, in development) signed each other out.

**The price:** a change to doorman is a new tag, and the app moves to it on purpose. An app writes two more settings and the leak test's two options. The decisions it was built on that are about Groundwork as a whole stayed with Groundwork and are named here by title.

**Instead of:**
- two libraries, sessions and access: the one door split in two, each change across them two tags
- staying folders in Groundwork's tree: no app can take them alone, and nothing proves their deps
- defaults the app may override: a good value without thought, but the value in effect is invisible in the app's code
- a name under `groundwork`: doorman isn't Groundwork's
