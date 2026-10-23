# A request reads past its person by a sentence, or by a separate system read before its writes

> The user's decision, 2026-10-23, from Iron Rainbow's port (GROUNDWORK-GAPS.md, P5: 45 system and 73 public endpoints; `with-system` 105 times in 49 files). Builds on [A named system actor is allowed what its sentences give it](2026-10-23-a-named-system-actor-is-allowed-what-its-sentences-give-it.md). Nothing new to build in doorman; this says how apps do it.

> **Changed in part** by [A transaction doorman opens carries its caller's log](2026-10-23-a-transaction-doorman-opens-carries-its-callers-log.md) (2026-10-23, on tidy-up): `run-as-system!`'s options write out `:log` too, `{:read-only? true :log log}`.

**What IR does:** a customer pays once for parts from two shops. The split checkout reads the other order on the same payment and each shop's state block: rows this customer may not see. IR reads them with `with-system`, which reaches everything.

**Decision:** one asker per transaction, and two ways to read past the person:

1. **Where the read is true for the person, a sentence says so**, for the handler's action:
   ```clojure
   ;; on the shops' state block: any signed-in buyer may read it while placing an order
   {:allow :orders/place :only #{:read} :to :signed-in}
   ```
2. **The rest is a separate read-only read by a named system actor, before the request's writes**, and a unique key guards the write the read informed:
   ```clojure
   (doorman/run-as-system! pool :checkout {:read-only? true}
                           (fn [transaction] (find-orders-of-payment transaction payment-id)))
   ;; the write: one order per (payment, shop), a unique key in the table, so a stale read can't double it
   ```

The system actor's sentences say exactly what `:checkout` may read, and `find-system-leaks :checkout` proves it.

**Why:** who is asking never changes inside a transaction, so the gates and the logs always name one asker; every read past the person is either true for them or named as a system actor's.

**The price:** the separate read is its own snapshot, not atomic with the writes: a unique key (or a guarded write) catches what changed in between.

**Instead of:**
- **a step that switches to a system actor inside the request's transaction**: who is asking would change mid-transaction; only if a real writing case needs it
- **reading anything as the system** (IR's `with-system`): reaches every row, and nothing proves what it reaches
