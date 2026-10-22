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
   :session-days: how long a session lasts unseen.
   :renew-after-minutes: a session seen again lasts :session-days from
   then, at most once in this many minutes (one write, not one per
   request).
   :allowed-origin: the app's own address; a request that changes something
   must carry it as its Origin.
   :secure-cookie?: false only in local development, over plain http."
  [:map {:closed true}
   [:cookie-name [:re #"^[A-Za-z0-9_-]+$"]]
   [:session-days pos-int?]
   [:renew-after-minutes pos-int?]
   [:allowed-origin :string]
   [:secure-cookie? :boolean]])

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

(def leak-options-shape
  "find-leaks's options, every key required: malli's generator makes each
   handler's other params from its :params-shape.
   :wire: the app's wire (wire.api's make-wire-from-settings), which each
   message is answered by, as over http (mailroom's answer-message);
   doorman never makes one.
   :seed: the same seed makes the same params every run.
   :size: how big the made-up values get (malli's 30 when it chose)."
  [:map {:closed true}
   [:wire [some? {:error/message "should be the app's wire, made by wire.api/make-wire-from-settings"}]]
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

(defn check-access-options
  "Returns the options; throws with every problem at once."
  [options]
  (check-data-for-shape "The options" :doorman/bad-options access-options-shape options))

(defn check-leak-options
  "Returns the options; throws with every problem at once."
  [options]
  (check-data-for-shape "The options" :doorman/bad-options leak-options-shape options))
