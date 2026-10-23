# A suspended scope's own row is frozen for its own kind's actions

> The user's decision, 2026-10-23. Changes in part [A session flag and a scope's state may say no](2026-10-23-a-session-flag-and-a-scopes-state-may-say-no.md). Built on tidy-up, not released.

**Decision:** a scope's state deny (`{:deny :writes :when [:= :shop/status "suspended"]}`) also holds on the scope's own row, the shop in `shops`, for the kind's own actions, as it holds on every row whose columns name the shop. A member of a suspended shop, its Owner too, can't write the shop's row: they can neither lift the suspension nor change the shop's settings. They still read it.

The state is lifted by what the deny doesn't hold:

- **an action of another kind**, held by the app's own staff: `{:allow :shops/lift :only :all-of-the-action :to [:member :platform :shops/lift]}` on `shops`;
- **a named system actor**: `{:allow :billing/mark :only :all-of-the-action :to [:system :billing]}`. The denies hold people, never a system actor ([A named system actor is allowed what its sentences give it](2026-10-23-a-named-system-actor-is-allowed-what-its-sentences-give-it.md)).

**Why:** with the row left open, a member could lift their own suspension.

**The price:** an app that suspends a scope needs another kind's action or a system actor to lift it; a member's own settings wait until it is lifted.

**Instead of:**
- **leaving the row open, so the app lifts the state with an ordinary action** (the old rule): the member's ordinary action is that action, so they lift it themselves
- **taking the members' roles away**: forgets who had which role, so lifting means restoring a copy; takes their reads too; and doesn't freeze the rows they reach through `[:me …]` or a `:via`
