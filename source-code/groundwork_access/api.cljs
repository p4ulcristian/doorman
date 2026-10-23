(ns groundwork-access.api
  "groundwork-access in the browser: the shape of the answer to \"what may I do?\",
   the server's make-my-actions-handler's, and groundwork-access's reasons, for the
   app's shapes by name. Everything else of groundwork-access's is the server's
   (this namespace's .clj half).

     (def shapes-by-name
       (access/put-refusals-into-shapes
        {:groundwork-access/my-actions {:params-shape [:map {:closed true}]
                              :answer-shape access/my-actions-answer-shape
                              :refusals     {}}
         :orders/ship        {… :refusals {:order/already-shipped …}}}))"
  (:require
   [groundwork-access.shapes :as shapes]))

(def refusals
  "groundwork-access's own reasons, each with its data's shape (all nil), the same
   as on the server: :groundwork-access/signed-out, :groundwork-access/not-found,
   :groundwork-access/not-allowed."
  shapes/refusals)

(defn put-refusals-into-shapes
  "The browser's shapes by name (bridge's), each name's :refusals with
   groundwork-access's reasons added, as put-access-into-handlers adds them on the
   server, so the app doesn't write them. A name that declares one of them
   itself throws, :groundwork-access/bad-shapes."
  [shapes-by-name]
  (shapes/put-refusals-into-shapes shapes-by-name))

(def my-actions-answer-shape
  "The answer of \"what may I do?\": [{:scope-kind :scope-id :actions} …],
   an entry of nil kind and id for the actions open in every scope; the
   same shape as on the server."
  shapes/my-actions-answer-shape)
