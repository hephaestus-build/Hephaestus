---
"hephaestus": patch
---

The proxy now limits the model calls that one practice review or one Heph conversation has in flight at once, to the most that its own runner opens: 2 for a Heph turn, and 12 for a review at the default practice concurrency of 3. A call past the limit is refused before it reaches your provider, so no single review takes more than its share of your provider's capacity. `sandbox.gateway.calls_in_flight.refused` counts these refusals. No action is needed.
