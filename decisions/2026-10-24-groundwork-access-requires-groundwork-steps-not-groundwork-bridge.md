# groundwork-access requires groundwork-steps, not groundwork-bridge; the leak test is handed the app's runner

> The user's decision, 2026-10-24 (groundwork v4.0.0): groundwork-bridge, groundwork-access, groundwork-live-updates and groundwork-screens don't require each other; they share shapes through groundwork-steps, and the composer (groundwork) plugs them together. Changes in part [access, piece 5: the leak test](2026-10-22-access-piece-5-the-leak-test.md), [The app passes its wire](2026-10-22-the-app-passes-its-wire.md) and [The session step adds the person to the message's log; the leak test takes the app's log](2026-10-22-the-session-step-adds-the-person-to-the-log.md).

**Decision:** groundwork-access requires [groundwork-steps](https://github.com/p4ulcristian/groundwork-steps) in place of groundwork-bridge. Its steps (the find-session step, the check, "what may I do?") read and write groundwork-steps's context, `:groundwork-steps/request`, `:groundwork-steps/message-data`, `:groundwork-steps/log`, and refuse with groundwork-steps's `refuse`; its refusals read `{:groundwork-steps/refused … :groundwork-steps/data …}`.

`find-leaks` runs no message itself: its options take the app's runner, `:answer-message`, `(fn [handlers request message])` → the answer keyed by name, as over http, in place of `:shaper`, `:log`, `:meter` and `:metric-buckets`, which only that runner used. In groundwork it is groundwork-bridge's `answer-message`:

```clojure
(access/find-leaks ready anna-request {:orders [order-in-another-shop]}
                   {:answer-message #(bridge/answer-message %1 :no-rate-limits %2 %3
                                                            {:shaper shaper :log log :meter meter :metric-buckets metric-buckets})
                    :scope-kinds scope-kinds :seed 7 :size 30})
```

groundwork-access's own tests answer messages with a runner of their own (`groundwork-access.test-answers`, on groundwork-steps and groundwork-shaped-json), which reads the params by their shape, runs the steps in their `:run-steps-in`, writes the answer or the refusal out by its shapes and answers a throw or no answer `:groundwork-steps/failed`; the tests that went over a real server's http address ask it in its place, with the same request (its cookie, its Origin) and the same JSON text. The handler's keys groundwork-access reads and writes (`:steps`, `:refusals`, `:run-steps-in`, `:rate-limit`) are the runner's table, read as data.

**Why:** groundwork-access needed groundwork-bridge only for the step context's keys, `refuse`, and one call, `answer-message`, in the leak test. That tied the access library to the whole wire and its releases. A runner handed in keeps the leak test asking exactly as the app answers (its limiter, its meter, its shaper), while groundwork-access stays out of how a message travels.

**Instead of:**
- groundwork-bridge kept as a test-only dependency: the tests would plug two siblings together, which only the composer does, and every bridge release would carry through groundwork-access
- the leak test taking the shaper, log and meter and running the steps itself: a second runner of messages in a library about access, which would drift from the app's
