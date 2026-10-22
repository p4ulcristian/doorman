# A record of two scopes may have a parent and children

> Moved from Groundwork (2026-10-22): "Groundwork" there is doorman here where it means access. Who is rung for a row became doorbell's, which tells every open screen by table.

> **Changed in part** by [Access rules are sentences in doorman's language](2026-10-17-access-rules-are-sentences-in-doormans-language.md) (2026-10-17): who sees and is rung for such a row is what the app's sentences say, not every side it carries.

> Adds to [Every scoped table names its scope](2026-10-12-every-scoped-table-names-its-scope.md).

**Decision:** the rule that every row carries its own scope, tied to its parent's by a matching link and filled in by the library, holds for records of two scopes too.

- **Both scopes from the parent.** A record under a two-scope record carries both of its parent's scopes (a price sheet under an agreement between a shop and a partner carries the shop and the partner). Both are filled from the parent and tied to it, so they can never disagree.
- **One scope from the parent, one of its own.** A record under a one-scope record may name a second scope itself (a service on an order carries the order's shop, and names the partner who does it). The first is filled and tied; the second is the app's to write, and may wait empty.

Either side sees the row and is rung for it; nobody else. Seeing the row gives nothing else: a partner who sees a service on an order doesn't see the order.

**Why:** before, a record of two scopes could have nothing above or below it, so the rows that hang under an agreement had no place behind the wall. Reaching the scopes through the parent on every read is the way already turned down for one scope: it slows with depth and with long lists.

**The price:** two scope columns on such tables, and a wider matching link.

**Instead of:** finding a child's scopes through its parent for every row.
