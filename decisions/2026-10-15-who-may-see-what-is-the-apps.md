# Who may see what is the app's; doorman enforces it

> Moved from Groundwork (2026-10-22), where it was "Who may see what is the app's; Groundwork enforces it": the enforcing was Groundwork's then and is doorman's now.

> **Changed in part** by [Access rules are sentences in doorman's language](2026-10-17-access-rules-are-sentences-in-doormans-language.md) (2026-10-17): how the app says its rules, left open here, is decided there, and built.

> Changes [doorman ships permissions](2026-10-12-doorman-ships-permissions.md) in part. Decided while Groundwork was frozen (Groundwork's "Groundwork is frozen").

**Decision:** the line between the business and everything else runs through permissions too.

- **The app's:** who may see and do what. Which records a partner reaches, whether platform staff see every scope, what a shared record gives the other side, which rows everyone may read. The app says it in its own words, next to its own tables.
- **doorman's:** enforcing it, the same for every app. The wall in Postgres, telling the database who is asking, refusing a database role that passes by the wall, a handler without a permission doesn't start, every id in a request checked, "not found" for another scope's record, and the leak test that proves nothing crosses.

How the app says it is not decided yet. Groundwork's bells (now doorbell) learned who to ring from it too, so they followed whatever form it took.

**Why:** the rule shapes tried to answer business questions in the library's own vocabulary. Every new business case became a new shape and a new decision, and the one large app still didn't fit after six new shapes in one day. A business case belongs to the business; only how it is enforced is shared.

**The price:** each app writes its own access rules, so a wrong rule is the app's mistake; the wall and the leak test are what catch it.

**Instead of:** a general shape for every way of granting access, decided and built one at a time as apps ask for them.
