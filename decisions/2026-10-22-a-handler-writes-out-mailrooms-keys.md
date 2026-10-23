# A handler writes out mailroom's keys, and its :run-steps-in is nil

> **Changed in part** by [A refusal is a named reason, declared on its handler](https://github.com/p4ulcristian/bridge/blob/tidy-up/decisions/2026-10-23-a-refusal-is-a-named-reason-declared-on-its-handler.md) (bridge's, 2026-10-23, built on tidy-up, not released): bridge's keys include `:refusals` (`{}` for none), never naming doorman's own reasons.

> Changes in part [access, piece 3: the check](2026-10-22-access-piece-3-the-check.md). Since v0.1.1, on mailroom v0.1.3.

**Decision:** doorman takes mailroom v0.1.3, where a handler writes out every key mailroom reads ([mailroom's "Every value that sets behaviour is a setting"](https://github.com/p4ulcristian/mailroom/blob/main/decisions/2026-10-22-every-value-that-sets-behaviour-is-a-setting.md)).

- **`put-access-into-handlers` refuses a handler** that leaves out `:steps`, `:rate-limit` or `:run-steps-in`, with every problem of every handler at once (`:doorman/bad-handlers`), as it refuses one without `:action`, `:params-shape` or `:answer-shape`. Both shapes stay real ones, not nil: the leak test makes params from `:params-shape`, and an answer that may hold another scope's rows leaves only as `:answer-shape` allows.
- **`:run-steps-in` is nil.** doorman sets it: the session, the one transaction that tells Postgres who is asking, read-only when the action only reads. An app's own was replaced without a word; now it refuses, named.
- **The leak test asks outside a server**: `(answer-message handlers :no-rate-limits request message)`, mailroom's written-out "no limiter".

**Why:** the handler table goes from doorman to mailroom; a key mailroom refuses should be named where the app hands it over, with doorman's own problems, not one call later. A `:run-steps-in` the app wrote and doorman dropped would have run the steps outside the app's wrapping unseen.

**Instead of:**
- wrapping the app's `:run-steps-in` inside doorman's: two transactions' worth of order to choose (which runs first, which sees the session), with no handler needing it yet
- leaving the check of mailroom's keys to mailroom: the app would meet doorman's problems and mailroom's in two rounds
