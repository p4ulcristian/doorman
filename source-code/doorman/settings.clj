(ns doorman.settings
  "doorman's settings, and the options put-access-into-handlers takes,
   checked before anything runs. Nothing has a default: every setting is
   written out, and a missing one refuses, named, with every other problem
   at once. They are checked by malli itself: they are values the app hands
   in, not data crossing an edge."
  (:require
   [malli.core :as malli]
   [malli.error :as malli-error]))

(def settings-shape
  "The settings, every key required.
   :cookie-name: the session cookie's name. Cookies don't tell ports apart,
   so two apps on one host (localhost in development) need two names.
   :session-days: how long a signed-in person's session lasts unseen.
   :visitor-session-days: how long a visitor's session (someone not
   signed in, for whom the app keeps something) lasts unseen; its rows
   are nobody's once it has ended.
   :renew-after-minutes: a session seen again lasts its days from then,
   at most once in this many minutes (one write, not one per request).
   :allowed-origin: the app's own address; a request that changes something
   must carry it as its Origin.
   :secure-cookie?: false only in local development, over plain http."
  [:map {:closed true}
   [:cookie-name [:re #"^[A-Za-z0-9_-]+$"]]
   [:session-days pos-int?]
   [:visitor-session-days pos-int?]
   [:renew-after-minutes pos-int?]
   [:allowed-origin :string]
   [:secure-cookie? :boolean]])

(def ^:private flags-shape
  [:set [:fn {:error/message "should be a keyword without a namespace, like :impersonated"}
         #(and (simple-keyword? %) (re-matches #"[a-z0-9-]+" (name %)))]])

(def sign-in-options-shape
  "sign-in!'s options, every key required.
   :settings: the settings (settings-shape).
   :tables: the app's table maps; those with a :visitor column that
   :moves-to a person column have the visitor's rows moved to the person.
   :session-flags: the session's flags, #{} for none (#{:impersonated}
   for an admin acting as someone): the denies that name one say no."
  [:map {:closed true}
   [:settings map?]
   [:tables [:sequential map?]]
   [:session-flags flags-shape]])

(def asker-shape
  "Who explain-refusal explains for, every key required: the session's
   person, its visitor (for nobody signed in) and its flags."
  [:map {:closed true}
   [:person-id [:maybe :uuid]]
   [:visitor-id [:maybe :uuid]]
   [:session-flags flags-shape]])

(def explain-options-shape
  "explain-refusal's options, every key required.
   :pool: the app's own pool; the explaining runs read-only.
   :handlers: the app's handlers, by name, each with its :action (and
   :record).
   :tables: the app's table maps, with their :access sentences.
   :scope-kinds: the app's kinds of scope (check-scope-kinds)."
  [:map {:closed true}
   [:pool some?]
   [:handlers [:map-of :keyword map?]]
   [:tables [:sequential map?]]
   [:scope-kinds [:map-of :keyword map?]]])

(def access-options-shape
  "put-access-into-handlers's options, every key required.
   :pool: the app's own pool, its role owning nothing, so the gates hold.
   :tables: the app's table maps, with their :access sentences.
   :scope-kinds: the app's kinds of scope (check-scope-kinds).
   :find-session-step: make-find-session-step's step."
  [:map {:closed true}
   [:pool some?]
   [:tables [:sequential map?]]
   [:scope-kinds [:map-of :keyword map?]]
   [:find-session-step [:map [:name :keyword] [:enter fn?]]]])

(def my-actions-options-shape
  "make-my-actions-handler's options, every key required.
   :pool: the app's own pool, as put-access-into-handlers's.
   :tables: the app's table maps, whose :access sentences say which
   actions are open without a membership.
   :scope-kinds: the app's kinds of scope (check-scope-kinds).
   :find-session-step: make-find-session-step's step.
   :rate-limit: bridge's, {:max 60 :per-ms 60000} per asker, or nil for
   none."
  [:map {:closed true}
   [:pool some?]
   [:tables [:sequential map?]]
   [:scope-kinds [:map-of :keyword map?]]
   [:find-session-step [:map [:name :keyword] [:enter fn?]]]
   [:rate-limit [:maybe [:map {:closed true} [:max [:int {:min 1}]] [:per-ms [:int {:min 1}]]]]]])

(def system-options-shape
  "run-as-system!'s options, every key required.
   :read-only?: true, Postgres refuses every write in its transaction."
  [:map {:closed true}
   [:read-only? :boolean]])

(def asker-run-options-shape
  "run-as-asker!'s options, every key required.
   :read-only?: true, Postgres refuses every write in its transaction.
   :scope-kinds: the app's, checked (check-scope-kinds): which actions the
   person's memberships hold."
  [:map {:closed true}
   [:read-only? :boolean]
   [:scope-kinds map?]])

(def leak-options-shape
  "find-leaks's options, every key required: malli's generator makes each
   handler's other params from its :params-shape.
   :wire: the app's wire (wire.api's make-wire-from-settings), which each
   message is answered by, as over http (bridge's answer-message);
   doorman never makes one.
   :log: the app's log (logbook's log-function), which each message's log
   is made from, as over http; doorman never makes one.
   :seed: the same seed makes the same params every run.
   :size: how big the made-up values get (malli's 30 when it chose)."
  [:map {:closed true}
   [:wire [some? {:error/message "should be the app's wire, made by wire.api/make-wire-from-settings"}]]
   [:log [fn? {:error/message "should be the app's log function, made by logbook"}]]
   [:seed :int]
   [:size [:int {:min 0}]]])

(defn- check-data-for-shape [what kind shape data]
  (when-let [explanation (malli/explain shape data)]
    (let [errors (malli-error/humanize explanation)]
      (throw (ex-info (str what " have problems: " (pr-str errors))
                      {:type kind :errors errors}))))
  data)

(defn check-settings
  "Returns the settings; throws with every problem at once."
  [settings]
  (check-data-for-shape "The settings" :doorman/bad-settings settings-shape settings))

(defn check-sign-in-options
  "Returns the options, the settings in them checked too; throws with
   every problem at once."
  [options]
  (check-data-for-shape "The options" :doorman/bad-options sign-in-options-shape options)
  (check-settings (:settings options))
  options)

(defn check-asker
  "Returns the asker; throws with every problem at once."
  [asker]
  (check-data-for-shape "The asker" :doorman/bad-asker asker-shape asker))

(defn check-explain-options
  "Returns the options; throws with every problem at once."
  [options]
  (check-data-for-shape "The options" :doorman/bad-options explain-options-shape options))

(defn check-access-options
  "Returns the options; throws with every problem at once."
  [options]
  (check-data-for-shape "The options" :doorman/bad-options access-options-shape options))

(defn check-my-actions-options
  "Returns the options; throws with every problem at once."
  [options]
  (check-data-for-shape "The options" :doorman/bad-options my-actions-options-shape options))

(defn check-system-options
  "Returns the options; throws with every problem at once."
  [options]
  (check-data-for-shape "The options" :doorman/bad-options system-options-shape options))

(defn check-asker-run-options
  "Returns the options; throws with every problem at once."
  [options]
  (check-data-for-shape "The options" :doorman/bad-options asker-run-options-shape options))

(defn check-leak-options
  "Returns the options; throws with every problem at once."
  [options]
  (check-data-for-shape "The options" :doorman/bad-options leak-options-shape options))
