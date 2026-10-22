# Sessions live in Postgres

> **Changed in part** by [doorman is its own library](2026-10-22-doorman-is-its-own-library.md) (2026-10-22): the sessions table is doorman's, `doorman.sessions`.

**Decision:** a session is a row in Groundwork's sessions table; the browser's cookie holds only random bytes, and the table stores their fingerprint (SHA-256), so a copy of the table can't sign anyone in. The app's login step (an identity provider, a gate, a password) ends by calling Groundwork's "sign in as this person"; signing out, signing out everywhere and removing someone delete rows.

**Why:** a session can be ended at once: a removed member, a stolen laptop, "sign out everywhere". Sessions survive deploys and are shared by every server.

**Instead of:** the whole session in a signed cookie. No table, but a session can't be ended until it expires.
