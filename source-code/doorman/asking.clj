(ns doorman.asking
  "Who is asking, told to Postgres for one transaction: the setting
   doorman.asking, as JSON, which doorman's SQL functions read (see
   create_access_functions.sql)."
  (:require
   [cellar.api :as cellar]
   [jsonista.core :as json]))

(def ^:private set-asking (cellar/make-query-from-sql-file "doorman/set_asking.sql"))

(def ^:private set-passing (cellar/make-query-from-sql-file "doorman/set_passing.sql"))

(defn- turn-actions-into-holding
  "{\"shop orders/see\" [\"scope id\" …]} from {[:shop id] #{:orders/see …}}."
  [actions-by-scope]
  (reduce (fn [holding [[kind scope-id] actions]]
            (reduce #(update %1 (str (name kind) " " (subs (str %2) 1)) (fnil conj []) (str scope-id))
                    holding actions))
          {} actions-by-scope))

(defn turn-asker-into-text
  "The asker as doorman.asking's JSON: the person (or, for nobody signed
   in, the visitor), the session's flags, and the scopes holding each action."
  [{:keys [person-id visitor-id session-flags]} actions-by-scope]
  (json/write-value-as-string
   {:person  (some-> person-id str)
    :visitor (when-not person-id (some-> visitor-id str))
    :flags   (vec (sort (map name session-flags)))
    :holding (turn-actions-into-holding actions-by-scope)}))

(defn set-asking!
  "Tells Postgres, for this transaction, who is asking."
  [transaction asker actions-by-scope]
  (cellar/find-one-row transaction set-asking {:asking (turn-asker-into-text asker actions-by-scope)}))

(defn set-moving!
  "Tells Postgres, for this transaction, that sign-in moves a visitor's
   rows to the person; nobody else is asking."
  [transaction visitor-id person-id]
  (cellar/find-one-row transaction set-asking
                       {:asking (json/write-value-as-string {:moving {:visitor (str visitor-id) :person (str person-id)}})}))

(defn set-system!
  "Tells Postgres, for this transaction, that a named system actor is
   asking; no person, no visitor, no scope held."
  [transaction system-name]
  (cellar/find-one-row transaction set-asking {:asking (json/write-value-as-string {:system (name system-name)})}))

(defn set-passing!
  "Reads past the gates for the rest of this transaction: for a read-only
   look (explain-refusal), never for a handler's steps."
  [transaction]
  (cellar/find-one-row transaction set-passing {}))
