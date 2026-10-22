(ns doorman.leaks
  "The leak test: every handler, asked by a person of one scope with the ids
   of another scope's rows, must not give any of them back.

   A handler with a :record is asked for each foreign row: it must answer
   \"Not found.\". A handler without one (a list) is asked once: its answer
   must hold none of the foreign ids. The other params come from the
   handler's :params-shape, by malli's generator, with the options' :seed
   (the same params every run) and :size (how big the made-up values get)."
  (:require
   [mailroom.api :as mailroom]
   [malli.generator :as generator]))

(defn- generate-params [{:keys [params-shape]} {:keys [seed size]}]
  (generator/generate params-shape {:seed seed :size size}))

(defn- find-texts-in
  "Every UUID or text in an answer, as text: answers are JSON data, where
   an id is text."
  [data]
  (set (map str (filter #(or (uuid? %) (string? %)) (tree-seq coll? seq data)))))

(defn- ask [handlers request handler-name params]
  (get (mailroom/answer-message handlers request {handler-name params}) handler-name))

(defn- check-record-handler [handlers request [handler-name {[table-key id-key] :record :as handler}] foreign-ids options]
  (for [id (get foreign-ids table-key)
        :let [answer (ask handlers request handler-name (assoc (generate-params handler options) id-key id))]
        :when (not= {:error "Not found."} answer)]
    {:handler handler-name :asked-for id :answer answer}))

(defn- check-list-handler [handlers request [handler-name handler] foreign-ids options]
  (let [answer (ask handlers request handler-name (generate-params handler options))
        found  (filter (find-texts-in answer) (map str (mapcat val foreign-ids)))]
    (when (seq found)
      [{:handler handler-name :gave-back (vec found)}])))

(defn find-leaks
  "Every leak of every handler: [{:handler … :asked-for id :answer …}] or
   [{:handler … :gave-back [ids]}]. Empty means none."
  [handlers request foreign-ids options]
  (vec (mapcat (fn [[_ handler :as entry]]
                 (if (:record handler)
                   (check-record-handler handlers request entry foreign-ids options)
                   (check-list-handler handlers request entry foreign-ids options)))
               handlers)))
