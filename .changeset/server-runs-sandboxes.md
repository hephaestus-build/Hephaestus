---
"hephaestus": minor
---

The server now runs the practice review and conversation sandboxes itself, so an installation has two roles: the server and the webhook receiver. There is no worker Deployment to size, update or watch. The server's API stays ready while its sandbox isolation probe runs or fails; only the sandboxes wait for the probe.

**Operators:** the chart refuses the `worker` values. Move them before you upgrade:

- `worker.enabled` is now `sandbox.enabled`.
- `worker.sandbox.namespace`, `worker.sandbox.reviewMemory`, `worker.sandbox.quota` and `worker.sandbox.templates` are now `sandbox.namespace`, `sandbox.reviewMemory`, `sandbox.quota` and `sandbox.templates`.
- `worker.runtime` is now `sandbox.runtime`, with the same keys.
- `worker.sandboxGateway.maxConnections` is now `sandbox.gateway.maxConnections`.
- `networkPolicy.workerGatewayPorts` is now `networkPolicy.modelGatewayPorts`.
- `worker.replicas`, `worker.rolloutStrategy`, `worker.drainTimeoutSeconds`, `worker.databasePool`, `worker.gitCache` and `worker.resources` are gone. Set `server.rolloutStrategy`, `server.drainTimeoutSeconds`, `server.databasePool`, `server.gitCache` and `server.resources` instead. The server's defaults now hold its sandboxes' reviews: a pool of 48 connections, 3 GiB of memory and a 70 GiB git cache.
- Outside the chart, `HEPHAESTUS_RUNTIME_WORKER_ENABLED` and the `worker` Spring profile are gone. Set `AGENT_ENABLED=true` on the server to run sandboxes.

The server keeps one replica and uses `Recreate`, so two server versions do not process provider events at once. It waits for in-flight review runs for up to 30 minutes, or 10 minutes in the small profile, before it closes the gateway. The Pod grace and Helm timeout cover that window and shutdown.

A server with agent jobs enabled now refuses startup without `HEPHAESTUS_CONVERSATION_LOOP_SECRET`. Outside the chart, replace `HEPHAESTUS_WORKER_DRAIN_TIMEOUT` with `HEPHAESTUS_AGENT_DRAIN_TIMEOUT`.
