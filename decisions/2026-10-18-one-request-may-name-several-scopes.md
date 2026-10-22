# One request may name several scopes

**Decision:** an endpoint may say its input names several scopes (a cart that holds a shop list). Groundwork checks the action in each of them, opens the wall for exactly those, and runs the endpoint in one transaction: everything happens or nothing does. A scope the person may not act in answers "not found", like a single one.

**Why:** some actions are one decision across several places: a customer pays one cart that becomes an order in each shop. Split into one request per scope, half could succeed; run as the system, nothing is checked.

**The price:** the check and the wall take a list where they took one scope.

**Instead of:** one request per scope; running such endpoints as the system.
