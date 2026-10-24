(ns groundwork-access.test-answers
  "Answers as the tests make and compare them. groundwork-access requires no
   message runner: its handlers' steps are groundwork-steps's, run by
   whichever runner the app plugs in (groundwork-bridge, in groundwork).
   answer-message is these tests' own runner, which answers a message as
   that one does over http: each name's params read by its :params-shape,
   its :run-steps-in around its steps, the answer or the refusal written
   out as JSON data by its shapes, a refusal with the message's request id,
   a step that throws or no answer :groundwork-steps/failed. No rate
   limits, no metrics: those are the runner's own."
  (:require
   [groundwork-logs.api :as logs]
   [groundwork-shaped-json.api :as shaped-json]
   [groundwork-steps.api :as steps]
   [jsonista.core :as json]))

(defn refusal
  "A refusal of groundwork-access's (or groundwork-steps's), as
   answer-message gives it, its request id taken out."
  [reason]
  {:groundwork-steps/refused reason :groundwork-steps/data nil})

(defn without-request-id [answer]
  (if (map? answer) (dissoc answer :groundwork-steps/request-id) answer))

(defn read-without-request-ids
  "An answer's JSON text as JSON data, each name's request id taken out."
  [text]
  (update-vals (json/read-value text) #(if (map? %) (dissoc % "groundwork-steps/request-id") %)))

(defn refusal-json
  "A name's refusal as read-without-request-ids reads it."
  [message-name reason]
  {message-name {"groundwork-steps/refused" (subs (str reason) 1) "groundwork-steps/data" nil}})

(defn- turn-out
  "Data as JSON data by its shape (nil: as it is), or ::unfit."
  [shaper shape data]
  (if (nil? shape)
    data
    (let [{:keys [json-data errors]} (shaped-json/turn-shaped-data-into-json-data-or-errors shaper shape data)]
      (if errors ::unfit json-data))))

(defn- turn-answer-out [shaper {:keys [answer-shape refusals]} answer]
  (if (steps/is-error-answer? answer)
    (let [{reason :groundwork-steps/refused data :groundwork-steps/data} answer
          shape (get refusals reason)]
      (cond (not (contains? refusals reason))  ::unfit
            (and (nil? shape) (some? data))    ::unfit
            (nil? shape)                       (steps/make-refusal reason nil)
            :else                              (let [out (turn-out shaper shape data)]
                                                 (if (= ::unfit out) ::unfit (steps/make-refusal reason out)))))
    (turn-out shaper answer-shape answer)))

(defn- answer-one [shaper log request handlers [message-name params]]
  (let [handler (get handlers message-name)
        failed  (steps/make-refusal :groundwork-steps/failed nil)]
    (if-not handler
      failed
      (let [{:keys [params-shape run-steps-in]} handler
            {:keys [shaped-data errors]} (if params-shape
                                           (shaped-json/turn-json-data-into-shaped-data-or-errors shaper params-shape params)
                                           {:shaped-data params})]
        (if errors
          (steps/make-refusal :groundwork-steps/invalid {:errors errors})
          (try
            (let [run     #(steps/run-steps-until-answered (:steps handler) %)
                  context {:groundwork-steps/request      request
                           :groundwork-steps/message-name message-name
                           :groundwork-steps/message-data shaped-data
                           :groundwork-steps/log          log
                           :groundwork-steps/shaper       shaper}
                  context (if run-steps-in (run-steps-in run context) (run context))
                  out     (when (steps/does-context-have-answer? context)
                            (turn-answer-out shaper handler (:groundwork-steps/answer context)))]
              (if (or (nil? out) (= ::unfit out)) failed out))
            (catch Exception e
              (log :error :test/handler-failed {:message-name (str message-name) :error e})
              failed)))))))

(defn answer-message
  "The answer to a message, {name answer}, as a runner answers it over
   http, by options {:shaper :log}: the app's shaper and log, the
   message's log made from it with a new request id."
  [handlers request message {:keys [shaper log]}]
  (let [request-id (str (random-uuid))
        log        (logs/with-context log {:request-id request-id})]
    (into {} (map (fn [[message-name :as entry]]
                    (let [answer (answer-one shaper log request handlers entry)]
                      [message-name (cond-> answer
                                      (steps/is-error-answer? answer) (assoc :groundwork-steps/request-id request-id))])))
          message)))

(defn make-answerer
  "find-leaks's :answer-message: (fn [handlers request message]) answering
   by answer-message, with the tests' shaper and log."
  [shaper log]
  (fn [handlers request message]
    (answer-message handlers request message {:shaper shaper :log log})))

(defn answer-json-text
  "A message's JSON text answered as JSON text, as over http."
  [handlers request text {:keys [shaper] :as options}]
  (shaped-json/turn-data-into-json-text shaper (answer-message handlers request (shaped-json/turn-json-text-into-data shaper text) options)))
