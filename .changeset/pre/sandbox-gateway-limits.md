---
"hephaestus": minor
---

The sandbox gateway no longer limits a worker to 200 connections that serve a call at once, and it now refuses a flood before it reads a credential. `SANDBOX_API_SOURCE_REQUESTS_PER_MINUTE` limits each connection peer, `worker.sandboxGateway.maxConnections` limits open connections, and the request bodies in flight stay within a quarter of the worker's heap. The defaults fit normal use. The metric `sandbox_gateway_refused_total` with a `reason` label replaces `sandbox_gateway_throttled_total`, so change any dashboard that reads the old name. Two new alerts watch refusals and the connection limit: load the rule file of the release to get them.
