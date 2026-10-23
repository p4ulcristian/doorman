# Roles have ids, and a member holds a set of roles

> The user's decision, 2026-10-23, from Iron Rainbow's port (GROUNDWORK-GAPS.md, P9 and P10). Changes in part [access, piece 1: who belongs where](2026-10-22-access-piece-1-who-belongs-where.md). Built on tidy-up, not released.

**What IR does today:** RC Hotcoats' owner renames "Painter" to "Coater", deletes a role nobody holds, gives Anna one extra right ("approve QC") without making a new role (13 such rows), and invites Tom with no role yet. doorman couldn't: a role was its name, and every member held exactly one.

**Decision:**

- **A role has an id**, and its name is a column, unique in its scope. `rename-role!`, `set-role-actions!`, `remove-role!` (refused while a member holds it).
- **A membership holds a set of roles**, `#{}` for none yet: Anna holds `#{"Employee" "QC approver"}`; Tom holds `#{}` until the owner picks. `set-membership-roles!` changes them in place. A person's actions in a scope are the union of their roles' actions.
- **An extra right is a small role.** A role stays the only thing that gives an action.

**Why:** one place says what a person holds, and explain-refusal names the role that gave or didn't give an action. Ids make a rename one row and let invitations point at a role.

**The price:** doorman's memberships and roles tables change (a migration for apps on today's tables); Iron Rainbow's 13 extra rights become roles, and its Teams screen shows them as roles.

**Instead of:**
- **an extras table** (person, scope, action), as IR has: two places say what a person holds
- **names as the key, a rename rewriting every membership; a fake empty role for "no role yet"**: a rename touches every member, and "no role" is a role
- **a personal copy of a role per person**: an edit to "Manager" never reaches the copies

**Settled while building** (the user to review):

1. **The tables:** `doorman.roles` (`:role/id`, the key; scope kind and id; `:role/name`), `doorman.role_actions` (role id and action, referencing the role), `doorman.memberships` (person, scope kind and id; no role column), and a new `doorman.membership_roles` (person, scope kind and id, role id), one row per role held, none for `#{}`. It references `doorman.roles` over (role id, scope kind, scope id), so Postgres too refuses a role of another scope.
2. **A name is unique in its scope by doorman's functions,** under a lock per scope (`pg_advisory_xact_lock`) in `add-role!`, `rename-role!` and `remove-role!`, not by a key in Postgres: cellar makes no unique key over several columns of a table's own.
3. **doorman makes the ids** (random UUIDs). `add-role!` returns the new id; `add-scope!` returns its new roles' ids by name, `{"Owner" … "Staff" …}`.
4. **`list-roles`** (`[{:role-id :name :actions}]` by name) is new: an app needs it to find the ids of a scope's starting roles. Owner is listed with every action of its kind and below.
5. **Every role function takes the scope beside the role id** (`(rename-role! transaction [:shop id] role-id "Coater")`); a role of another scope is `:doorman/unknown-role`.
6. **Owner stays a row named "Owner",** never renamed, given actions or removed, and no role of the app's takes the name: `:doorman/owner-role`.
7. **The named errors:** `:doorman/role-held` (`{:role-id :name :held-by n}` in its data), `:doorman/role-name-taken`, `:doorman/unknown-role`, `:doorman/owner-role`, `:doorman/unknown-action`, `:doorman/bad-roles` (not a set of ids), `:doorman/bad-role-name`, `:doorman/bad-actions`.
8. **`set-membership-roles!` returns whether the person is a member there;** for one who isn't, nothing changes (it adds no membership). `remove-membership!` takes the roles held there with it. `remove-role!` takes the role's actions with it.
9. **A member holding `#{}`** is listed by `list-actions-of-person` with `#{}` in that scope; "what may I do?" leaves an empty scope out, as before.
10. **explain-refusal names the roles:** each of `:memberships` is `{:scope-kind :scope-id :roles [{:role-id :name :actions}]}` (`:roles []` for none yet); a `[:member …]`'s `:to`, and a `:scope [kind param]` handler's `:scope`, have `:given-by [{:scope-kind :scope-id :role-id :name :gives}]`, the roles there or above that give what it needs (a role above gives only actions of the scope's kind and below). A `:scope [kind param]` handler's `:memberships` are the named scope's (they were nil).
11. **The migration, `access-move-from-v0-7`: two `cellar/migrate!` runs,** since cellar stops on a primary key change and runs a change's SQL before it makes new tables. First `:old-tables` and `:renames` (v0.7's three tables renamed `…_of_v0_7` and kept, today's made empty beside them); then `:copy-and-drops` (`move_access_rows_of_v0_7.sql`: each role a `gen_random_uuid()` id, its actions by it, each membership's role name the set of that role's id; then the three dropped), one transaction. Between the two, nobody holds anything (it fails closed), so the app starts after the second. A v0.7 membership naming a role its scope doesn't have (v0.7 refused making one) would come over holding `#{}`.
12. **The leak test needed no change:** it asks as the request's person, whatever they hold; `doorman.roles-test` runs it for a member of two roles and one of none. `doorman.sessions-migration-test` now ends at v0.7's access tables; `doorman.roles-migration-test` takes them to today's.
