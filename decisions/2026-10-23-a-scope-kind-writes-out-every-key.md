# A scope kind writes out every key

> Since v0.7.0. Changes in part [access, piece 1](2026-10-22-access-piece-1-who-belongs-where.md), following [doorman is its own library](2026-10-22-doorman-is-its-own-library.md)'s "No defaults".

**Decision:** every kind of scope writes out `:table` (the app's table of its scopes, or `nil`), `:parent` (or `nil`), `:single?`, `:actions`, `:roles` and `:denies` (`[]` for none); the map is closed. `check-scope-kinds` refuses a missing or unknown key with every other problem at once.

**Why:** a kind's parent, whether it has one scope and what may say no are behaviour; unwritten, the value in effect is invisible in the app's code.

**The price:** every app's scope kinds gain three keys.

**Instead of:** `nil`, `false` and `[]` as unwritten defaults.
