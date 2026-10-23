# doorman owns a person's tokens, and only sign-in names a person

> The user's decision, 2026-10-23, from the tidy-up after Groundwork v1.1 (Iron Rainbow's personal tokens for the catalog and AI calls, its `labs_api_tokens`). Changes in part [The session is the only truth](2026-10-12-the-session-is-the-only-truth.md) and [Sessions for visitors, and none for servers](2026-10-12-sessions-for-visitors-and-none-for-servers.md). Not built yet.

**Decision:** one door names a person to doorman: `sign-in!`, called once the app's own login has proven who someone is. Every call after it carries a proof doorman made and reads itself:

- **a cookie**, from a browser: the session, as today;
- **a bearer token**, from a script or an AI acting as a person: `Authorization: Bearer …`. A signed-in person makes a token for themselves, through doorman, with a name and an end date, both required. doorman shows it once and keeps only its SHA-256, as it does a session's; the person lists their tokens and revokes them; a removed member's tokens end with their sessions.

A token's call is that person's: the same actions, scopes and gates. No Origin check (a bearer token isn't sent by a browser on its own).

`run-as-asker!` leaves doorman's public api: no code can tell doorman "this is p-17". The composer's routes get the asker from doorman's own step, from the cookie or the token. The REPL's "ask as this person" stays, in the REPL only, named for what it is.

**Why:** "who is this" stays one place's answer. A token is a session without a browser. If the app checks its own tokens and then names the person, any code holding the pool can name anyone, and nothing checks it.

**The price:** doorman gains a tokens table and its migration. Iron Rainbow's tokens move into it with their hashes, so nobody has to make a new one: IR keeps the SHA-256 of the whole token text (`ircat_` and 43 characters), and doorman hashes the whole text too. The composer's `answer-as` takes the request instead of an asker.

**Not decided here:** the token's text (IR's starts with a lead, `ircat_`, so a leaked one says what it is, and shows its first 12 characters in the list); a token that holds fewer actions than its person; the longest end date; `/mcp`'s WorkOS OAuth tokens (by "the session is the only truth", the app checks one once and doorman issues its own); which route kind token calls arrive on.

**Instead of:**
- **the app checks its tokens and names the person** (today's `run-as-asker!` and `answer-as`): any server code can act as anyone
- **token calls run as a named system actor**: an AI's call no longer sees what its person sees; every reach needs its own sentences
