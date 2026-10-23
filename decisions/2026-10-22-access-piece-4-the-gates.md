# access, piece 4: the gates

> **Changed in part** by [A handler says where it acts, and Postgres hears only that](2026-10-23-a-handler-says-where-it-acts-and-postgres-hears-only-that.md) (2026-10-23, built on tidy-up, not released): a person's sentence counts in a gate only while its action is the one the handler acts as (`doorman.is_acting(action)`), and only for the operations its `:only` allows on the table; Postgres is told only the handler's action, in the scopes it acts in.

> **Added to** by [doorman reads past its gates only inside its own functions](2026-10-23-doorman-reads-past-its-gates-only-inside-its-own-functions.md), [Visitors get sessions of their own](2026-10-23-visitors-get-sessions-and-their-rows-move-at-sign-in.md) and [:via reads related rows through a function](2026-10-23-via-reads-related-rows-through-a-function-and-guards-what-they-match.md) (2026-10-23, v0.7.0): every read gate lets doorman's own functions read; a table with a `:visitor` column lets sign-in's move through; a table a via reads guards what it matches; `install-access-functions!` takes the tables and the scope kinds.

> **Changed in part** by [doorman is its own library](2026-10-22-doorman-is-its-own-library.md) (2026-10-22): the gates' functions are in the folder doorman; cellar's `grant-tables-to-role!`, given doorman's tables, lets the app's own role use that folder and call them.

> Builds on [doorman ships permissions](2026-10-12-doorman-ships-permissions.md) (the Postgres wall), Groundwork's "Production migrates before it starts" and Groundwork's "Groundwork's own tables are table maps, in their own folder".

**Decision:**

- `put-gates-on-tables` gives each table map with sentences its `:gates`: the per-operation conditions of piece 2. groundwork.schema makes them: row-level security on and forced, one policy per operation (`USING` for which rows may be seen or touched, `WITH CHECK` for what a new or changed row must still meet); an operation no action does has no policy, which Postgres reads as no.
- A policy's name ends in a hash of its SQL, so a changed sentence is a new policy name: schema drops the old and makes the new, comparing names, never SQL text.
- A table that names a scope (references a scope kind's table) must have `:access`, or say `:access :public` on purpose; otherwise `put-gates-on-tables` throws. A table map without gates has row security off.
- The app runs as a role of its own that owns nothing; `grant-tables-to-role!` (schema) lets it use the tables, so the gates hold for it. The owner migrates.
- `install-access-functions!` runs before migrate: Postgres refuses a policy whose function doesn't exist.

**Why:** a query that forgot its WHERE, a list, a new row in another shop: the check (piece 3) can't see them, Postgres can. The same conditions feed both, so they can't disagree.

**The price:** the app needs two database roles (decided 2026-10-18). Lists come back filtered, never refused, so a wrong query is quiet, not loud; the leak test (piece 5) is what proves the gates hold. The access functions are installed by a call of their own, not yet compared like tables.

**Instead of:** gates written by hand in SQL files: nothing reads them to make the check, the bells or the leak test.
