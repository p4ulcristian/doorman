(ns doorman.api
  "doorman in the browser: the shape of the answer to \"what may I do?\",
   the server's make-my-actions-handler's, for the app's shapes by name.
   Everything else of doorman's is the server's (this namespace's .clj
   half).

     (def shapes-by-name
       {:doorman/my-actions {:params-shape [:map {:closed true}]
                             :answer-shape doorman/my-actions-answer-shape}})"
  (:require
   [doorman.shapes :as shapes]))

(def my-actions-answer-shape
  "The answer of \"what may I do?\": [{:scope-kind :scope-id :actions} …],
   an entry of nil kind and id for the actions open in every scope; the
   same shape as on the server."
  shapes/my-actions-answer-shape)
