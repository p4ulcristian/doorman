# One request over several shops, and invitations, need nothing new in doorman

> The user's decision, 2026-10-23, from Iron Rainbow's port (GROUNDWORK-GAPS.md, P3 and "invitations stay IR's own").

**Decision:**

- **Several shops (P3):** nothing is built now. The real case, a customer's split checkout (one payment, one order per shop, `:customizer.checkout/create-split-order`), works as `:scope :no-scope`: her sentences (`:signed-in`, and `:via` her own submitted order) depend on no named shop. A member acting in two named shops in one request happens in no Iron Rainbow endpoint; [One request may name several scopes](2026-10-18-one-request-may-name-several-scopes.md) stays written and unbuilt, and is added as a new `:scope` value when a real handler needs it, breaking none.
- **Invitations** stay the app's own table and sentences. The shop's side: `[:member :invitation/workspace-id :team/invite]`; the invitee's side: `[:me :invitation/person-id]`, the person filled in by the app's login when the email matches. Accepting calls doorman's `add-membership!` (with roles per [Roles have ids, and a member holds a set of roles](2026-10-23-roles-have-ids-and-a-member-holds-a-set-of-roles.md)).

**Why:** nothing to build for cases that work or don't exist; invitations are the app's product, doorman's part is the membership.

**The price:** IR's checkout sentences that name `:orders/number` and `:orders/credit` are rewritten to name `:orders/place`, since Postgres now hears only the handler's action.

**Instead of:** building several named scopes now, for no endpoint; an invitations part in doorman.
