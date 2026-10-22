# A record can belong to a visitor

> **Changed in part** by [Access rules are sentences in doorman's language](2026-10-17-access-rules-are-sentences-in-doormans-language.md) (2026-10-17): the visitor reaches the row through a `[:me <column>]` sentence, and a scope through a `:via` sentence instead of a pass.

> Adds to [doorman ships permissions](2026-10-12-doorman-ships-permissions.md) and [Sessions for visitors, and none for servers](2026-10-12-sessions-for-visitors-and-none-for-servers.md).

**Decision:** a record kind can name a visitor column beside, or instead of, its person column. The wall shows such a row to the visitor session it names, to read and to change, and to nobody else.

- **The visitor's id is Groundwork's,** the one of its visitor session. The app doesn't make or keep one, and the browser never sends it as a value.
- **At sign-in Groundwork moves the visitor's rows to the person,** in the step that already moves the session's data: in every table with a visitor column, the person is set and the visitor emptied. The app writes no hook for it.
- **When the visitor session ends,** its rows are nobody's: only the system sees them, and the app's cleanup may remove them.
- A scope reaches a visitor's record the way it reaches a person's: through a pass.
- **The leak test** asks for a visitor's records as another visitor and as a signed-in stranger, and expects "not found".

**Why:** people make things before they sign in: a design, an upload, a cart. Those rows had no owner the wall knows, so the endpoints the whole internet can call ran as the system, with the app comparing an id of its own in each query. And the move to the person at sign-in was the app's to remember.

**The price:** a visitor column on such tables, and one more thing the sign-in step does.

**Instead of:**
- a real person for every visitor, an account without a login: the people table fills with throwaway rows that every count and list has to leave out, and merging one into the real person at sign-in is the same move, written by the app
- running visitors' requests as the system
