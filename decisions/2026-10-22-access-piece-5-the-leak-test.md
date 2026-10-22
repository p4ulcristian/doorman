# access, piece 5: the leak test

> **Changed in part** by [doorman is its own library](2026-10-22-doorman-is-its-own-library.md) (2026-10-22): `find-leaks` takes options, `{:seed … :size …}`, both required (seed 7 and malli's size 30 before, unwritten).

> Builds on Groundwork's "Groundwork ships permissions" (the built-in leak test).

**Decision:** `find-leaks` is for an app's own tests. Given its handlers (made ready by `put-access-into-handlers`), a request signed in as a person of one scope, and the ids of another scope's rows by table, it asks every handler: one with a `:record` is asked for each foreign row and must answer "Not found."; any other is asked once and its answer must hold none of the foreign ids. The other params come from the handler's `:params-shape`, by malli's generator. It returns every leak; empty means none. transport's `answer-message` answers a message without a server, which the leak test uses.

**Why:** the check and the gates are proven for every handler an app has, not just the ones someone thought to test. The test was itself proven against a handler that reads past the gates on purpose: it finds the foreign order in its answer.

**The price:** access depends on test.check, for malli's generator. A handler whose foreign rows are reached another way than its `:record` or its answer (a write that changes them) isn't covered yet. The app gives the foreign ids; Groundwork doesn't make the second scope's data.

**Instead of:** each app writing its own leak tests per handler: a new handler without one is untested.
