# doorman decisions

One file per decision: `YYYY-MM-DD-what-we-decided.md`. The title is the decision. Each file says what we decided, why, and what we chose against. A decision changes by a new file; the old one says so at the top (`Replaced by` or `Changed in part by`), and nothing else in it is rewritten.

All but the last were made while doorman was two libraries inside Groundwork, a Clojure framework (groundwork.server.sessions and groundwork.server.access), or before them, in Groundwork's design; they name its parts (its tables, the bells, transport) where they came from. The ones they name that stayed with Groundwork are named by title, in plain text: "Groundwork ships no logins", "Groundwork ships permissions", "Who may see what is the app's; Groundwork enforces it", "Access rules are sentences in Groundwork's language", "Pages for other sites, visitors, and servers", "Shared rows have an owner", "What every endpoint declares", "A table is one map", "Production migrates before it starts", "Groundwork's own tables are table maps, in their own folder".

## Who is asking

- [Sessions live in Postgres](2026-10-12-sessions-live-in-postgres.md), changed in part by [doorman is its own library](2026-10-22-doorman-is-its-own-library.md)
- [The session is the only truth](2026-10-12-the-session-is-the-only-truth.md)
- [sessions v1: signed-in sessions, a step to find them](2026-10-22-sessions-v1.md), changed in part by [doorman is its own library](2026-10-22-doorman-is-its-own-library.md)

## What may they do

- [Every scoped table names its scope](2026-10-12-every-scoped-table-names-its-scope.md)
- [A record may wait for its scope](2026-10-13-a-record-may-wait-for-its-scope.md), changed in part by [Shared rows may belong to nobody](2026-10-16-shared-rows-may-belong-to-nobody.md)
- [A record can belong to a visitor](2026-10-13-a-record-can-belong-to-a-visitor.md), changed in part by Groundwork's "Access rules are sentences in Groundwork's language"
- [Shared rows may belong to nobody](2026-10-16-shared-rows-may-belong-to-nobody.md)
- [One request may name several scopes](2026-10-18-one-request-may-name-several-scopes.md)
- [access, piece 1: who belongs where](2026-10-22-access-piece-1-who-belongs-where.md), changed in part by [doorman is its own library](2026-10-22-doorman-is-its-own-library.md)
- [access, piece 2: sentences into SQL conditions](2026-10-22-access-piece-2-sentences-into-sql.md), changed in part by [doorman is its own library](2026-10-22-doorman-is-its-own-library.md)
- [access, piece 3: the check, put in front of every handler](2026-10-22-access-piece-3-the-check.md), changed in part by [doorman is its own library](2026-10-22-doorman-is-its-own-library.md) and [A handler writes out mailroom's keys](2026-10-22-a-handler-writes-out-mailrooms-keys.md)
- [access, piece 4: the gates](2026-10-22-access-piece-4-the-gates.md), changed in part by [doorman is its own library](2026-10-22-doorman-is-its-own-library.md)
- [access, piece 5: the leak test](2026-10-22-access-piece-5-the-leak-test.md), changed in part by [doorman is its own library](2026-10-22-doorman-is-its-own-library.md)

## The library

- [doorman is its own library](2026-10-22-doorman-is-its-own-library.md)
- [A handler writes out mailroom's keys, and its :run-steps-in is nil](2026-10-22-a-handler-writes-out-mailrooms-keys.md)
