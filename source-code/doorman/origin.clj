(ns doorman.origin
  "A request that changes something (not a GET) or opens a websocket must
   come from the app's own pages: its Origin header is the app's address.
   Otherwise another site could make a signed-in browser send it.")

(defn- does-request-need-origin-check? [request]
  (or (not= :get (:request-method request)) (:websocket? request)))

(defn does-request-come-from-allowed-origin?
  "Whether the request is a plain GET, or names the allowed origin."
  [request allowed-origin]
  (or (not (does-request-need-origin-check? request))
      (= allowed-origin (get-in request [:headers "origin"]))))
