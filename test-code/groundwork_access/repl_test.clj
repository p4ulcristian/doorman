(ns groundwork-access.repl-test
  "groundwork-access.repl, a REPL's only: a step asking as a named asker, outside
   groundwork-access.api."
  (:require
   [clojure.test :refer [deftest is testing]]
   [groundwork-access.repl :as repl]
   [groundwork-access.test-log :as test-log :refer [log]]
   [groundwork-bridge.api :as bridge]))

(def ^:private anna #uuid "00000000-0000-4000-8000-000000000002")

(deftest the-step-asks-as-the-asker-given
  (let [context (bridge/run-steps-until-answered [(repl/make-step-asking-as {:person-id anna :visitor-id nil :session-flags #{:impersonated}})]
                                                 {:groundwork-bridge/request nil :groundwork-bridge/log log})]
    (testing "in the context and the log, as the find-session step puts them"
      (is (= [anna nil #{:impersonated}] ((juxt :groundwork-access/person-id :groundwork-access/visitor-id :groundwork-access/session-flags) context)))
      (reset! test-log/lines [])
      ((:groundwork-bridge/log context) :info :test/later-step {})
      (is (= [{"person-id" (str anna)}] (mapv #(get % "context") @test-log/lines)))))
  (testing "every key of the asker is required"
    (is (= :groundwork-access/bad-asker (:type (ex-data (try (repl/make-step-asking-as {:person-id anna})
                                                   (catch clojure.lang.ExceptionInfo e e)))))))
  (testing "it is not groundwork-access's api"
    (is (nil? (ns-resolve 'groundwork-access.api 'make-step-asking-as)))))
