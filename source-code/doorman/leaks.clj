(ns doorman.leaks
  "The leak test: every handler, asked by a person of one scope with the ids
   of another scope's rows, must not give any of them back.

   A handler with a :record is asked for each foreign row: it must answer
   \"Not found.\". A handler with :scope [kind param] is asked for each
   foreign scope, the ids given under the kind's :table (the options'
   :scope-kinds say which), in its param: it must answer \"Not found.\" (a
   handler that would act in a shop where the asker doesn't hold its
   action is a leak, whatever else she holds there). Any other (a list) is
   asked once: its answer must hold none of the foreign ids. The other params come from the
   handler's :params-shape, by malli's generator, with the options' :seed
   (the same params every run) and :size (how big the made-up values get).
   Each message is answered by bridge's answer-message, by the options'
   :wire and :log."
  (:require
   [bridge.api :as bridge]
   [malli.generator :as generator]))

(defn- generate-params [{:keys [params-shape]} {:keys [seed size]}]
  (generator/generate params-shape {:seed seed :size size}))

(defn- find-texts-in
  "Every UUID or text in an answer, as text: answers are JSON data, where
   an id is text."
  [data]
  (set (map str (filter #(or (uuid? %) (string? %)) (tree-seq coll? seq data)))))

(defn- ask [handlers request handler-name params {:keys [wire log]}]
  (get (bridge/answer-message handlers :no-rate-limits request {handler-name params} {:wire wire :log log}) handler-name))

(defn- check-record-handler [handlers request [handler-name {[table-key id-key] :record :as handler}] foreign-ids options]
  (for [id (get foreign-ids table-key)
        :let [answer (ask handlers request handler-name (assoc (generate-params handler options) id-key id) options)]
        :when (not= {:error "Not found."} answer)]
    {:handler handler-name :asked-for id :answer answer}))

(defn- check-scope-handler [handlers request [handler-name {[kind param] :scope :as handler}] foreign-ids {:keys [scope-kinds] :as options}]
  (for [id (get foreign-ids (get-in scope-kinds [kind :table]))
        :let [answer (ask handlers request handler-name (assoc (generate-params handler options) param id) options)]
        :when (not= {:error "Not found."} answer)]
    {:handler handler-name :asked-in [kind id] :answer answer}))

(defn- check-list-handler [handlers request [handler-name handler] foreign-ids options]
  (let [answer (ask handlers request handler-name (generate-params handler options) options)
        found  (filter (find-texts-in answer) (map str (mapcat val foreign-ids)))]
    (when (seq found)
      [{:handler handler-name :gave-back (vec found)}])))

(defn find-leaks
  "Every leak of every handler: [{:handler … :asked-for id :answer …}],
   [{:handler … :asked-in [kind id] :answer …}] or [{:handler … :gave-back
   [ids]}]. Empty means none."
  [handlers request foreign-ids options]
  (vec (mapcat (fn [[_ handler :as entry]]
                 (cond (:record handler)          (check-record-handler handlers request entry foreign-ids options)
                       (vector? (:scope handler)) (check-scope-handler handlers request entry foreign-ids options)
                       :else                      (check-list-handler handlers request entry foreign-ids options)))
               handlers)))
