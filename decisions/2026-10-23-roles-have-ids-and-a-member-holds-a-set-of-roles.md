# Roles have ids, and a member holds a set of roles

> The user's decision, 2026-10-23, from Iron Rainbow's port (GROUNDWORK-GAPS.md, P9 and P10). Changes in part [access, piece 1: who belongs where](2026-10-22-access-piece-1-who-belongs-where.md). Not built yet.

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
