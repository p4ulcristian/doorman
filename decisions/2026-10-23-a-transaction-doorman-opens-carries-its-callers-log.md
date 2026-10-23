# A transaction doorman opens carries its caller's log

> From the user's yes, 2026-10-23, in the review of the after-commit build (the failure line has the request id): cellar's `run-in-transaction!` takes a required `:log` ([cellar's decision](https://github.com/p4ulcristian/cellar/blob/tidy-up/decisions/2026-10-23-a-transaction-keeps-work-to-run-after-its-commit.md)). Changes in part [A named system actor is allowed what its sentences give it](2026-10-23-a-named-system-actor-is-allowed-what-its-sentences-give-it.md) and [doorman owns a person's tokens](2026-10-23-doorman-owns-tokens-and-only-sign-in-names-a-person.md). Built on tidy-up, not released.

**What was wrong:** a handler's step keeps work for after doorman's transaction commits (cellar's `run-after-commit!`); a failure in it was written to the pool's log, without the message's request id.

**Decision:** every transaction doorman opens says its log to cellar:

- **The check step's and `:doorman/my-actions`'s** carry the message's log, `:bridge/log` (its request id, and the person once the session step found them).
- **`run-as-system!` and `run-as-asker-of-request!`** take `:log` in their options, required: `{:read-only? false :log log}`, `{:settings … :read-only? … :scope-kinds … :log log}`; the caller's (a job's, a request's), or nil for a transaction that keeps no work.
- **doorman's own**, never handed to app code (`sign-in!`, `sign-out-everywhere!`, `explain-refusal`, the system leak test), write `:log nil`: cellar refuses `run-after-commit!` on them.

**Why:** the failure line is found by the request it came from.

**Instead of:**
- **the pool's log**: one per app, no request id
- **a log option on `sign-in!` and the others**: they keep no work, so no line is ever written there
