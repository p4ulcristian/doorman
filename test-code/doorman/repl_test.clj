(ns doorman.repl-test
  "doorman.repl, a REPL's only: a step asking as a named asker, outside
   doorman.api."
  (:require
   [clojure.test :refer [deftest is testing]]
   [doorman.repl :as repl]
   [doorman.test-log :as test-log :refer [log]]
   [bridge.api :as bridge]))

(def ^:private anna #uuid "00000000-0000-4000-8000-000000000002")

(deftest the-step-asks-as-the-asker-given
  (let [context (bridge/run-steps-until-answered [(repl/make-step-asking-as {:person-id anna :visitor-id nil :session-flags #{:impersonated}})]
                                                 {:bridge/request nil :bridge/log log})]
    (testing "in the context and the log, as the find-session step puts them"
      (is (= [anna nil #{:impersonated}] ((juxt :doorman/person-id :doorman/visitor-id :doorman/session-flags) context)))
      (reset! test-log/lines [])
      ((:bridge/log context) :info :test/later-step {})
      (is (= [{"person-id" (str anna)}] (mapv #(get % "context") @test-log/lines)))))
  (testing "every key of the asker is required"
    (is (= :doorman/bad-asker (:type (ex-data (try (repl/make-step-asking-as {:person-id anna})
                                                   (catch clojure.lang.ExceptionInfo e e)))))))
  (testing "it is not doorman's api"
    (is (nil? (ns-resolve 'doorman.api 'make-step-asking-as)))))
