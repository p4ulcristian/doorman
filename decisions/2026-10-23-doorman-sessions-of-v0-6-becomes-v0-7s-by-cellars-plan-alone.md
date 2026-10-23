# doorman.sessions of v0.6 becomes v0.7's by cellar's plan alone

> Since v0.7.5. About [Visitors get sessions of their own, and their rows move to the person at sign-in](2026-10-23-visitors-get-sessions-and-their-rows-move-at-sign-in.md), which changed `doorman.sessions` on databases that have rows.

**Decision:** no change entry ships with v0.7's sessions table. Tested on a real database (`doorman.sessions-migration-test`): the tables made from v0.6.0's maps, people's sessions written as v0.6's `sign-in!` wrote them, then `cellar/migrate!` to today's maps. cellar's plan has no stop: `session__visitor_id` added (optional), `session__flags` added `NOT NULL DEFAULT ''` (Postgres fills the old rows with the default without rewriting the table), `session__person_id` made optional, all instant; `doorman.scopes`'s parent index built in the slow part. Every row survives with no flags and no visitor; an old cookie still finds its person; a visitor's session starts, is found, and ends at sign-in; signing out everywhere ends the old sessions with the new.

**Why:** a backfill is needed only where a new column has no default to give old rows; the flags' default (none) is what an old session had.

**Instead of:** a change entry that fills the flags: the default fills them, and a change entry runs once on every database for nothing.
