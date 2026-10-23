(ns groundwork-access.tokens
  "A person's tokens: a session without a browser, for a script or an AI
   acting as them. The text is the settings' :token-lead and 43 random
   characters (32 random bytes, URL-safe base64), shown once when it is
   made; the table keeps only the SHA-256 of the whole text, the first
   :token-shown-characters for the list, a name and an end. Who makes,
   lists and revokes one is found from the request (groundwork-access.askers); this
   namespace only reads and writes the rows."
  (:require
   [groundwork-postgres.api :as postgres]
   [groundwork-access.cookie :as cookie])
  (:import
   (java.time Duration Instant OffsetDateTime ZoneOffset)
   (java.util UUID)))

(defn- make-query [file-name]
  (postgres/make-query-from-sql-file (str "groundwork_access/" file-name)))

(def ^:private insert-token            (make-query "insert_token.sql"))
(def ^:private find-token              (make-query "find_token.sql"))
(def ^:private find-tokens-of-person   (make-query "find_tokens_of_person.sql"))
(def ^:private delete-token-of-person  (make-query "delete_token_of_person.sql"))
(def ^:private delete-tokens-of-person (make-query "delete_tokens_of_person.sql"))
(def ^:private delete-ended-tokens     (make-query "delete_ended_tokens.sql"))

(defn turn-text-into-fingerprint
  "The SHA-256 of the token's whole text, as hex, as a session's cookie's."
  [text]
  (cookie/turn-cookie-value-into-fingerprint text))

(defn- check-ends-at
  "Throws unless ends-at is after now and at most :token-longest-days from now."
  [^Instant ends-at {:keys [token-longest-days]}]
  (let [now     (Instant/now)
        longest (.plus now (Duration/ofDays token-longest-days))]
    (when-not (.isAfter ends-at now)
      (throw (ex-info "A token's end date is after now" {:type :groundwork-access/bad-token-end :ends-at ends-at})))
    (when (.isAfter ends-at longest)
      (throw (ex-info (str "A token's end date is at most " token-longest-days " days from now (:token-longest-days)")
                      {:type :groundwork-access/bad-token-end :ends-at ends-at :longest longest})))))

(defn insert-token!
  "Makes a token for the person: {:token-id :text :shown-start :ends-at},
   the text shown this once."
  [pool person-id {:keys [settings name ends-at]}]
  (let [{:keys [token-lead token-shown-characters]} settings
        _        (check-ends-at ends-at settings)
        text     (str token-lead (cookie/make-cookie-value))
        token-id (UUID/randomUUID)
        shown    (subs text 0 token-shown-characters)]
    (postgres/run-statement! pool insert-token {:token/id          token-id
                                              :token/fingerprint (turn-text-into-fingerprint text)
                                              :token/person-id   person-id
                                              :token/name        name
                                              :token/shown-start shown
                                              :token/ends-at     (OffsetDateTime/ofInstant ends-at ZoneOffset/UTC)})
    {:token-id token-id :text text :shown-start shown :ends-at ends-at}))

(defn find-live-token
  "{:token/id :token/person-id} of a token's text, or nil for one that is
   wrong, has ended or was revoked."
  [pool text]
  (postgres/find-one-row pool find-token {:token/fingerprint (turn-text-into-fingerprint text)}))

(defn- turn-into-instant [^OffsetDateTime time] (.toInstant time))

(defn list-tokens-of-person
  "The person's tokens that haven't ended, newest first: [{:token-id :name
   :shown-start :created-at :ends-at}], never their text."
  [pool person-id]
  (mapv (fn [{:token/keys [id name shown-start created-at ends-at]}]
          {:token-id id :name name :shown-start shown-start
           :created-at (turn-into-instant created-at) :ends-at (turn-into-instant ends-at)})
        (postgres/find-rows pool find-tokens-of-person {:token/person-id person-id})))

(defn delete-token-of-person!
  "Ends the person's token at once; whether it was theirs and there."
  [pool person-id token-id]
  (pos? (postgres/run-statement! pool delete-token-of-person {:token/id token-id :token/person-id person-id})))

(defn delete-tokens-of-person!
  "Ends every token of the person at once; how many."
  [pool person-id]
  (postgres/run-statement! pool delete-tokens-of-person {:token/person-id person-id}))

(defn delete-ended-tokens!
  "Forgets tokens that have ended; how many."
  [pool]
  (postgres/run-statement! pool delete-ended-tokens {}))
