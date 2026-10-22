# Access rules are sentences in doorman's language

> Moved from Groundwork (2026-10-22), where it was "Access rules are sentences in Groundwork's language": the words and what is made from them were Groundwork's then and are doorman's now. Who is rung became doorbell's, which tells every open screen by table (doorbell's "sync tells by table and trusts the gates"); the browser's "can I?" is not built. Groundwork's copy keeps those parts.

> **Changed in part** by Groundwork's "A table is one map" (2026-10-22): a table's sentences sit on its map, and its record kind (table, id, whose, parent) is read from the map.

> Answers how the app says it, left open by [Who may see what is the app's](2026-10-15-who-may-see-what-is-the-apps.md). Changes [doorman ships permissions](2026-10-12-doorman-ships-permissions.md): the six shapes and the shapes added on 2026-10-13 become words of this language. Built under Groundwork's "The freeze lifts for the access language".

**Decision:** an app writes who may do what as one list of sentences. Each sentence says: allow this action, on this kind of record, to whom, and when. doorman gives the words and reads every sentence at start; it refuses to start on an unknown word, table or column.

The app has the nouns: its kinds of scope, which column names whose row, its actions, its sentences. doorman has the words, the grants tables, and what it makes from the sentences: the wall, list filters, who is rung, the browser's "can I?", and the leak test.

The words:

- **To whom:** `:anyone`, `:signed-in`, `[:member <scope>]` (a member of a fixed scope, or of the scope a column of the row names, whose role holds the action), `[:me <column>]` (the person or visitor in that column). A sentence may give `:anyone` or `:signed-in` an action that writes (a visitor sends a shop an inquiry, a signed-in customer places an order), but only with a `:when` or a `:via` that narrows it; doorman refuses an open write without one at start.
- **When:** a condition on the row's own columns (`[:= :invoice__status "draft"]`), and `[:none <column>]` for a universal row that belongs to nobody.
- **`:via`:** a row is allowed when a related row exists for which a who and a when hold. It names the other table, which columns match, and the who and when on that row; it is SQL's EXISTS. It covers every direction: the row points to the other (a payout row may be written by whoever may pay its order), the other points to the row (a shop handed an order sees it while the handoff is open), or both share a column (a partner sees a shop's orders while their agreement is active). The lookup runs past the wall, inside doorman, so Postgres doesn't loop on the other table's own rule. doorman refuses to start when the matched columns have no index.
- **Deny:** only two things can say no, before any yes: a session flag (an impersonating admin can't move money) and a scope's state (a suspended shop is read-only). Never a person or a row. A scope's state lives in the app's own table: the scope kind names its table, and the deny is a condition on that table's columns (`[:= :workspace__status "suspended"]`). A scope a deny holds for stays verified for reads and is left out of the transaction's writes, so the wall refuses its writes too.

A kind of scope may have exactly one scope (the platform's back office): its members hold roles there, and its sentences reach other scopes' rows and universal rows.

**Why:** shapes were fixed patterns: each new business case needed a new shape and a new doorman decision. Words combine, so a new case is a new sentence in the app; only a case no word covers changes doorman, and one new word serves many cases. doorman can read every sentence, which it must to build the wall, the bells, "can I?" and the leak test from the same rules. Most words already exist, scattered by shape (conditions, people, visitors, open actions, links, session blocks); one list puts every rule in one place and one form.

**The price:**
- Both running apps rewrite their permission description in the same change.
- The wall knows read, insert, update and delete, not actions: two update actions on one table become one update rule on the wall, and only the endpoint check tells them apart.
- `:via` adds a lookup to every row check that uses it; it is measured on the longest lists.
- The leak test proves the wall matches the sentences, not that the sentences are right: a sentence that is too open passes it.
- A deny can confuse ("why can't I?"), so the explain tool names the deny that said no.

**Instead of:**
- `:via` following only a row's own pointer: it covers the payout rows only, so handoffs and agreements would keep their own words (passes, links)
- a state column on doorman's scopes table: the reason for the state (a trial ending, an unpaid bill) stays in the app, so two places would have to agree
- adding the missing words to the shapes as they are: smallest change, but the rules stay scattered, and the next case risks a new shape again
- conditions in a policy language (Cedar, Oso Polar): says anything, but hard to turn into list filters and bells
- the app writing Postgres row rules itself: doorman can't read them, so no bells, no "can I?", a blind leak test

**Settled while building (2026-10-17):**
- Each action says whether it reads or writes, or both; a write-only action can't read.
- `[:member <scope> <action>]` may name the action the person must hold there (a partner reaches an order through an agreement only while holding the agreement's action).
- An action open to anyone through a `:via` counts as open for endpoints too: what the wall allows, an endpoint can ask.
- A list shows every row the person may read. To narrow a list by another action ("only the orders I may edit"), a query file calls a SQL function doorman generates per action and record kind.
- A record kind's `:parent` stays: it keeps a child's scope equal to its parent's, which is data integrity, not access.
- A `:via` needs indexes on both sides of what it matches and on the related row's who column.
- Changing a related row's matched or who columns needs the right to create such a row.
- A scope-state deny on a writing action makes the scope read-only, lifted by the system (the app's billing); on a reading action it hides the scope. On a row of two scopes, a deny in either applies.
