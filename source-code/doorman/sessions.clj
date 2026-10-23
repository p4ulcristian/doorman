(ns doorman.sessions
  "Who is asking: a session is a row in Postgres, the browser's cookie holds
   only random bytes, the table only their fingerprint. A person's session
   is made at sign-in, with its flags; a visitor's (someone not signed in)
   when the app first keeps something for them, with an id of its own
   that the visitor's rows name. At sign-in the visitor's rows become the
   person's and the old session ends, in one transaction."
  (:require
   [clojure.string :as str]
   [cellar.api :as cellar]
   [doorman.asking :as asking]
   [doorman.cookie :as cookie]
   [doorman.origin :as origin]
   [doorman.settings :as settings]
   [logbook.api :as logbook]
   [bridge.api :as bridge])
  (:import
   (java.time Duration Instant OffsetDateTime ZoneOffset)
   (java.util UUID)))

(defn- make-query [file-name]
  (cellar/make-query-from-sql-file (str "doorman/" file-name)))

(def ^:private insert-session            (make-query "insert_session.sql"))
(def ^:private find-session              (make-query "find_session.sql"))
(def ^:private update-session-ends-at    (make-query "update_session_ends_at.sql"))
(def ^:private delete-session            (make-query "delete_session.sql"))
(def ^:private delete-sessions-of-person (make-query "delete_sessions_of_person.sql"))
(def ^:private delete-ended-sessions     (make-query "delete_ended_sessions.sql"))

(defn- find-days [{:keys [session-days visitor-session-days]} person-id]
  (if person-id session-days visitor-session-days))

(defn- find-ends-at [days]
  (OffsetDateTime/ofInstant (.plus (Instant/now) (Duration/ofDays days)) ZoneOffset/UTC))

(defn- turn-flags-into-text [flags] (str/join " " (sort (map name flags))))

(defn- turn-text-into-flags [text] (set (map keyword (remove str/blank? (str/split (or text "") #" ")))))

(defn- find-live-session [pool-or-transaction request {:keys [cookie-name]}]
  (when-let [cookie-value (cookie/find-cookie-value request cookie-name)]
    (let [fingerprint (cookie/turn-cookie-value-into-fingerprint cookie-value)]
      (some-> (cellar/find-one-row pool-or-transaction find-session {:session/fingerprint fingerprint})
              (assoc :session/fingerprint fingerprint)))))

(defn- make-move-query [{:keys [table] {:keys [column moves-to]} :visitor}]
  (let [v (cellar/turn-key-into-column-name column)
        p (cellar/turn-key-into-column-name moves-to)]
    (cellar/make-query-from-sql-text
     (str "move a visitor's rows of " table " to the person")
     (str "UPDATE " (cellar/turn-table-key-into-table-name table) " SET " p " = :move/person-id, " v " = NULL"
          " WHERE " v " = :move/visitor-id"))))

(defn- move-visitor-rows!
  "In every table with a :visitor column that :moves-to a person column:
   the visitor's rows become the person's, the visitor emptied. The gates
   let exactly this through (doorman.find_moving_visitor)."
  [transaction tables visitor-id person-id]
  (asking/set-moving! transaction visitor-id person-id)
  (doseq [table tables :when (get-in table [:visitor :moves-to])]
    (cellar/run-statement! transaction (make-move-query table) {:move/person-id person-id :move/visitor-id visitor-id})))

(defn sign-in! [pool request person-id options]
  (let [{:keys [settings tables session-flags]} (settings/check-sign-in-options options)
        {:keys [cookie-name session-days secure-cookie?]} settings
        cookie-value (cookie/make-cookie-value)]
    (cellar/run-in-transaction! pool {:read-only? false}
                                (fn [transaction]
                                  (when-let [{:session/keys [fingerprint visitor-id]} (find-live-session transaction request settings)]
                                    (when visitor-id
                                      (move-visitor-rows! transaction tables visitor-id person-id)))
        ;; the request's session ends, live or not: a key planted before the login is worthless after it
                                  (when-let [cookie-value (cookie/find-cookie-value request cookie-name)]
                                    (cellar/run-statement! transaction delete-session {:session/fingerprint (cookie/turn-cookie-value-into-fingerprint cookie-value)}))
                                  (cellar/run-statement! transaction insert-session {:session/fingerprint (cookie/turn-cookie-value-into-fingerprint cookie-value)
                                                                                     :session/person-id   person-id
                                                                                     :session/visitor-id  nil
                                                                                     :session/flags       (turn-flags-into-text session-flags)
                                                                                     :session/ends-at     (find-ends-at session-days)})))
    {:set-cookie (cookie/make-set-cookie-text cookie-name cookie-value (* session-days 86400) secure-cookie?)}))

(defn start-visitor-session! [pool request settings]
  (let [{:keys [cookie-name visitor-session-days secure-cookie?] :as settings} (settings/check-settings settings)]
    (if-let [{:session/keys [person-id visitor-id]} (find-live-session pool request settings)]
      {:visitor-id (when-not person-id visitor-id) :set-cookie nil}
      (let [cookie-value (cookie/make-cookie-value)
            visitor-id   (UUID/randomUUID)]
        (cellar/run-statement! pool insert-session {:session/fingerprint (cookie/turn-cookie-value-into-fingerprint cookie-value)
                                                    :session/person-id   nil
                                                    :session/visitor-id  visitor-id
                                                    :session/flags       ""
                                                    :session/ends-at     (find-ends-at visitor-session-days)})
        {:visitor-id visitor-id
         :set-cookie (cookie/make-set-cookie-text cookie-name cookie-value (* visitor-session-days 86400) secure-cookie?)}))))

(defn sign-out! [pool request settings]
  (let [{:keys [cookie-name secure-cookie?]} (settings/check-settings settings)]
    (when-let [cookie-value (cookie/find-cookie-value request cookie-name)]
      (cellar/run-statement! pool delete-session {:session/fingerprint (cookie/turn-cookie-value-into-fingerprint cookie-value)}))
    {:set-cookie (cookie/make-set-cookie-text cookie-name "" 0 secure-cookie?)}))

(defn sign-out-everywhere! [pool person-id]
  (cellar/run-statement! pool delete-sessions-of-person {:session/person-id person-id}))

(defn delete-ended-sessions! [pool]
  (cellar/run-statement! pool delete-ended-sessions {}))

(defn- is-due-for-longer-life?
  "A session seen again is pushed out, at most once in :renew-after-minutes."
  [ends-at days {:keys [renew-after-minutes]}]
  (.isBefore (.toInstant ^OffsetDateTime ends-at)
             (.minus (.plus (Instant/now) (Duration/ofDays days)) (Duration/ofMinutes renew-after-minutes))))

(defn- find-asker
  "{:person-id :visitor-id :session-flags} of the request's session; nils
   and #{} without one."
  [pool request settings]
  (if-let [{:session/keys [fingerprint person-id visitor-id flags ends-at]} (find-live-session pool request settings)]
    (let [days (find-days settings person-id)]
      (when (is-due-for-longer-life? ends-at days settings)
        (cellar/run-statement! pool update-session-ends-at {:session/fingerprint fingerprint
                                                            :session/ends-at     (find-ends-at days)}))
      {:person-id person-id :visitor-id (when-not person-id visitor-id) :session-flags (turn-text-into-flags flags)})
    {:person-id nil :visitor-id nil :session-flags #{}}))

(defn find-session-key-of-request [request {:keys [cookie-name]}]
  (some-> (cookie/find-cookie-value request cookie-name) cookie/turn-cookie-value-into-fingerprint))

(def ^:private not-from-the-app
  {:error "This request didn't come from the app's own pages."})

(defn make-find-session-step [pool settings]
  (let [{:keys [allowed-origin] :as settings} (settings/check-settings settings)]
    {:name  :doorman/find-session
     :enter (fn [context]
              (let [request (:bridge/request context)]
                (if (origin/does-request-come-from-allowed-origin? request allowed-origin)
                  (let [{:keys [person-id visitor-id session-flags]} (find-asker pool request settings)]
                    (cond-> (assoc context
                                   :doorman/person-id person-id
                                   :doorman/visitor-id visitor-id
                                   :doorman/session-flags session-flags)
                      ;; the steps after this one, and what they call, write lines that say who asked
                      person-id  (update :bridge/log logbook/with-context {:person-id person-id})
                      visitor-id (update :bridge/log logbook/with-context {:visitor-id visitor-id})))
                  (bridge/add-answer-to-context context not-from-the-app))))}))
