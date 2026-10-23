(ns doorman.askers
  "Who is asking, found from a request by the proof doorman made and reads
   itself, and nothing else:

     a cookie   Cookie: app_session=…           a browser's session; a request that changes
                                                something must carry the app's Origin
     a token    Authorization: Bearer app_…     a script's, as the person who made it; no
                                                Origin check (a browser never sends it on its own)

   A request carrying both is refused; one carrying neither is nobody's. A
   token's asker is its person with no session flags (a token has none),
   and no visitor. The find-session step puts the asker in the context;
   run-as-asker-of-request! runs a transaction as the asker of a request;
   a token is made, listed and revoked by the asker of the request."
  (:require
   [cellar.api :as cellar]
   [doorman.asking :as asking]
   [doorman.cookie :as cookie]
   [doorman.memberships :as memberships]
   [doorman.origin :as origin]
   [doorman.sessions :as sessions]
   [doorman.settings :as settings]
   [doorman.tokens :as tokens]
   [logbook.api :as logbook]
   [bridge.api :as bridge]))

(def ^:private nobody {:person-id nil :visitor-id nil :session-flags #{}})

(def ^:private not-from-the-app
  {:error "This request didn't come from the app's own pages."})

(def ^:private cookie-and-token
  {:error "A request carries a session's cookie or a token, not both."})

(defn find-token-text
  "The text of the request's Authorization: Bearer header, or nil. Any
   other kind of Authorization (Basic) is not doorman's, and is no token."
  [request]
  (some->> (get-in request [:headers "authorization"])
           (re-matches #"(?i)bearer\s+(\S+)\s*")
           second))

(defn find-asker-of-request
  "{:asker {:person-id :visitor-id :session-flags} :token-id id-or-nil},
   or {:refusal answer} for a request doorman answers with an error: one
   carrying both a cookie and a token, or one changing something by a
   cookie (or nothing) without the app's Origin."
  [pool request {:keys [cookie-name allowed-origin] :as settings}]
  (let [token-text (find-token-text request)
        cookie?    (some? (cookie/find-cookie-value request cookie-name))]
    (cond
      (and token-text cookie?)
      {:refusal cookie-and-token}

      token-text
      (if-let [{:token/keys [id person-id]} (tokens/find-live-token pool token-text)]
        {:asker {:person-id person-id :visitor-id nil :session-flags #{}} :token-id id}
        {:asker nobody :token-id nil})

      (not (origin/does-request-come-from-allowed-origin? request allowed-origin))
      {:refusal not-from-the-app}

      :else
      {:asker (sessions/find-session-asker! pool request settings) :token-id nil})))

(defn put-asker-into-context
  "The context with the asker at :doorman/person-id, :doorman/visitor-id and
   :doorman/session-flags, and the person (and the token) or the visitor
   added to the message's log, so every line the later steps write says
   who asked."
  [context {:keys [person-id visitor-id session-flags]} token-id]
  (cond-> (assoc context
                 :doorman/person-id person-id
                 :doorman/visitor-id visitor-id
                 :doorman/session-flags session-flags)
    person-id  (update :bridge/log logbook/with-context (cond-> {:person-id person-id} token-id (assoc :token-id token-id)))
    visitor-id (update :bridge/log logbook/with-context {:visitor-id visitor-id})))

(defn make-find-session-step [pool settings]
  (let [settings (settings/check-settings settings)]
    {:name  :doorman/find-session
     :enter (fn [context]
              (let [{:keys [refusal asker token-id]} (find-asker-of-request pool (:bridge/request context) settings)]
                (if refusal
                  (bridge/add-answer-to-context context refusal)
                  (put-asker-into-context context asker token-id))))}))

(defn find-session-key-of-request
  "A hash of the request's token, else of its cookie, or nil."
  [request {:keys [cookie-name]}]
  (if-let [token-text (find-token-text request)]
    (tokens/turn-text-into-fingerprint token-text)
    (some-> (cookie/find-cookie-value request cookie-name) cookie/turn-cookie-value-into-fingerprint)))

(defn- find-asker-or-throw [pool request settings]
  (let [{:keys [refusal] :as found} (find-asker-of-request pool request settings)]
    (when refusal
      (throw (ex-info (:error refusal) {:type :doorman/refused-request :answer refusal})))
    found))

(defn run-as-asker-of-request!
  "Runs (f transaction) in one transaction of its own, as the asker of the
   request. A transaction handed in is refused: who asks would change
   under its other statements."
  [pool request {:keys [settings read-only? scope-kinds]} f]
  (when (cellar/is-transaction? pool)
    (throw (ex-info "run-as-asker-of-request! runs in a transaction of its own: hand it the pool"
                    {:type :doorman/asker-in-a-transaction})))
  (let [{{:keys [person-id] :as asker} :asker} (find-asker-or-throw pool request settings)]
    (cellar/run-in-transaction! pool {:read-only? read-only?}
                                (fn [transaction]
                                  (asking/set-asking! transaction asker
                                                      {:acting  :every-action
                                                       :holding (if person-id (memberships/list-actions-of-person transaction scope-kinds person-id) {})})
                                  (f transaction)))))

(defn make-token! [pool request {:keys [settings] :as options}]
  (let [{{:keys [person-id session-flags]} :asker :keys [token-id]} (find-asker-or-throw pool request settings)]
    (cond
      (nil? person-id)
      (throw (ex-info "Only a signed-in person makes a token" {:type :doorman/no-token-for-asker :reason :nobody}))

      token-id
      (throw (ex-info "A token makes no token: a signed-in session does" {:type :doorman/no-token-for-asker :reason :token}))

      (seq session-flags)
      (throw (ex-info (str "A session with flags makes no token: the token, having none, would shed them " (pr-str session-flags))
                      {:type :doorman/no-token-for-asker :reason :session-flags :session-flags session-flags}))

      :else
      (tokens/insert-token! pool person-id options))))

(defn list-tokens [pool request settings]
  (let [{{:keys [person-id]} :asker} (find-asker-or-throw pool request settings)]
    (if person-id (tokens/list-tokens-of-person pool person-id) [])))

(defn revoke-token! [pool request settings token-id]
  (let [{{:keys [person-id]} :asker} (find-asker-or-throw pool request settings)]
    (boolean (and person-id (tokens/delete-token-of-person! pool person-id token-id)))))
