#### 🔴 Drain legacy reviews before removing gateway affinity

Follow [Drain legacy reviews before removing gateway affinity](https://docs.hephaestus.build/admin/upgrade#drain-legacy-reviews-before-removing-gateway-affinity). The drain now also covers `REVIEW` runs. One stopped window serves both this change and the durable composition claim.
Do not start the target servers until no `REVIEW` or `REVIEW_COMPOSE` run has a live lease.
A rolling update is not safe for this first upgrade: an older active attempt has no published preparation checkpoint, so another server cannot continue it. Queued work stays queued.
The upgrade changes no schema. Later rolling upgrades keep every active run and lease.

With `postgresql.bundled=false`, Helm does not check the external database's limit.
Set it to the release's ordinary clients, plus that server's own reserved slots and your operator margin, plus the clients of any other tenant.
Follow [Database connections](https://docs.hephaestus.build/admin/sizing#database-connections).
