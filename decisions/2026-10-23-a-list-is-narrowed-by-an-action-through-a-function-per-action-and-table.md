# A list is narrowed by an action through a function per action and table

> Since v0.7.0. Builds "To narrow a list by another action" of [Access rules are sentences in doorman's language](2026-10-17-access-rules-are-sentences-in-doormans-language.md)'s "Settled while building".

**Decision:** for every table with sentences and a one-column primary key, and every action its sentences allow, `install-access-functions!` makes `doorman."may <action> on <table>"()`: the ids of the rows the asker may do that action on, by the action's condition, its denies in. It runs as the caller, behind the gates, so it answers only rows the asker may read. A query file narrows a list with it:

```sql
SELECT order__id, order__note FROM orders
WHERE order__id IN (SELECT * FROM doorman."may orders/edit on orders"())
```

A name longer than Postgres's 63 bytes is refused at the start, named.

**Why:** a list shows every row the asker may read; "only the orders I may edit" is another action's condition, which the app can't write (it is made from the sentences) and must not copy. A function named by what it answers is found by reading the query.

**The price:** one function per action and table, made whether a query calls it or not; a table without a one-column primary key has none.

**Instead of:** a SQL fragment the app pastes into its query text: the query's SQL lives in its file, and a fragment changes with the sentences.
