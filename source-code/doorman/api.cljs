(ns doorman.api
  "doorman in the browser: the shape of the answer to \"what may I do?\",
   the server's make-my-actions-handler's, and doorman's reasons, for the
   app's shapes by name. Everything else of doorman's is the server's
   (this namespace's .clj half).

     (def shapes-by-name
       (doorman/put-refusals-into-shapes
        {:doorman/my-actions {:params-shape [:map {:closed true}]
                              :answer-shape doorman/my-actions-answer-shape
                              :refusals     {}}
         :orders/ship        {… :refusals {:order/already-shipped …}}}))"
  (:require
   [doorman.shapes :as shapes]))

(def refusals
  "doorman's own reasons, each with its data's shape (all nil), the same
   as on the server: :doorman/signed-out, :doorman/not-found,
   :doorman/not-allowed."
  shapes/refusals)

(defn put-refusals-into-shapes
  "The browser's shapes by name (bridge's), each name's :refusals with
   doorman's reasons added, as put-access-into-handlers adds them on the
   server, so the app doesn't write them. A name that declares one of them
   itself throws, :doorman/bad-shapes."
  [shapes-by-name]
  (let [doubled (for [[message-name {name-refusals :refusals}] shapes-by-name
                      reason (keys shapes/refusals)
                      :when (contains? name-refusals reason)]
                  (str message-name " declares " reason ", doorman's own: put-refusals-into-shapes adds it"))]
    (when (seq doubled)
      (throw (ex-info (str "The shapes have problems: " (vec doubled)) {:type :doorman/bad-shapes :problems (vec doubled)})))
    (update-vals shapes-by-name #(update % :refusals merge shapes/refusals))))

(def my-actions-answer-shape
  "The answer of \"what may I do?\": [{:scope-kind :scope-id :actions} …],
   an entry of nil kind and id for the actions open in every scope; the
   same shape as on the server."
  shapes/my-actions-answer-shape)
