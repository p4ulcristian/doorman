# A websocket is told by its Upgrade header

**Decision:** the Origin check tells a websocket request by its `Upgrade: websocket` header, read from the Ring request's headers, not by http-kit's `:websocket?` key. A GET that asks to become a websocket must come from the allowed origin, as before; any other GET passes. mailroom is renamed bridge with this release, and every `:mailroom/…` key doorman reads or writes is `:bridge/…`.

**Why:** bridge's server is Jetty 12 through rj9a now, and its requests carry no `:websocket?`; read by that key, a websocket from another site would pass the check unseen. The header is what every Ring server passes.

**Instead of:** bridge adding `:websocket?` to every request (an http-kit key kept alive for one reader); a function from `bridge.api` (doorman would need bridge for one header check).
