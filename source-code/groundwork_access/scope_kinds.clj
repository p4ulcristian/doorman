(ns groundwork-access.scope-kinds
  "The app's kinds of scope, in its code, every key written out:

     {:company {:table   :companies
                :parent  nil
                :single? false
                :actions {:company/report #{:read}}
                :roles   {\"Director\" #{:company/report :orders/see}}
                :denies  []}
      :shop    {:table   :shops
                :parent  :company                       ; a membership in a company counts in its shops
                :single? false
                :actions {:orders/see #{:read} :orders/edit #{:read :insert :update}}
                :roles   {\"Staff\" #{:orders/see :orders/edit} \"Bookkeeper\" #{:orders/see}}
                :denies  [{:deny :writes :session :impersonated}            ; a session flag says no
                          {:deny :writes :when [:= :shop/status \"suspended\"]}]}   ; a scope's state says no
      :platform {:table nil :parent nil :single? true  ; exactly one scope: the back office
                 :actions {:platform/support #{:read}} :roles {} :denies []}}

   Every scope also has the role \"Owner\", holding every action of its
   kind and of the kinds below it. A role holds actions of its kind and of
   the kinds below it. An action belongs to one kind.

   A deny sits on a kind and is about the kind's own actions: a set of
   them (or one), or :writes, every one of them that writes. It says no by
   a session flag (:session), or by the state of a scope of the kind
   (:when, a condition on the kind's :table)."
  (:require
   [clojure.string :as str]
   [malli.core :as malli]
   [malli.error :as malli-error]))

(def owner-role "Owner")

(def operations #{:read :insert :update :delete})

(def writing-operations #{:insert :update :delete})

(def ^:private flag-shape
  [:fn {:error/message "should be a keyword without a namespace, like :impersonated"}
   #(and (simple-keyword? %) (re-matches #"[a-z0-9-]+" (name %)))])

(def ^:private denied-actions-shape
  [:or [:= :writes] :qualified-keyword [:set {:min 1} :qualified-keyword]])

(def deny-shape
  "A deny: by a session flag, or by a scope's state."
  [:or
   [:map {:closed true} [:deny denied-actions-shape] [:session flag-shape]]
   [:map {:closed true} [:deny denied-actions-shape] [:when [:and vector? [:fn seq]]]]])

(def kind-shape
  "One kind of scope, every key required."
  [:map {:closed true}
   [:table [:maybe :keyword]]
   [:parent [:maybe :keyword]]
   [:single? :boolean]
   [:actions [:map-of :qualified-keyword [:set {:min 1} [:enum :read :insert :update :delete]]]]
   [:roles [:map-of :string [:set :qualified-keyword]]]
   [:denies [:sequential deny-shape]]])

(defn find-ancestors
  "The kinds above a kind, nearest first; a loop stops where it repeats."
  [scope-kinds kind]
  (loop [above (get-in scope-kinds [kind :parent]) seen []]
    (if (or (nil? above) (= above kind) (some #{above} seen))
      seen
      (recur (get-in scope-kinds [above :parent]) (conj seen above)))))

(defn- has-loop? [scope-kinds kind]
  (let [ancestors (find-ancestors scope-kinds kind)
        last-one  (or (peek ancestors) kind)
        next-one  (get-in scope-kinds [last-one :parent])]
    (and (some? next-one) (contains? scope-kinds next-one))))

(defn is-kind-at-or-below?
  "Whether kind is above itself, or below it."
  [scope-kinds kind above]
  (or (= kind above) (boolean (some #{above} (find-ancestors scope-kinds kind)))))

(defn find-kind-of-action
  "The kind an action belongs to, or nil."
  [scope-kinds action]
  (some (fn [[kind {:keys [actions]}]] (when (contains? actions action) kind)) scope-kinds))

(defn find-what-action-does
  "#{:read …}: what an action does to rows, or nil for an action no kind has."
  [scope-kinds action]
  (some #(get-in % [:actions action]) (vals scope-kinds)))

(defn does-action-write?
  "Whether an action inserts, updates or deletes."
  [scope-kinds action]
  (boolean (some writing-operations (find-what-action-does scope-kinds action))))

(defn find-actions-at-or-below
  "Every action of a kind and of the kinds below it."
  [scope-kinds kind]
  (set (for [[other {:keys [actions]}] scope-kinds
             :when (is-kind-at-or-below? scope-kinds other kind)
             action (keys actions)]
         action)))

(defn- find-shape-problems [[kind spec]]
  (when-let [explanation (malli/explain kind-shape spec)]
    [(str kind ": " (pr-str (malli-error/humanize explanation)))]))

(defn- find-relation-problems [scope-kinds [kind {:keys [parent roles denies actions]}]]
  (concat
   (when (contains? roles owner-role)
     [(str kind ": \"Owner\" is groundwork-access's own role, holding every action")])
   (when (and parent (not (contains? scope-kinds parent)))
     [(str kind ": its :parent " parent " is no kind")])
   (when (has-loop? scope-kinds kind)
     [(str kind ": the kinds' :parent form a loop")])
   (for [[role-name role-actions] roles
         action role-actions
         :let [action-kind (find-kind-of-action scope-kinds action)]
         :when (not (and action-kind (is-kind-at-or-below? scope-kinds action-kind kind)))]
     (str kind ": role " role-name " names " action ", which isn't one of its actions or of a kind below it"))
   (for [action (keys actions)
         [other {other-actions :actions}] scope-kinds
         :when (and (not= other kind) (contains? other-actions action) (neg? (compare (str kind) (str other))))]
     (str action " is an action of both " kind " and " other "; an action belongs to one kind"))
   (for [{denied :deny :as deny} denies
         action (cond (= :writes denied) [] (set? denied) denied :else [denied])
         :when (not (contains? actions action))]
     (str kind ": the deny " (pr-str deny) " names " action ", which isn't one of its actions"))
   (for [deny denies
         :when (and (contains? deny :when) (nil? (get-in scope-kinds [kind :table])))]
     (str kind ": the deny " (pr-str deny) " reads a scope's state, which lives in the kind's :table; it has none"))))

(defn check-scope-kinds
  "Returns the scope kinds; throws with every problem at once."
  [scope-kinds]
  (let [problems (concat (mapcat find-shape-problems scope-kinds)
                         (mapcat #(find-relation-problems scope-kinds %) scope-kinds))]
    (when (seq problems)
      (throw (ex-info (str "The scope kinds have problems:\n  " (str/join "\n  " problems))
                      {:type :groundwork-access/bad-scope-kinds :problems (vec problems)})))
    scope-kinds))

(defn find-actions-of-role
  "The actions a role allows in a kind: Owner holds every one of the kind
   and of the kinds below it."
  [scope-kinds kind role-name role-actions]
  (if (= owner-role role-name)
    (find-actions-at-or-below scope-kinds kind)
    role-actions))

(defn does-action-only-read?
  "Whether the action does nothing but :read in its kind: its handlers run
   in a read-only transaction. An action no kind has is not."
  [scope-kinds action]
  (= #{:read} (find-what-action-does scope-kinds action)))

(defn find-denies
  "Every deny of every kind, read: {:kind :deny (as written) :actions #{the
   actions it is about} :writes? :session flag-or-nil :when condition-or-nil}."
  [scope-kinds]
  (vec (for [[kind {:keys [denies actions]}] scope-kinds
             {denied :deny :as deny} denies]
         {:kind    kind
          :deny    deny
          :writes? (= :writes denied)
          :actions (cond (= :writes denied) (set (filter #(some writing-operations (get actions %)) (keys actions)))
                         (set? denied)      denied
                         :else              #{denied})
          :session (:session deny)
          :when    (:when deny)})))

(defn does-deny-apply?
  "Whether a deny is about an action in an operation: it names the action
   (for every operation), or it is :writes and the operation writes. With
   operation nil, the check's question: may the person do the action at
   all (a writing action is said no to by :writes)."
  [{:keys [actions writes?]} action operation]
  (and (contains? actions action)
       (or (not writes?) (nil? operation) (contains? writing-operations operation))))
