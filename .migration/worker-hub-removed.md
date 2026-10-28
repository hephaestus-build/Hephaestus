#### 🔴 Workers no longer connect to the server

Workers and the server now share only the database. The worker hub, its registration token and its metrics are gone.

1. Delete `HEPHAESTUS_WORKER_REGISTRATION_TOKEN` and `HEPHAESTUS_HUB_URL` from your values, Secrets and environment files, and any `hephaestus.worker.hub.token.*`, `hephaestus.worker.control.*` or `hephaestus.worker.heartbeat.*` settings. Hephaestus ignores them.
2. Move alerts and dashboards off `worker.control.*`, `worker.heartbeats.*`, `worker.hub.*`, `worker.token.exchange` and `worker.jwt.verify`. `attempt.token.verify` counts the checks of sandbox credentials instead.

A practice review that still runs when you upgrade has no lease. The database migration counts it as a drain, and the server's lease recovery requeues it within a minute of the server's start, without spending a retry. Recovery needs the server role with `AGENT_ENABLED=true`, as orphan recovery always has.
