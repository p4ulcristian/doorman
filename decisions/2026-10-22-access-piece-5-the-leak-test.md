# access, piece 5: the leak test

> **Changed in part** by [A refusal is a named reason, declared on its handler](https://github.com/p4ulcristian/bridge/blob/tidy-up/decisions/2026-10-23-a-refusal-is-a-named-reason-declared-on-its-handler.md) (bridge's, 2026-10-23, built on tidy-up, not released): a record or scope the asker must not reach is refused `:doorman/not-found` (`:doorman/signed-out` for an asker nobody signed in), compared by the reason, not "Not found.".

> **Changed in part** by [A handler says where it acts, and Postgres hears only that](2026-10-23-a-handler-says-where-it-acts-and-postgres-hears-only-that.md) (2026-10-23, built on tidy-up, not released): a `:scope [kind param]` handler is asked with each foreign id of its kind's `:table` and must answer "Not found."; `find-leaks` takes the scope kinds, `:scope-kinds`, required.

> **Changed in part** by [The app passes its wire; doorman never makes one](2026-10-22-the-app-passes-its-wire.md) (2026-10-22, v0.2.0): `find-leaks` takes the app's wire, `:wire`, in its options, for mailroom's `answer-message`.

> **Changed in part** by [doorman is its own library](2026-10-22-doorman-is-its-own-library.md) (2026-10-22): `find-leaks` takes options, `{:seed … :size …}`, both required (seed 7 and malli's size 30 before, unwritten).

> Builds on [doorman ships permissions](2026-10-12-doorman-ships-permissions.md) (the built-in leak test).

**Decision:** `find-leaks` is for an app's own tests. Given its handlers (made ready by `put-access-into-handlers`), a request signed in as a person of one scope, and the ids of another scope's rows by table, it asks every handler: one with a `:record` is asked for each foreign row and must answer "Not found."; any other is asked once and its answer must hold none of the foreign ids. The other params come from the handler's `:params-shape`, by malli's generator. It returns every leak; empty means none. transport's `answer-message` answers a message without a server, which the leak test uses.

**Why:** the check and the gates are proven for every handler an app has, not just the ones someone thought to test. The test was itself proven against a handler that reads past the gates on purpose: it finds the foreign order in its answer.

**The price:** access depends on test.check, for malli's generator. A handler whose foreign rows are reached another way than its `:record` or its answer (a write that changes them) isn't covered yet. The app gives the foreign ids; Groundwork doesn't make the second scope's data.

**Instead of:** each app writing its own leak tests per handler: a new handler without one is untested.
