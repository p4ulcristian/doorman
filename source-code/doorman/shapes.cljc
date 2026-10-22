(ns doorman.shapes
  "The shapes doorman's messages answer by, the same on both sides: the
   server's handler answers by them, and the browser reads the answer by
   them (the app puts them in its shapes by name, as every message's).")

(def my-actions-answer-shape
  "The answer of make-my-actions-handler's handler, \"what may I do?\":
   every scope the person is in with the actions they may do there, and
   an entry of nil kind and id for the actions open to them in every
   scope. By wire: scope kinds and actions travel as their text and come
   back keywords.

     [{:scope-kind nil   :scope-id nil      :actions [:profile/edit]}
      {:scope-kind :shop :scope-id #uuid \"…\" :actions [:orders/edit :orders/see]}]"
  [:vector [:map {:closed true}
            [:scope-kind [:maybe :keyword]]
            [:scope-id [:maybe :uuid]]
            [:actions [:vector :keyword]]]])
