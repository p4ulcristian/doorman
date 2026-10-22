# doorman ships permissions

> **Built in part** (2026-10-23, v0.7.0): shape 2, a parent scope, by [A scope may sit under a parent scope](2026-10-23-a-scope-may-sit-under-a-parent-scope.md); shape 6, a session flag, by [A session flag and a scope's state may say no](2026-10-23-a-session-flag-and-a-scopes-state-may-say-no.md).

> Moved from Groundwork (2026-10-22), where it was "Groundwork ships permissions": the permissions were a Groundwork part then and are doorman's now ("Groundwork" below is doorman where it means the permissions). Groundwork's copy keeps what it says of Groundwork as a whole.

> **Changed in part** by [Who may see what is the app's; doorman enforces it](2026-10-15-who-may-see-what-is-the-apps.md) (2026-10-15): who may see and do what is the app's to say; doorman only enforces it.

> **Changed in part** by [Access rules are sentences in doorman's language](2026-10-17-access-rules-are-sentences-in-doormans-language.md) (2026-10-17): the six rule shapes become words of a language in which the app writes its rules as sentences.

> Replaces Groundwork's "Groundwork knows nothing about tenants": permissions know about scopes, not what they are.

**Decision:** permissions are built so they can't be skipped.

**The model** (the shape Google Cloud and GitHub use):

- **Scopes** are the places data belongs to. An app has several kinds (for example shops, partner organisations, an admin area, later companies above shops). A kind can have a parent kind, and a membership in the parent counts in its children.
- **Memberships** connect a person to a scope with a role. doorman owns them, in its own tables; an app keeps its own data about a member (pay, for example) in its own table next to it.
- **Roles** are bundles of actions, per kind of scope. The app declares the actions and the starting roles; each scope edits its own roles live. Owner always holds everything. A person can only give actions they hold.
- **Records** lead to their scope through their own columns (job → its order → its shop), declared once per kind of record. A record can belong to two scopes (a shared agreement), or to a person (a customer's own order).
- **Checks:** every handler declares its action and the record it acts on. doorman follows the record's own arrows to its scope, finds the person's role there, and checks the action. The same rule becomes one SQL condition at start, used for single checks and for lists.
- **Conditions** on the record's own columns ("only while draft") are part of the rule. Session flags (impersonation) can block actions.
- **Grants and roles change live,** in Postgres; the next request uses them. The structure (kinds, actions, arrows) changes with a deploy.

**doorman gives general shapes; every specific case is the app's data.** The shapes:

1. the record's own scope, through its columns at any depth (job → order → shop)
2. a parent scope: a membership in the parent counts in its children
3. scope to scope through a linking record, while a condition holds (shops and partners through active agreements)
4. a person on the record (the customer column is me)
5. a condition on the record's own columns (only while draft)
6. a session flag blocks an action (impersonation can't move money)

An app can add its own check to a handler, but it can only say no. A new case built from the shapes is a line in the app's description and never a change to doorman; only a new way to *grant* access changes doorman, where the leak test covers it.

**Stored by doorman:** scopes (kind, parent), roles (per scope), role actions (the ticks in a scope's roles grid; owner needs none), memberships (person, scope, role), extra actions (one action on top of a role), invitations. The actions themselves are declared in the app's code; which scope a record belongs to stays in the app's own columns.

**Built in from the first handler:**

1. A handler without a permission doesn't start.
2. A record is checked against its own scope, never the one the browser names; another scope's record answers "not found".
3. **Every id in a request** is checked, not only the main record: the input shape marks each id field with its kind, and all must be reachable for the person.
4. **The Postgres wall:** each transaction carries the set of scopes that were verified, and every scoped table has row-level security, checked at start. The system bypass lasts one transaction.
5. **Records without a scope** are declared as global, personal or public on purpose; nothing defaults to the bypass.
6. **Scope-to-scope rules** (a partner sees orders of shops it has an active agreement with) are one of the rule shapes, with their condition.
7. **A built-in leak test** calls every handler with another scope's ids and expects "not found", for every app.
8. The browser's "can I?" comes from the same grants and rules.

**Why:** every app needs this, and a mistake is a data leak. In the app this was learned from, every problem came from permissions being optional or added later: checks on the scope the browser named, other ids in a request not checked, endpoints that only required sign-in, the database wall on only some tables, a bypass that stayed on. Built in and required from the first handler, none of these can happen quietly.

**The price:** the permission engine is the most security-critical code an app runs, and a bug in it reaches every app; the built-in leak test is the guard. Deep chains add joins to lists. "Not found" for a lost access can confuse people.

**Instead of:**
- a separate permission service (SpiceDB, OpenFGA): one more thing to run, two writes that can disagree, weak list filtering
- a policy library (Oso is a paid cloud now; Cedar stores no facts and filters lists experimentally)
- each app's own check, called by the library: every app rebuilds roles and grants, and a bug is a leak
- only the database wall: nothing checks actions, and a missing table rule fails as an empty page
