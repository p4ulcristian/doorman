# Sessions for visitors, and none for servers

> Moved from Groundwork (2026-10-22): the sessions part of Groundwork's "Pages for other sites, visitors, and servers". Embeddable pages, and declaring a route's kind, stay in Groundwork's copy.

**Decision:** two kinds of request beyond a signed-in person on the app's own pages:

- **Visitor sessions.** Someone not signed in gets a session the first time the app keeps something for them (a cart, uploaded designs), not on every request. It ends after `:visitor-hours` unseen, a required setting; each visit pushes the end out, at most once an hour. At sign-in its data moves into the signed-in session, under a new key, so a key planted before the login is worthless after it.
- **Server-to-server requests.** A route the app declares server-to-server is called by another server (a payment provider's webhook): no session is read or made, and the Origin check doesn't apply; the handler authenticates the call itself, like a signature. Every other request is a browser's and keeps the Origin check.

**Why:** people fill a cart before they sign in, and providers call the server directly. Each is declared, so the default stays strict: no session for nobody, every changing request from the app's own pages.

**The price:** in another site's frame the browser doesn't send the session cookie, so a flow that needs it (sign-in, checkout) opens on the app's own site. A visitor in a frame is kept in a second cookie made for frames (SameSite=None, Partitioned), which never holds anyone signed in. A server-to-server request has no protection from doorman; its handler must check the call.

**Instead of:** a session for every visitor (a row per crawler hit), or turning the Origin check off for webhooks app-wide.
