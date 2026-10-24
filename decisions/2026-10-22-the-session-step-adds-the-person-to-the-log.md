# The session step adds the person to the message's log; the leak test takes the app's log

> **Changed in part by** [groundwork-access requires groundwork-steps, not groundwork-bridge](2026-10-24-groundwork-access-requires-groundwork-steps-not-groundwork-bridge.md) (2026-10-24): the message's log is `:groundwork-steps/log`; the leak test takes no log any more, the app's runner (`:answer-message`) carries it.

> Changes in part [sessions v1](2026-10-22-sessions-v1.md) (what the find-session step adds) and [The app passes its wire](2026-10-22-the-app-passes-its-wire.md) (`find-leaks`'s options). Since v0.3.0, on mailroom v0.3.0, cellar v0.2.0 and logbook v0.1.0.

**Decision:** doorman writes no line of its own and decides nothing about how one is written. Two places take the app's log:

- **The find-session step** reads mailroom's `:mailroom/log`, the message's log with its request id, and when someone is signed in puts `(logbook/with-context log {:person-id person-id})` back in its place, beside `:doorman/person-id`. Every step after it (the check, the handler's own steps, a library they call with `(:mailroom/log context)`) writes lines whose context names the request and the person; a handler's failure, which mailroom writes with the log its steps had got to, does too. Nobody signed in: the log stays as it was. A request from another site is answered before anything is added.
- **`find-leaks`** takes `:log` in its options, required, checked by malli as a function, and hands it to mailroom's `answer-message` with the wire.

The step needs `:mailroom/log` in the context, as it needs `:mailroom/request`: mailroom puts both there. doorman depends on logbook for `with-context` only.

**Why:** "Context is handed down as a value": the step that finds the person is the one that knows it, and the context is how the steps hand things down. The person as context, not data, keeps a line's own `:person-id` (an order's buyer) apart from who asked.

**The price:** the step can't run on a context without `:mailroom/log` (a test that builds one by hand writes it in); `find-leaks` takes one key more. Every caller of v0.2 changes.

**Instead of:**
- the person added by mailroom from `:doorman/person-id` after the steps: mailroom would know a doorman key, and the steps would log without it
- a new step of the app's own for it: every app would write the same step, and could forget it
- `:log` in doorman's settings: the step uses the message's log, which mailroom makes per message, not one from the start
