# A param may say it is a row of a table, and doorman checks it before any step

> The user's decision, 2026-10-23, from Iron Rainbow's port (GROUNDWORK-GAPS.md, P2, and IR's rule "a request naming another workspace answers 404"). Adds to [A handler says where it acts, and Postgres hears only that](2026-10-23-a-handler-says-where-it-acts-and-postgres-hears-only-that.md). Built on tidy-up, not released.

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

**Settled while building** (the user to review):

1. **"May read it in the shops it acts in"** is the action's sentences on the marked table whose `:only` allows `:read`, with the denies for reading, as the read gate counts them (`make-sql-conditions`' new `:reads`), asked once Postgres has been told the handler's action and its scopes: after the check lets the message through, in the same transaction, before the first step. Anna, Staff in shops A and B, moving shop A's batch to shop B's workstation is refused: the move acts in A.
2. **Nobody signed in gets `:doorman/signed-out`,** not `:doorman/not-found`, as for every refusal of the check (an `:anyone` list asked with a hidden row), so it tells nothing of whether the row exists.
3. **Where doorman reads its mark:** through `:map` (its keys), `:vector` `:sequential` `:set` (each element), `:maybe` and `:and` (each of its shapes). A mark anywhere else (inside `:or`, `:multi`, a registry, a map entry's own properties) stops the start, named, since doorman couldn't check it.
4. **What stops the start, each named, with every other problem:** a marked table that is none of the tables; one without a one-column primary key; a mark whose shape's type isn't the key's (`[:string …]` on a `:uuid` key); a mark on a list or map shape rather than the id's; an action that doesn't `:read`; no sentence on the table letting the action read it. A table with `:access :public` has no sentence, so it stops the start too.
5. **A marked param that is nil or missing** (an optional key, `[:maybe …]`) is no row and isn't checked; an empty list checks none.
6. **explain-refusal** gives each handler `:rows-of [{:path [:moves 1 :workstation-id] :table :workstations :id … :readable? bool}]`, read as the check reads them (an unreadable one makes its `:answer` `:doorman/not-found`); `[]` for a handler with no mark, `nil` when the check refuses before the marks.
7. **One query per marked id,** `EXISTS` over the table's key and the read condition; no batching.
8. **The leak test is unchanged.** It makes a handler's params by malli's generator, so a marked param is a made-up id, refused `:doorman/not-found`: a list handler (`:every-scope-holding-the-action`, `:no-scope`) with a required marked param is refused there before its steps, so the leak test doesn't reach its list. (Open: the leak test could take the asker's own ids for marked tables.)
