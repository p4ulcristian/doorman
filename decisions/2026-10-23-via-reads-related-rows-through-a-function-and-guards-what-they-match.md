# :via reads related rows through a function, and guards what they match

> Since v0.7.0. Builds `:via` of [Access rules are sentences in doorman's language](2026-10-17-access-rules-are-sentences-in-doormans-language.md) and its "Settled while building", which replaced Groundwork's passes ("A row can be a pass to one record") and [A record between fixed sides carries them](2026-10-13-a-record-between-fixed-sides-carries-them.md)'s weighing of them.

**Decision:**

```clojure
{:allow :orders/see
 :via {:table :agreements
       :match {:order/shop-id :agreement/shop-id}              ; the row's column → the related row's
       :to    [:member :agreement/partner-id :agreements/see]  ; who, on the related row
       :when  [:= :agreement/status "active"]}}               ; and when, on it
```

- **A sentence has `:to` or `:via`, one of them**, and may have a `:when` on the row as well. The via's `:table` is one of the app's tables; its `:match` columns exist on both sides; its `:to` is any who word said on the related table; a `[:member …]` there names the action held (the related row's kind is usually another than the sentence's action's); its `:when` can't say `[:none …]`.
- **SQL:** the row's matched columns are among the values a function offers, `order__shop_id IN (SELECT via_1 FROM doorman.via_…())`. The function, made by `install-access-functions!`, returns the matched columns of the related rows for which the who and the when hold, for the one asking. It is STABLE and takes no argument, so a statement asks it once, a list is one hashed lookup, and a single row's check the same. It reads the related table past its gates (see [doorman reads past its gates only inside its own functions](2026-10-23-doorman-reads-past-its-gates-only-inside-its-own-functions.md)): Postgres doesn't loop on that table's own gates, and the related rows count by the sentence, not by what the asker may read of them.
- **Indexes**, checked at the start from the table maps (`:indexes`, the primary key, a `:unique?` column): on the row's matched columns, on the related table's, and on its who column.
- **Open:** a via whose who is `:anyone` or `:signed-in` makes its action open, and such a sentence may write (the related row narrows it); a `[:me <visitor column>]` who makes it a visitor's.
- **The guard:** changing a related row in what a via matches (its matched columns, its who column) or making a via's `:when` hold again needs the right to create such a row. The related table's update gate is its update sentences AND (its insert condition OR `doorman.unchanged_…(id, columns…, whens…)`), a function comparing the row with itself before the statement (a STABLE function reads the statement's snapshot). The related table needs a one-column primary key when it can be updated.
- "What may I do?" lists a via's action in each scope its related rows name, when the via matches a column of the row that names a scope (a partner's agreement names the shop); otherwise under the entry for every scope, for whoever holds the who's action.

**Why:** handoffs, agreements, tickets: access through a row that exists while something holds, in any direction, one word instead of a shape per case. A function the policy calls once per statement keeps lists fast, and reading past the related table's gates is what the sentence means. The guard closes the hole the related row opens: whoever may update an agreement (a partner accepting it) could otherwise point it at another shop and read that shop's orders. cellar gives an update one condition for both the old row and the new, so the guard compares with the row before the statement instead of seeing the old row.

**The price:** a `:via` adds a lookup to every statement on its table. Each via is a function of its own, named by a hash of what it reads; a changed via makes a new one and the old stays until dropped by hand. The guard calls a function per updated row of a related table.

**Instead of:**
- a plain EXISTS in the policy: the related table's own gates would decide what counts, and two tables naming each other would loop
- passes, a second word for a via that only reads
- the guard as a trigger: cellar drops triggers no map names, and reports the database as not current
