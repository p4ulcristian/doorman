# A param may say it is a row of a table, and doorman checks it before any step

> The user's decision, 2026-10-23, from Iron Rainbow's port (GROUNDWORK-GAPS.md, P2, and IR's rule "a request naming another workspace answers 404"). Adds to [A handler says where it acts, and Postgres hears only that](2026-10-23-a-handler-says-where-it-acts-and-postgres-hears-only-that.md). Not built yet.

**What was wrong:** Anna, in shop A, moves batch #88 to a workstation: `{:batch/id 88 :workstation-id W}`. The batch is the handler's `:record` and is checked; `W` isn't. Shop B's workstation id lands in the move's log, which has no reference to check it.

**Decision:** a param's shape may mark it as a row of a table:

```clojure
:params-shape [:map
               [:batch/id       :uuid]
               [:workstation-id [:uuid {:doorman/row-of :workstations}]]]
```

Before any step runs, doorman checks every marked id: the handler's action may read that row in the shops it acts in. If not, the answer is `:doorman/not-found`, and no step (no email, no payment) has run. Marks inside lists and nested maps are checked too. A marked table with no sentence letting the action read it stops the start, named.

IR's 404 rule is the same mark: `[:workspace/id [:uuid {:doorman/row-of :workspaces}]]`.

**Why:** the rule sits on the param itself, where its meaning is declared; one rule covers both gaps; the refusal comes before any outside work.

**The price:** doorman reads inside malli shapes for its mark.

**Instead of:**
- **a required `:references` key on every handler**: the param's meaning said apart from its shape, and one more key on every handler
- **gates and multi-column references only**: an id used only as a value stays unchecked, and each handler answers differently
