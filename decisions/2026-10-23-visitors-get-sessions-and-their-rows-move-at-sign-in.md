# Visitors get sessions of their own, and their rows move to the person at sign-in

> Since v0.7.0. Builds [Sessions for visitors, and none for servers](2026-10-12-sessions-for-visitors-and-none-for-servers.md) and [A record can belong to a visitor](2026-10-13-a-record-can-belong-to-a-visitor.md). Changes in part [sessions v1](2026-10-22-sessions-v1.md): `sign-in!` takes the request and options.

**Decision:**

- **A visitor's session** is a row in `doorman.sessions` like a person's: the cookie holds random bytes, the table their fingerprint; it has no person, and an id of its own, `:session/visitor-id`, made by doorman. `start-visitor-session!` makes one on the app's own route (its Ring request), when the app first keeps something for someone not signed in, and returns `{:visitor-id … :set-cookie …}`. A request that already has a live session makes none (`:set-cookie nil`). It lasts `:visitor-session-days` unseen, a required setting; seen again it is pushed out, at most once in `:renew-after-minutes`, like a person's.
- **The find-session step** puts `:doorman/visitor-id` in the context (nil when someone is signed in, or there is no session), beside `:doorman/person-id` and `:doorman/session-flags`, and adds `{:visitor-id …}` to the message's log as it does the person.
- **A table names its visitor column** on its map: `:visitor {:column :cart/visitor-id :moves-to :cart/person-id}` (`:moves-to nil` for a table with no person column). `[:me :cart/visitor-id]` is then the visitor asking, `doorman.find_asking_visitor()`; a visitor is someone not signed in, so a signed-in person is never one. A `[:me <visitor column>]` action is open to a visitor: a handler without a `:record` runs for one.
- **At sign-in**, `(sign-in! pool request person-id {:settings … :tables … :session-flags …})`, in one transaction: if the request has a live visitor session, every table whose `:visitor` `:moves-to` a person column gets the visitor's rows set to the person, the visitor emptied; the request's session ends, whatever it was, so a key planted before the login is worthless after it; the new session is made.
- **The move passes the gates by one branch of their own:** the transaction tells Postgres only `{"moving": {"visitor": …, "person": …}}`, and each such table's read and update gates let through the visitor's rows and the row after the move (the person's, the visitor emptied). An UPDATE with a WHERE holds the new row to the read gate too, so both carry it.
- **No session data.** A visitor's things are rows naming the visitor, never a blob in the session; the session is who is asking.
- **Servers get no session.** doorman makes one only when `sign-in!` or `start-visitor-session!` is called; a server's request (a webhook) is answered by the app's own route without the find-session step, and proves itself.

**Why:** people make things before they sign in (a cart, a design); with a session of their own, the gates and the check reach those rows the way they reach a person's, through a sentence, instead of the app comparing an id in each query. A message's answer sets no cookie (mailroom answers JSON), so the session is made on the app's own route. The move is doorman's because a forgotten move loses a cart; inside the gates because the app's role owns nothing, and the app's sentences may not let the visitor or the person update such a row.

**The price:** `sign-in!`'s signature changed: the request and three required options. The app has a route (or a page load) that makes the visitor session. A visitor's session lasts `:visitor-session-days`, one length for every visitor (the old "keep longer once it holds designs" is not built). A page in another site's frame gets no Lax cookie, and the decided frame cookie (`SameSite=None; Partitioned`) is not built yet. When a visitor's session ends its rows are nobody's; the app's cleanup removes them.

**Instead of:**
- making the visitor session inside a message: its answer can't set the cookie
- data kept in the session row (Groundwork's `put-data`): two places for one cart, and the gates can't read a blob
- running the move as the system: doorman has no system that passes the gates; a branch only the move's transaction opens is narrower
- a visitor column read from a column option: cellar refuses options it doesn't know, and the map key says both columns at once
