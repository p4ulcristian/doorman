# The app passes its wire; doorman never makes one

> Changes in part [access, piece 5: the leak test](2026-10-22-access-piece-5-the-leak-test.md) (`find-leaks` takes the wire) and [doorman is its own library](2026-10-22-doorman-is-its-own-library.md) (doorman no longer needs wire itself). Since v0.2.0, on mailroom v0.2.0.

**Decision:** wire v0.2.0 makes a wire from settings, and mailroom v0.2.0 takes the app's wire wherever it sends or reads a message. doorman's one call that does so is the leak test's: `find-leaks` asks each handler through mailroom's `answer-message`. So `find-leaks` takes the app's wire as a required key, `:wire`, in its options, and hands it to mailroom; doorman never makes a wire and carries no wire settings.

```clojure
(is (= [] (doorman/find-leaks ready anna-request {:orders [order-in-another-shop]} {:wire wire :seed 7 :size 30})))
```

The key is checked by malli with the rest of the options (`[:wire some?]`; wire has no predicate for a made wire): a missing one refuses, `:doorman/bad-options`, naming `:wire`.

The settings and the options are checked by malli itself, as cellar and showroom check theirs, not through wire's `find-errors-in-data-for-shape`: they are values the app hands in, not data crossing an edge, and checking them through wire would make `sign-in!` and every other function that takes the settings take a wire. So doorman needs wire no more: its `deps.edn` names cellar, mailroom, malli, test.check and jsonista; wire comes with mailroom, and the tests name it to make their wire.

**Why:** the leak test asks the handlers as the browser's messages are answered, so it must use the app's own wire, whose settings the app shows, and not one doorman would make with settings of its own. In the options, beside `:seed` and `:size`, it is written out and refused like them.

**The price:** an app's leak tests write `:wire`. Every caller of `find-leaks` in v0.1 changes.

**Instead of:**
- a wire made inside doorman: a second wire with settings the app doesn't show, answering differently from the app's
- the wire as a first argument: unlike every other value doorman requires, which are named options
- checking the settings through wire: every function that takes the settings would take a wire too
