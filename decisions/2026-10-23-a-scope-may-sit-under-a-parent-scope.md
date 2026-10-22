# A scope may sit under a parent scope, and a membership counts below it

> Since v0.7.0. Builds shape 2 of [doorman ships permissions](2026-10-12-doorman-ships-permissions.md) ("a membership in the parent counts in its children"), left out of [access, piece 1](2026-10-22-access-piece-1-who-belongs-where.md). Changes in part piece 1: `add-scope!` takes the parent.

**Decision:**

- A kind names its `:parent` kind (`:shop {:parent :company …}`), `nil` for none; an unknown one or a loop is refused at the start.
- `(add-scope! transaction scope-kinds :shop shop-id founder-id company-id)`: the last argument is the parent scope's id, which must exist and be of the parent kind, or `nil` (a shop under no company). It is kept on `doorman.scopes` (`:scope/parent-kind`, `:scope/parent-id`, indexed).
- A role of a kind may hold the actions of its kind and of the kinds below it (a company's Director holds `:orders/see`, a shop's action). Owner holds every one of them. An action belongs to one kind; one in two kinds is refused at the start.
- `list-actions-of-person` adds, for every scope below a scope the person is a member of, at any depth, the actions held above that are of its kind or a kind below it: a Director of a company holds `:orders/see` in each of its shops, not `:company/report`. The check, the gates and "what may I do?" read it, so they all agree.

**Why:** a company above its shops, an organisation above its teams: a membership given once, above, instead of in every shop, and a new shop under the company reached at once. Computing it when who is asking is told to Postgres keeps every SQL condition as it was: a shop's id among the scopes holding the action.

**The price:** a person high up holds an action in every scope below, so who is asking lists them all, once per transaction; a very wide tree makes that list long. `add-scope!`'s signature changed.

**Instead of:**
- reaching a parent's membership from every row in SQL (a recursive lookup per statement): every condition would change, and the gates would follow the tree on every query
- copying memberships into each child scope: two rows to keep equal, and a new shop would start without them
