# doorman reads past its gates only inside its own functions

> Since v0.7.0. Adds to [access, piece 4: the gates](2026-10-22-access-piece-4-the-gates.md).

**Decision:** every read gate doorman makes starts with `(SELECT doorman.is_passing())`: true only while the setting `doorman.passing` is `'on'`. doorman's own functions turn it on while they read and back to what it was after: a `:via`'s related rows, a scope's denied state, the guard's row as it was. `explain-refusal` turns it on for its whole read-only transaction, to see a record the asker can't. Nothing else does; the check and a handler's steps never do. It opens reads only, never writes. Every table with sentences has a read gate, so a function can read a table no action reads.

**Why:** doorman has no system role: the app's role owns nothing, and the gates are forced, so even the tables' owner is held by them (unless it is a superuser). A related row, a scope's state and a row before a change are facts doorman must read whatever the asker may see, and Postgres refuses a policy that reads its own table through itself.

**The price:** like `doorman.asking`, it is a setting the app's own code could set; the gates guard against a query that forgot a rule, not against the app's code lying about who asks. A function that fails ends its transaction, and the setting with it.

**Instead of:**
- functions that run as their owner (SECURITY DEFINER): forced gates hold the owner too, and a role that passes them is the role the app must not have
- each table's gate reading the others' rows under their gates: what counts would change with what the asker may see, and two tables naming each other loop
