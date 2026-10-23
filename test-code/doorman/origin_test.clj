(ns doorman.origin-test
  "A request that changes something, or opens a websocket, comes from the
   app's own pages."
  (:require
   [clojure.test :refer [deftest is testing]]
   [doorman.origin :as origin]))

(def ^:private allowed "https://shop.example")

(deftest a-websocket-is-checked-by-its-upgrade-header
  (testing "a plain GET passes from anywhere"
    (is (origin/does-request-come-from-allowed-origin? {:request-method :get :headers {}} allowed)))
  (testing "a websocket upgrade from another site is refused, from the app's own pages taken"
    (let [upgrade {:request-method :get :headers {"upgrade" "websocket" "connection" "Upgrade"}}]
      (is (not (origin/does-request-come-from-allowed-origin? (assoc-in upgrade [:headers "origin"] "https://evil.example") allowed)))
      (is (not (origin/does-request-come-from-allowed-origin? upgrade allowed)))
      (is (origin/does-request-come-from-allowed-origin? (assoc-in upgrade [:headers "origin"] allowed) allowed))))
  (testing "a POST from another site is refused"
    (is (not (origin/does-request-come-from-allowed-origin? {:request-method :post :headers {"origin" "https://evil.example"}} allowed)))))
