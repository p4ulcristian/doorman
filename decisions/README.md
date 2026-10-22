# doorman decisions

One file per decision: `YYYY-MM-DD-what-we-decided.md`. The title is the decision. Each file says what we decided, why, and what we chose against. A decision changes by a new file; the old one says so at the top (`Replaced by` or `Changed in part by`), and nothing else in it is rewritten.

All but the last two were made while doorman was two libraries inside Groundwork, a Clojure framework (groundwork.server.sessions and groundwork.server.access), or before them, in Groundwork's design; they name its parts (its tables, the bells, transport) where they came from. The ones they name that stayed with Groundwork are named by title, in plain text: "Shared rows have an owner", "What every endpoint declares", "A table is one map", "Production migrates before it starts", "Groundwork's own tables are table maps, in their own folder", "Groundwork knows nothing about tenants", "Groundwork is frozen", "The freeze lifts for the access language", "A row can be a pass to one record". Seven moved from Groundwork's own decisions on 2026-10-22, "Groundwork" in them became doorman where it meant sessions or access; Groundwork keeps a short copy of those that also said what Groundwork ships, its routes or its sync. Groundwork is now house, the part of manor (the group of libraries this one belongs to) that puts them together; the decisions that stayed with Groundwork are in house's `decisions/`.

## Who is asking

- [Sessions live in Postgres](2026-10-12-sessions-live-in-postgres.md), changed in part by [doorman is its own library](2026-10-22-doorman-is-its-own-library.md)
- [The session is the only truth](2026-10-12-the-session-is-the-only-truth.md)
- [doorman ships no logins](2026-10-12-doorman-ships-no-logins.md), moved from Groundwork
- [Sessions for visitors, and none for servers](2026-10-12-sessions-for-visitors-and-none-for-servers.md), moved from Groundwork (its sessions part)
- [sessions v1: signed-in sessions, a step to find them](2026-10-22-sessions-v1.md), changed in part by [doorman is its own library](2026-10-22-doorman-is-its-own-library.md) and [The session step adds the person to the message's log](2026-10-22-the-session-step-adds-the-person-to-the-log.md)

## What may they do

- [doorman ships permissions](2026-10-12-doorman-ships-permissions.md), moved from Groundwork, changed in part by [Who may see what is the app's](2026-10-15-who-may-see-what-is-the-apps.md) and [Access rules are sentences in doorman's language](2026-10-17-access-rules-are-sentences-in-doormans-language.md)
- [Who may see what is the app's; doorman enforces it](2026-10-15-who-may-see-what-is-the-apps.md), moved from Groundwork, changed in part by [Access rules are sentences in doorman's language](2026-10-17-access-rules-are-sentences-in-doormans-language.md)
- [Access rules are sentences in doorman's language](2026-10-17-access-rules-are-sentences-in-doormans-language.md), moved from Groundwork, changed in part by Groundwork's "A table is one map"
- [A record of two scopes may have a parent and children](2026-10-13-a-record-of-two-scopes-may-have-a-parent-and-children.md), moved from Groundwork, changed in part by [Access rules are sentences in doorman's language](2026-10-17-access-rules-are-sentences-in-doormans-language.md)
- [A record between fixed sides carries them](2026-10-13-a-record-between-fixed-sides-carries-them.md), moved from Groundwork, changed in part by [Access rules are sentences in doorman's language](2026-10-17-access-rules-are-sentences-in-doormans-language.md)
- [Every scoped table names its scope](2026-10-12-every-scoped-table-names-its-scope.md)
- [A record may wait for its scope](2026-10-13-a-record-may-wait-for-its-scope.md), changed in part by [Shared rows may belong to nobody](2026-10-16-shared-rows-may-belong-to-nobody.md)
- [A record can belong to a visitor](2026-10-13-a-record-can-belong-to-a-visitor.md), changed in part by [Access rules are sentences in doorman's language](2026-10-17-access-rules-are-sentences-in-doormans-language.md)
- [Shared rows may belong to nobody](2026-10-16-shared-rows-may-belong-to-nobody.md)
- [One request may name several scopes](2026-10-18-one-request-may-name-several-scopes.md)
- [access, piece 1: who belongs where](2026-10-22-access-piece-1-who-belongs-where.md), changed in part by [doorman is its own library](2026-10-22-doorman-is-its-own-library.md)
- [access, piece 2: sentences into SQL conditions](2026-10-22-access-piece-2-sentences-into-sql.md), changed in part by [doorman is its own library](2026-10-22-doorman-is-its-own-library.md)
- [access, piece 3: the check, put in front of every handler](2026-10-22-access-piece-3-the-check.md), changed in part by [doorman is its own library](2026-10-22-doorman-is-its-own-library.md) and [A handler writes out mailroom's keys](2026-10-22-a-handler-writes-out-mailrooms-keys.md)
- [access, piece 4: the gates](2026-10-22-access-piece-4-the-gates.md), changed in part by [doorman is its own library](2026-10-22-doorman-is-its-own-library.md)
- [access, piece 5: the leak test](2026-10-22-access-piece-5-the-leak-test.md), changed in part by [doorman is its own library](2026-10-22-doorman-is-its-own-library.md) and [The app passes its wire](2026-10-22-the-app-passes-its-wire.md)

## The library

- [doorman is its own library](2026-10-22-doorman-is-its-own-library.md), changed in part by [The app passes its wire](2026-10-22-the-app-passes-its-wire.md)
- [A handler writes out mailroom's keys, and its :run-steps-in is nil](2026-10-22-a-handler-writes-out-mailrooms-keys.md)
- [The app passes its wire; doorman never makes one](2026-10-22-the-app-passes-its-wire.md), changed in part by [The session step adds the person to the message's log](2026-10-22-the-session-step-adds-the-person-to-the-log.md)
- [The session step adds the person to the message's log; the leak test takes the app's log](2026-10-22-the-session-step-adds-the-person-to-the-log.md)
