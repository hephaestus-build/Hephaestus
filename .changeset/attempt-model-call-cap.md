---
"hephaestus": patch
---

The proxy now limits the model calls that one practice review or one Hephaestus conversation has in flight at once, to the most that its own runner opens: 2 for a conversation turn, and 12 for a review at the default practice concurrency of 3. A call past the limit is refused before it reaches your provider, so no single review takes more than its share of your provider's capacity. A refused call asks again and keeps its result: practice scripts now wait out such a refusal instead of leaving a call unrated. `sandbox.gateway.calls_in_flight.refused` counts the refusals. No action is needed.
