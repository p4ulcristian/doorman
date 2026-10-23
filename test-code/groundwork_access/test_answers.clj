(ns groundwork-access.test-answers
  "Answers as the tests compare them: a refusal without its request id,
   which is new for every message."
  (:require
   [jsonista.core :as json]))

(defn refusal
  "A refusal of groundwork-access's (or bridge's), as answer-message gives it, its
   request id taken out."
  [reason]
  {:groundwork-bridge/refused reason :groundwork-bridge/data nil})

(defn without-request-id [answer]
  (if (map? answer) (dissoc answer :groundwork-bridge/request-id) answer))

(defn read-without-request-ids
  "An http answer's JSON text as JSON data, each name's request id taken
   out."
  [text]
  (update-vals (json/read-value text) #(if (map? %) (dissoc % "groundwork-bridge/request-id") %)))

(defn refusal-json
  "A name's refusal as read-without-request-ids reads it."
  [message-name reason]
  {message-name {"groundwork-bridge/refused" (subs (str reason) 1) "groundwork-bridge/data" nil}})
