(ns doorman.askers
  "A transaction as a given asker, for code on the app's own route (not a
   message's steps): Postgres is told who is asking as for a message (the
   person or visitor, the session's flags, the scopes holding each of the
   person's actions), so the gates hold the rows exactly as they hold a
   message's."
  (:require
   [cellar.api :as cellar]
   [doorman.asking :as asking]
   [doorman.memberships :as memberships]))

(defn run-as-asker!
  "Runs (f transaction) in one transaction of its own, as the asker. A
   transaction handed in is refused: who asks would change under its
   other statements."
  [pool {:keys [person-id] :as asker} {:keys [read-only? scope-kinds]} f]
  (when (cellar/is-transaction? pool)
    (throw (ex-info "run-as-asker! runs in a transaction of its own: hand it the pool"
                    {:type :doorman/asker-in-a-transaction})))
  (cellar/run-in-transaction! pool {:read-only? read-only?}
                              (fn [transaction]
                                (asking/set-asking! transaction asker
                                                    (if person-id (memberships/list-actions-of-person transaction scope-kinds person-id) {}))
                                (f transaction))))
