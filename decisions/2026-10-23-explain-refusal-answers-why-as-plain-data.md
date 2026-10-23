# explain-refusal answers why, as plain data

> **Changed in part** by [A refusal is a named reason, declared on its handler](https://github.com/p4ulcristian/bridge/blob/tidy-up/decisions/2026-10-23-a-refusal-is-a-named-reason-declared-on-its-handler.md) (bridge's, 2026-10-23, built on tidy-up, not released): its `:answer` is `:allowed` or the check's reason: `:doorman/not-found`, `:doorman/not-allowed`, `:doorman/signed-out`.

> Since v0.7.0. Answers "the explain tool names the deny that said no" of [Access rules are sentences in doorman's language](2026-10-17-access-rules-are-sentences-in-doormans-language.md), and what study's `:why-refused` tool needs (study's "The tools are the app's functions, in study.tools").

**Decision:** `(explain-refusal {:pool :handlers :tables :scope-kinds} {:person-id :visitor-id :session-flags} handler-name params)`, every key required, says what the check would answer the asker's message, and why, as data:

- `:answer` `:allowed` `:not-allowed` `:not-found`, the check's own (or `:no-such-handler`); `:action`, `:writes?`, `:asker`;
- `:record`: the table, the id from the params, whether the row exists, and the scopes its columns name;
- `:memberships`: the asker's role in each of those scopes and the scopes above them; `:actions-in-scopes`: what they hold there (inherited too);
- `:sentences`: each sentence giving the action on that table, whether it holds, and each part: `:to` (the scope, the action it needs, whether it is held there; or the column and its value), `:via`, `:when`, each with `:holds?`;
- `:denies`: each deny of the action, whether it says no (the flag; the scopes in the denied state);
- for a handler without a `:record`: each sentence's way (`:anyone` `:signed-in` `:visitor` `[:holds action]`) and whether it holds for the asker.

It runs read-only, in one transaction that tells Postgres who is asking, as the check does, and reads past the gates, so it sees the record when the asker can't; each part is the same SQL the check and the gates run. It takes the handler by name and its params, as a message names them.

**Why:** "why can't I?" is asked about a message someone sent, so it takes what the message takes. Data, not sentences in English, so a REPL person and an agent can both read it and a test can assert it; the parts are the conditions' own SQL, so the explanation can't drift from the check.

**The price:** it reads past the gates: it is for the app's REPL and tests, never a handler. A scope (rather than a record) isn't a question it answers.

**Instead of:**
- Groundwork's `why`, lines of English: readable once, hard to test or to act on
- `(person action [kind id])`: the action and record are already the handler's; asking by message can't name a pair no handler has
