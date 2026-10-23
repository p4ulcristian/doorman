(ns doorman.test-answers
  "Answers as the tests compare them: a refusal without its request id,
   which is new for every message."
  (:require
   [jsonista.core :as json]))

(defn refusal
  "A refusal of doorman's (or bridge's), as answer-message gives it, its
   request id taken out."
  [reason]
  {:bridge/refused reason :bridge/data nil})

(defn without-request-id [answer]
  (if (map? answer) (dissoc answer :bridge/request-id) answer))

(defn read-without-request-ids
  "An http answer's JSON text as JSON data, each name's request id taken
   out."
  [text]
  (update-vals (json/read-value text) #(if (map? %) (dissoc % "bridge/request-id") %)))

(defn refusal-json
  "A name's refusal as read-without-request-ids reads it."
  [message-name reason]
  {message-name {"bridge/refused" (subs (str reason) 1) "bridge/data" nil}})
