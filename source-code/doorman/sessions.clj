(ns doorman.sessions
  "Who is asking: a session is a row in Postgres, the browser's cookie holds
   only random bytes, the table only their fingerprint."
  (:require
   [cellar.api :as cellar]
   [doorman.cookie :as cookie]
   [doorman.origin :as origin]
   [doorman.settings :as settings]
   [mailroom.api :as mailroom])
  (:import
   (java.time Duration Instant OffsetDateTime ZoneOffset)))

(defn- make-query [file-name]
  (cellar/make-query-from-sql-file (str "doorman/" file-name)))

(def ^:private insert-session            (make-query "insert_session.sql"))
(def ^:private find-session              (make-query "find_session.sql"))
(def ^:private update-session-ends-at    (make-query "update_session_ends_at.sql"))
(def ^:private delete-session            (make-query "delete_session.sql"))
(def ^:private delete-sessions-of-person (make-query "delete_sessions_of_person.sql"))
(def ^:private delete-ended-sessions     (make-query "delete_ended_sessions.sql"))

(defn- find-ends-at [{:keys [session-days]}]
  (OffsetDateTime/ofInstant (.plus (Instant/now) (Duration/ofDays session-days)) ZoneOffset/UTC))

(defn sign-in! [pool person-id settings]
  (let [{:keys [cookie-name session-days secure-cookie?]} (settings/check-settings settings)
        cookie-value (cookie/make-cookie-value)]
    (cellar/run-statement! pool insert-session {:session/fingerprint (cookie/turn-cookie-value-into-fingerprint cookie-value)
                                                :session/person-id   person-id
                                                :session/ends-at     (find-ends-at settings)})
    {:set-cookie (cookie/make-set-cookie-text cookie-name cookie-value (* session-days 86400) secure-cookie?)}))

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
  [ends-at {:keys [session-days renew-after-minutes]}]
  (.isBefore (.toInstant ^OffsetDateTime ends-at)
             (.minus (.plus (Instant/now) (Duration/ofDays session-days)) (Duration/ofMinutes renew-after-minutes))))

(defn- find-person-id [pool request {:keys [cookie-name] :as settings}]
  (when-let [cookie-value (cookie/find-cookie-value request cookie-name)]
    (let [fingerprint (cookie/turn-cookie-value-into-fingerprint cookie-value)]
      (when-let [{:session/keys [person-id ends-at]} (cellar/find-one-row pool find-session {:session/fingerprint fingerprint})]
        (when (is-due-for-longer-life? ends-at settings)
          (cellar/run-statement! pool update-session-ends-at {:session/fingerprint fingerprint
                                                              :session/ends-at     (find-ends-at settings)}))
        person-id))))

(defn find-session-key-of-request [request {:keys [cookie-name]}]
  (some-> (cookie/find-cookie-value request cookie-name) cookie/turn-cookie-value-into-fingerprint))

(def ^:private not-from-the-app
  {:error "This request didn't come from the app's own pages."})

(defn make-find-session-step [pool settings]
  (let [{:keys [allowed-origin] :as settings} (settings/check-settings settings)]
    {:name  :doorman/find-session
     :enter (fn [context]
              (let [request (:mailroom/request context)]
                (if (origin/does-request-come-from-allowed-origin? request allowed-origin)
                  (assoc context :doorman/person-id (find-person-id pool request settings))
                  (mailroom/add-answer-to-context context not-from-the-app))))}))
