# access, piece 2: sentences into SQL conditions

> **Added to** by [:via reads related rows through a function, and guards what they match](2026-10-23-via-reads-related-rows-through-a-function-and-guards-what-they-match.md), [[:member …] names a column or a kind of one scope, and may name the action held there](2026-10-23-a-member-sentence-names-a-column-or-a-kind-of-one-scope-and-what-is-held-there.md), [A session flag and a scope's state may say no](2026-10-23-a-session-flag-and-a-scopes-state-may-say-no.md) and [Visitors get sessions of their own](2026-10-23-visitors-get-sessions-and-their-rows-move-at-sign-in.md) (2026-10-23, v0.7.0): the words left out of v1.

> **Changed in part** by [doorman is its own library](2026-10-22-doorman-is-its-own-library.md) (2026-10-22): the functions are `doorman.find_scopes_holding(kind, action)` and `doorman.find_asking_person()`.

> Builds on [Access rules are sentences in doorman's language](2026-10-17-access-rules-are-sentences-in-doormans-language.md) and Groundwork's "A table is one map".

**Decision:** a table map's `:access` sentences become one SQL condition per action (the sentences joined by OR) and one per operation (read, insert, update, delete: the actions that do it, joined by OR; `false` when none does). `make-sql-conditions` checks every table's sentences first and throws with every problem at once: an action no scope kind has, a column the table doesn't have, an open write (`:anyone` or `:signed-in` given a writing action without a `:when`).

The words in v1: `:to` is `:anyone`, `:signed-in`, `[:member column]` or `[:me column]`; `:when` is `[:= col v]`, `[:not= col v]`, `[:in col [vs]]`, `[:none col]`, `[:and …]`, `[:or …]`. A `[:member column]` finds its scope kind from the column's reference: the kind whose `:table` the reference points at.

Who is asking is read from two SQL functions the gates install (piece 4), `groundwork.find_scopes_holding(kind, action)` and `groundwork.find_asking_person()`, each wrapped in `(SELECT …)` so Postgres asks once per query, not once per row.

**Why:** one condition per action is what both the check and the gates need, made once from the sentences, so the two can't disagree. The words are the decided ones; the checks turn a wrong sentence into a refused start.

**The price:** `:via`, a fixed scope (`[:member :platform …]`), denies and visitors (decided 2026-10-17) aren't in v1.

**Instead of:** each of the check and the gates reading the sentences on its own: two readings that could drift apart.
