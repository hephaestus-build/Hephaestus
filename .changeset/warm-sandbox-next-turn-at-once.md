---
"hephaestus": patch
---

Hephaestus starts to answer a follow-up question up to two seconds sooner. A conversation's sandbox now waits on the server for its next question, and the server hands the question over as soon as it is queued. Before, the sandbox asked again every two seconds. At most half of `sandbox.gateway.maxConnections` wait at once on each server.
