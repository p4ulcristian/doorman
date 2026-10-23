# A handler says where it acts, and Postgres hears only that

> The user's decision, 2026-10-23, from Iron Rainbow's port (GROUNDWORK-GAPS.md, P1: 295 endpoints check their action on the shop the request names). Changes in part [access, piece 3: the check](2026-10-22-access-piece-3-the-check.md) and [access, piece 4: the gates](2026-10-22-access-piece-4-the-gates.md). Not built yet.

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
3. **A handler names what else it reads:** `:reads-with #{:locations/see}`, written out `#{}` for none. Those actions count for reading only, in the same shops: a handler that makes a machine and reads its location to check it is in the shop names the action that reads locations.
4. **A sentence may name a scope's own row:** `[:member :workspace/id]` on the `workspaces` table itself, so shop settings have a gate (refused today: "names no scope").

**Why:** a rule an app must remember is a leak waiting for the one handler that forgets. When doorman tells Postgres every right a person has, the gate can only stop rows of shops she isn't in, not rows of shops she is in for something else. Telling it one action in named shops makes the gate as narrow as the handler.

**The price:** every handler of every app writes `:scope` (or `:record`) and `:reads-with`. Iron Rainbow's `on-workspace` entries map to `:scope [:workspace :workspace/id]`, its `on-resource` ones to `:record`; its blanket `:workspace/access` sentence goes, since it gave every member every table. How many IR handlers read outside their action is being counted.

**Not decided here:** one request over several shops (P3: a split order, a handoff that reads the partner's shop); how `:scope` names more than one param.

**Instead of:**
- **only a `:scope` key, Postgres still told everything** (the first suggestion): closes the check for handlers that write it; any other handler, and any bug, still reaches every shop the person is in
- **dropping IR's blanket sentence only**: the gate still takes any action held in the row's shop, so `:workstations/edit` in B still writes machines in B
