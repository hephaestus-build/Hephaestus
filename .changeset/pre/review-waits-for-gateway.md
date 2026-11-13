---
"hephaestus": patch
---

Reviews wait for a disconnected sandbox gateway to return within their attempt lease and work deadline. A gateway connection failure does not use a model retry. Repeated disconnected responses stop after bounded replays and use the existing infrastructure retry, so an outage cannot keep starting billed calls. Provider failures keep their existing retry behavior.

Deploy the matching server and agent image; their runtime image contract advances together.
