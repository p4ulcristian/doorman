# The session is the only truth

**Decision:** who is signed in is decided by Groundwork's session alone. An identity provider (a single sign-on service, a gate, a password check) is asked once, at sign-in; its tokens aren't kept to renew it in the background. Ending someone's access means deleting their sessions: sign-out, sign-out everywhere, a removed member.

**Why:** one place answers "who is this", so there is nothing to keep in sync. A background renewal would need a locked update of the session on every request, or parallel requests overwrite each other. Deleting rows ends access at once, on every server.

**The price:** when the provider disables an account, its sessions here live on until they end or are deleted; the app ends them when it learns of it (a webhook from the provider, a removed member).

**Instead of:** keeping the provider's tokens and renewing them silently, which keeps two sessions alive that must agree.
