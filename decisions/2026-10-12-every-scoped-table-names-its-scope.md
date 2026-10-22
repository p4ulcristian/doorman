# Every scoped table names its scope

> **Waits** for cellar: [Records under records wait for cellar's matching links](2026-10-23-records-under-records-wait-for-cellar.md) (2026-10-23).

**Decision:** every table whose rows belong to a scope has its own scope column, however deep it sits (a job photo names its shop, not only its job). It is tied to the parent by a matching link: the child's (parent, scope) pair must match the parent's own, so the copy can never disagree. Groundwork checks at start that each table in the permission description has the column and the link, and refuses to start otherwise. It also installs, at start, a rule that fills the scope from the parent when a row is inserted, so the app's SQL never mentions it; and the link carries a parent's scope change down to its children in the same transaction.

**Why:** without it, the database wall, list filters and live-update bells follow the chain to the scope for every row (a job looks up its order to find its shop), which multiplies with depth and on long lists. With it, every row is one direct comparison away from its scope, and an index helps.

**The price:** one column per deep table, a one-time migration when an existing app moves, and a little more checking on every insert.

**Instead of:** reaching the scope through the parent chain for every row, which is correct but slows with depth and size.
