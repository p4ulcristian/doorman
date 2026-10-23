# doorman owns the shape of "what may I do?"'s answer, on both sides

> **Changed in part** by [The family and its composer are groundwork](https://github.com/p4ulcristian/groundwork/blob/Starting-from-the-bottom/decisions/2026-10-23-the-family-and-its-composer-are-groundwork.md) (2026-10-23): manor, the family, and house, the composer, are both named groundwork now.

> The user's decision, 2026-10-23. Since v0.6.0. Changes in part ["What may I do?" is a handler for the browser](2026-10-23-what-may-i-do-is-a-handler-for-the-browser.md): its answer's shape was written twice, doorman's and parlor's.

**Decision:** the shape of `make-my-actions-handler`'s answer is written once, in doorman, in `doorman.shapes`, a `.cljc` both sides load. The server's `doorman.api/my-actions-answer-shape` is it, and so is the browser's: `doorman.api` gets a `.cljs` half holding only that shape. The app (house) puts it in the browser's shapes by name, as every message's shapes travel, and hands it to parlor, which checks the answer with it. parlor's own copy is gone.

```clojure
;; the app's browser code
{:doorman/my-actions {:params-shape [:map {:closed true}] :answer-shape doorman/my-actions-answer-shape}}
```

**Why:** two copies of one shape drift apart, and nothing said which was right; the server's handler answers by it, so the server's library says it. A message's shapes reach the browser the way the app's own do, by a `.cljc`.

**The price:** doorman, the server's library, has a browser half, one value; an app's browser build reads `doorman.shapes`, which needs only Clojure.

**Instead of:**
- parlor owning the shape: the browser's library would say what the server answers
- a test in house checking the two copies match: two copies still, and the test reads source text across languages
