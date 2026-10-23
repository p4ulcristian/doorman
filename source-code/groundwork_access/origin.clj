(ns groundwork-access.origin
  "A request that changes something (not a GET) or opens a websocket must
   come from the app's own pages: its Origin header is the app's address.
   Otherwise another site could make a signed-in browser send it.")

(defn- is-websocket-request?
  "Whether the request asks to become a websocket (Upgrade: websocket), told
   by its header, as every Ring server passes it."
  [request]
  (some-> (get-in request [:headers "upgrade"]) .toLowerCase (.contains "websocket")))

(defn- does-request-need-origin-check? [request]
  (or (not= :get (:request-method request)) (boolean (is-websocket-request? request))))

(defn does-request-come-from-allowed-origin?
  "Whether the request is a plain GET, or names the allowed origin."
  [request allowed-origin]
  (or (not (does-request-need-origin-check? request))
      (= allowed-origin (get-in request [:headers "origin"]))))
