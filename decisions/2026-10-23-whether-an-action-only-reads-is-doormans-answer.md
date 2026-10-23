# Whether an action only reads is doorman's answer, for anyone to ask

> **Changed in part** by [The family and its composer are groundwork](https://github.com/p4ulcristian/groundwork/blob/Starting-from-the-bottom/decisions/2026-10-23-the-family-and-its-composer-are-groundwork.md) (2026-10-23): manor, the family, and house, the composer, are both named groundwork now.

> Since v0.5.0.

**Decision:** `doorman.api/does-action-only-read?` says whether an action does nothing but `:read` in its scope kind, the same rule the check uses to run a handler in a read-only transaction. An action no kind has is not one that only reads.

```clojure
(doorman/does-action-only-read? scope-kinds :orders/see)   ;=> true: read-only transaction
(doorman/does-action-only-read? scope-kinds :orders/edit)  ;=> false: its handlers may write
```

house asks it to key every write once: a handler whose action may write carries a one-time key, and one that only reads isn't keyed (house's "Every write runs once, by its one-time key").

**Why:** the app already says, once, what each action does to rows (`#{:read :insert :update}`); doorman reads that to pick the transaction. A second answer elsewhere (a handler flag) could disagree with the transaction the handler really runs in: a handler marked read that writes would fail in Postgres, and one marked write that doesn't would key for nothing.

**Instead of:** each handler saying `:writes? true`: one more key on every handler, saying again what its action says.
