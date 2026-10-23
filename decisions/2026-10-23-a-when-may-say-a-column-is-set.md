# A sentence's :when may say a column is set: [:some col]

> The user's decision, 2026-10-23, from Iron Rainbow's port (GROUNDWORK-GAPS.md, P4: 11 uses). Adds to [Access rules are sentences in doorman's language](2026-10-17-access-rules-are-sentences-in-doormans-language.md). Built on tidy-up, not released.

**Decision:** `:when` gains `[:some col]`, the twin of `[:none col]`: the column is set (SQL `IS NOT NULL`).

```clojure
;; on categories: a shop's own categories, read by anyone; Iron Rainbow's universal ones (no shop) are not
{:allow :catalogue/see :only #{:read} :to :anyone :when [:some :category/workspace-id]}

;; on workspace_parts: a shop's public price shows once the shop set one
{:allow :prices/see :only #{:read} :to :anyone
 :when  [:or [:some :workspace-part/pricing] [:some :workspace-part/template-id] [:some :workspace-part/blueprint]]}
```

**Why:** Iron Rainbow says two things this way: whose row it is (a shop's, not the universal catalogue's) and whether the shop filled it in (a price). One word for one thing, beside `:none`.

**The price:** one more word in `:when`.

**Instead of:**
- **`[:not cond]`**: covers every negation, but gives two ways to say "not equal" (`:not=` and `[:not [:= …]]`)
- **a true/false column kept for the rule** (`has_price`): a column that exists only for an access rule, which the app must keep true

**Settled while building** (the user to review):

- **One SQL word reaches every place a `:when` is read:** a sentence's, a scope's state deny's (`{:deny :writes :when [:some :shop/suspended-at]}`), a `:via`'s on the related row, the guard on a table a `:via` reads, and explain-refusal's `:when` part. The check and the gates read the same conditions, so both hold it.
- **A `:via`'s `:when` may say `[:some …]`** (the related row's column is set, an accepted agreement); `[:none …]` there stays refused, as before.
- **Its column is checked as every `:when`'s:** a column the table doesn't have stops the start, with every other problem. A `[:some …]` on a required column (always true) is not refused, as `[:none …]` on one (always false) never was.
