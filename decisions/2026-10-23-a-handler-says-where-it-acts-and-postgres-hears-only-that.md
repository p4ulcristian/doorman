# A handler says where it acts, and Postgres hears only that

> **Changed in part** by [doorman owns a person's tokens, and only sign-in names a person](2026-10-23-doorman-owns-tokens-and-only-sign-in-names-a-person.md) (2026-10-23, built on tidy-up, not released): `run-as-asker!` is `run-as-asker-of-request!`, the asker found in the request.

> The user's decision, 2026-10-23, from Iron Rainbow's port (GROUNDWORK-GAPS.md, P1: 295 endpoints check their action on the shop the request names). Changes in part [access, piece 3: the check](2026-10-22-access-piece-3-the-check.md), [access, piece 4: the gates](2026-10-22-access-piece-4-the-gates.md), [access, piece 2: sentences into SQL](2026-10-22-access-piece-2-sentences-into-sql.md), [access, piece 5: the leak test](2026-10-22-access-piece-5-the-leak-test.md) and [[:member …] names a column or a kind of one scope](2026-10-23-a-member-sentence-names-a-column-or-a-kind-of-one-scope-and-what-is-held-there.md). Built on tidy-up, not released.

**What was wrong:** Anna holds `:machines/edit` in shop A and is an employee in shop B. She asks `:machines/create-machine` with `{:workspace/id B}`.

- The check asked only whether she holds `:machines/edit` *somewhere*: yes, in A, so the handler ran.
- doorman told Postgres every action she holds in every shop, and a table's gate took any of them: she holds an action that writes machines in B, so the row went in.

The handler never said which shop it acts in; doorman filled in "any shop where she holds it" silently.

**Decision:** least privilege. A request carries one handler's action, in the shops it names, and Postgres hears nothing else.

1. **Every handler says where it acts**, written out, no default. A handler missing it stops the start, named, with every other problem:
   - `:record [:machines :machine/id]`: one row, as today; it acts in that row's shops;
   - `:scope [:workspace :workspace/id]`: a scope kind and the param holding its id; the check asks whether the action is held *there*, and answers "Not found." before any step runs if not;
   - `:scope :every-scope-holding-the-action`: written out for a list across every shop where the action is held ("my orders, in all my shops");
   - `:scope :no-scope`: only `:anyone`, `:signed-in` and `[:me …]` sentences apply (my own profile).
2. **Postgres hears only that:** `doorman.asking` holds the handler's action in those shops alone. A handler that tries to write a row in another shop, by a bug or a crafted param, is refused by the gate.
3. **A sentence says which of its action's operations it allows on its table:** `:only`, written out on every sentence: `:all-of-the-action` (every operation the action's kind declares) or a non-empty set of them. Bob, an "Order clerk" holding only `:orders/edit`, edits an order: the order's ledger rows move with it, and he reads the workstations it runs on.

   ```clojure
   ;; on payout_ledger_entries
   {:allow :orders/edit :only #{:read :insert :update} :to [:member :payout-ledger-entry/workspace-id]}
   ;; on workstations
   {:allow :orders/edit :only #{:read} :to [:member :workstation/workspace-id]}
   ```

   His handler, acting as `:orders/edit`, inserts and updates ledger rows, never deletes one, reads workstations and never changes one, and reaches neither in shop B. A gate takes an action's sentence only for the operations its `:only` allows on that table; an `:only` outside what one of its actions declares stops the start, named, with every other problem.
4. **A sentence may name a scope's own row:** `[:member :workspace/id]` on the `workspaces` table itself, so shop settings have a gate (refused today: "names no scope").

**Why:** a rule an app must remember is a leak waiting for the one handler that forgets. When doorman tells Postgres every right a person has, the gate can only stop rows of shops she isn't in, not rows of shops she is in for something else. Telling it one action in named shops makes the gate as narrow as the handler.

**The price:** every handler of every app writes `:scope` (or `:record`), and every sentence writes `:only`. Handlers write nothing about other tables: what an action reaches beyond its own table is said once, per action and table, by sentences. Iron Rainbow's `on-workspace` entries map to `:scope [:workspace :workspace/id]`, its `on-resource` ones to `:record`; its blanket `:workspace/access` sentence goes, since it gave every member every table. In Iron Rainbow, 115 endpoints read and 36 write another area's walled table; each is covered by a sentence for its action on that table.

**Not decided here:** one request over several shops (P3: a split order, a handoff that reads the partner's shop); how `:scope` names more than one param.

**Settled while building** (the user to review):

- **The check reads with every action, then Postgres is told again.** In the one transaction, the check runs as before (every action the asker holds, so "Not allowed." for a row they may see still differs from "Not found."); once it lets the message through, `doorman.asking` is set again to the handler's action alone (`"acting"`) and the scopes it acts in, before the handler's steps. Every person's sentence in a gate counts only while its action is the one acted as, `doorman.is_acting(action)`; a named system actor's sentences don't.
- **A record's scopes** are the scopes its columns name (its own row, on a scope kind's table), and, for its action's `[:member <kind of one scope> …]` and `:via` sentences, every scope of those kinds holding what they need: the row doesn't name the partner or the back office it is reached through.
- **"Held there"**, for `:scope [kind param]`, is the action held in that scope, or the action a `[:member <column of that kind> <held>]` sentence of it needs held there (what "what may I do?" lists). Each scope told to Postgres carries only the actions the handler's action's sentences need held in its kind.
- **A scope's state** denying the action in the named scope answers "Not allowed.", as a session flag does.
- **`run-as-asker!`, "what may I do?" and `explain-refusal`** tell Postgres every action the asker holds, in every scope (`"every_action": true`), as before: no handler names one action there. A named system actor is told every action too, so the `:anyone` rows stay its.
- **The leak test** asks a `:scope [kind param]` handler with each foreign id of its kind's `:table` and wants "Not found."; it takes the scope kinds, `:scope-kinds`, required.
- **The check respects `:only`.** For a `:record` handler, the record is allowed when a sentence of the action holds for it whose `:only` allows what the action is for: one of the action's writes, for an action that writes; `:read`, for one that only reads. Otherwise, a record the asker can see (by any action they hold, as the check always asked) answers "Not allowed.", one they can't "Not found.": Bob's `:orders/edit` on a workstation of his shop, which its sentence lets him only read, is "Not allowed."; one of shop B's is "Not found.". A record a sentence lets the action write by one operation and not another (the ledger, `#{:read :insert :update}`, no `:delete`) is allowed; the gate refuses the missing operation. The same condition narrows a list, `doorman."may <action> on <table>"()`.
- **A scope's own row** is frozen by its kind's state deny: [A suspended scope's own row is frozen for its own kind's actions](2026-10-23-a-suspended-scopes-own-row-is-frozen-for-its-own-kinds-actions.md).

**Instead of:**
- **`:reads-with #{…}` on every handler** (the first plan for point 3): Bob's handler would name `:payouts/edit` to touch the ledger, so he'd need that action too, more than his job; it covered reads, never the writes his edit makes; and it was said per handler, again in each one, instead of once per action and table
- **only a `:scope` key, Postgres still told everything** (the first suggestion): closes the check for handlers that write it; any other handler, and any bug, still reaches every shop the person is in
- **dropping IR's blanket sentence only**: the gate still takes any action held in the row's shop, so `:workstations/edit` in B still writes machines in B
