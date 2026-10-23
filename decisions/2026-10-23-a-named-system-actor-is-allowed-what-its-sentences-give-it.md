# A named system actor is allowed what its sentences give it, through the gates

> **Changed in part** by [A transaction doorman opens carries its caller's log](2026-10-23-a-transaction-doorman-opens-carries-its-callers-log.md) (2026-10-23, on tidy-up): `run-as-system!` takes `{:read-only? … :log …}`, the caller's log (or nil) written out.

> Since v0.7.5. Adds to [Access rules are sentences in doorman's language](2026-10-17-access-rules-are-sentences-in-doormans-language.md) (a new who word) and [doorman reads past its gates only inside its own functions](2026-10-23-doorman-reads-past-its-gates-only-inside-its-own-functions.md) ("doorman has no system role" stands). Asked for by postbox's webhooks: a bounce couldn't mark a shop's row, since its function ran as the app's role with nobody asking.

**Decision:**

- **A who word, `[:system name]`:** `{:allow :shop-emails/mark-bounced :to [:system :postbox]}`. The name is a keyword without a namespace. It holds only while that actor asks (`(SELECT doorman.find_asking_system()) = 'postbox'`), never for a person or a visitor: the check answers a person's message for such an action "Not found.", and "what may I do?" never lists it. A `:via`'s `:to` can't be one. The actions are ordinary actions of a scope kind.
- **`run-as-system! pool name {:read-only? …} f`:** `f` runs in one transaction of its own in which Postgres is told the actor asks (`doorman.asking` `{"system": "postbox"}`): no person, no visitor, no scope held, no session flag. A transaction handed in is refused (who asks would change under its other statements). The app's own role runs it, which owns nothing and passes no gate.
- **What it reaches:** the rows its sentences give it by name, the `:anyone` ones, and tables without sentences; nothing else. The denies don't hold it: they say no to people (a session's flag, a scope's state), and a scope's state is lifted by the system.
- **The leak test:** `find-system-leaks pool tables name {:orders [id …]}` reads, changes and deletes each row as the actor, each in a transaction undone, and returns every row it reached.
- Names are not declared anywhere else: a name no sentence gives anything reaches only what anyone does.

**Why:** a webhook's function runs with nobody asking, so rows behind the gates were invisible to it. The gates are built from the sentences; a system actor named in them is allowed exactly what is written there, read by the same conditions, and the leak test can prove it. Like `doorman.asking` for people, it is a setting the app's code sets: the gates guard against a query that forgot a rule, not against the app's code lying about who asks.

**Instead of:**
- a role with BYPASSRLS, or running as the tables' owner: every table opens, which is the role the app must not have
- `doorman.passing` for webhooks: it opens every read, and no write
- the webhook's function finding a person to act as: there is none; a bounce belongs to no one
- declaring the actors in the scope kinds: the sentences already say what each may do, and a second list would have to agree with them
