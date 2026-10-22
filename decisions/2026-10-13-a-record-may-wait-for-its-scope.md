# A record may wait for its scope

> **Changed in part** by [Shared rows may belong to nobody](2026-10-16-shared-rows-may-belong-to-nobody.md) (2026-10-16): an empty scope column can also mean a row for everyone, where a `[:none <column>]` sentence says so.

**Decision:** a record kind on its own (no parent, nothing hanging under it) may leave its scope column empty. Such a row is in no scope through that column yet: no member sees it, only the people named on it and the system. A record under another record, and a record others hang under, keep their scope columns NOT NULL, because their matching link only holds then.

**Why:** some records exist before they belong anywhere: an order placed before it is routed to a shop. Forcing a scope on them would mean a placeholder scope, which is a way to leak. An empty column is the honest state, and the wall already reads it as "nobody's yet".

**Instead of:** every scope column NOT NULL, which forces such tables into a model change or a fake scope.
