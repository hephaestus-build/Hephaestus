---
"hephaestus": patch
---

Reviews wait for a disconnected sandbox gateway to return within their attempt lease and work deadline. A gateway connection failure does not use a model retry. Repeated disconnected responses stop with a no-fault requeue after bounded replays, so an outage cannot keep starting billed calls. Provider failures keep their existing retry behavior.
