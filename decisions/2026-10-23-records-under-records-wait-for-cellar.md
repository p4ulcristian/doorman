# Records under records wait for cellar's matching links

> **Replaced** by [Records under records carry their parent's columns, by cellar's link and a fill](2026-10-23-records-under-records-carry-their-parents-columns.md) (2026-10-23): cellar has the link and the fill since v0.4.5, and doorman v0.7.5 builds them.

> Since v0.7.0. About [Every scoped table names its scope](2026-10-12-every-scoped-table-names-its-scope.md), [A record of two scopes may have a parent and children](2026-10-13-a-record-of-two-scopes-may-have-a-parent-and-children.md) and [A record between fixed sides carries them](2026-10-13-a-record-between-fixed-sides-carries-them.md), and the "Settled while building" line "A record kind's `:parent` stays" of [Access rules are sentences in doorman's language](2026-10-17-access-rules-are-sentences-in-doormans-language.md).

**Decision:** doorman v0.7.0 doesn't build a record's parent (a job carrying its order's shop, a person carried down): the matching link, the fill on insert and the change carried down to children. Their access half is built: a row's scope and person columns are ordinary columns its sentences name, several of them on one row, and the platform's side is a kind of one scope. The integrity half waits for cellar to make a composite reference (the child's parent and carried columns pointing at the parent's id and columns, `ON UPDATE CASCADE`) and a fill, from the table map.

**Why:** the link is a constraint on the app's tables, and cellar makes those tables from their maps: it references only a one-column primary key, and drops a trigger no map names, reporting the database as not current. Built in doorman, a link would be undone by the next migrate; built in the gates, a parent's scope change couldn't reach its children.

**The price:** until cellar has it, a child's carried scope is the app's to keep equal to its parent's.

**Instead of:** a check in the write gates that a child's carried columns equal its parent's: a parent could then never change them while it had children, and the fill stays undone.
