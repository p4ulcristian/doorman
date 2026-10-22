# access, piece 1: who belongs where

> **Added to** by ["What may I do?" is a handler for the browser; open actions are listed for every scope](2026-10-23-what-may-i-do-is-a-handler-for-the-browser.md) (2026-10-23, v0.4.0): `make-my-actions-handler`, a person's actions for the browser, with the open ones.

> **Changed in part** by [doorman is its own library](2026-10-22-doorman-is-its-own-library.md) (2026-10-22): the rows are in doorman's folder: `doorman.scopes`, `doorman.roles`, `doorman.role_actions`, `doorman.memberships`; Owner stays.

> Builds on [doorman ships permissions](2026-10-12-doorman-ships-permissions.md) and [Access rules are sentences in doorman's language](2026-10-17-access-rules-are-sentences-in-doormans-language.md).

**Decision:**

- **In the app's code:** its kinds of scope, each with its actions (each saying what it does to rows: read, insert, update, delete) and the roles a new scope starts with. `check-scope-kinds` refuses a role that names an action its kind doesn't have, and a role named "Owner".
- **As rows, in Groundwork's folder** (`access-tables`): the scopes, by the app's own row id (a shop's scope id is the shop's id); each scope's roles and their actions; the memberships, one role per person per scope.
- **Owner** is every scope's own role, holding every action of its kind; the person who makes a scope is its Owner (`add-scope!`, in the transaction that makes the app's row).
- **A scope's roles change live:** `add-role!` gives a shop a role of its own without a deploy; `add-membership!` and `remove-membership!`.
- `list-actions-of-person` says, for a person, every scope they're in and the actions they hold there; the check (piece 3) reads it.
- The scopes table has a parent column from the start; the rule that follows a parent comes when an app needs it.

**Why:** the words of the 2026-10-12 model (scope, action, role, membership), with one id per shop and roles a shop can shape itself.

**The price:** who may give a role or an action (nobody gives what they don't hold) is checked by the app's handler for now; it comes with the check in piece 3. Extra actions on top of a role, invitations and parent scopes aren't in yet.

**Instead of:**
- a scope id of Groundwork's own, which the app's row points at: two ids for one shop
- roles fixed in code: every new role a deploy, for every shop at once
