(ns doorman.api-names-test
  "The public names of doorman.api. Adding or removing one fails here, so
   the api changes on purpose."
  (:require
   [clojure.test :refer [deftest is]]
   [doorman.api]))

(deftest the-api-names
  (is (= '#{;; who is asking
           settings-shape sessions-table sign-in! start-visitor-session! sign-out! sign-out-everywhere! delete-ended-sessions!
           find-session-key-of-request make-find-session-step run-as-asker-of-request!
           tokens-table make-token! list-tokens revoke-token! delete-ended-tokens!
           ;; what may they do
           access-tables check-scope-kinds does-action-only-read? add-scope! add-role! add-membership! remove-membership!
           list-actions-of-person make-sql-conditions install-access-functions!
           put-access-into-handlers put-gates-on-tables find-leaks explain-refusal
           my-actions-answer-shape make-my-actions-handler
           ;; a named system actor
           run-as-system! find-system-leaks}
         (set (keys (ns-publics 'doorman.api))))))
