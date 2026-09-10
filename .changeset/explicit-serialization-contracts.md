---
"hephaestus": patch
---

Server builds no longer emit avoidable serialization warnings. Request-local and managed service state remain in-process rather than being silently discarded for serialization.
