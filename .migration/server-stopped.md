#### 🔴 Use server.stopped instead of server.replicas=0

In a stopped upgrade, in database maintenance and in a restore, set `server.stopped=true` where the procedure set `server.replicas=0`, and `server.stopped=false` where it restored the count.
`server.stopped` keeps `server.replicas`, which sizes PostgreSQL's connections, so PostgreSQL keeps running.

For the next upgrade from 1.0.0-rc.8 or earlier, whose chart has no `server.stopped`, follow [Stopped schema upgrades](https://docs.hephaestus.build/admin/upgrade#stopped-schema-upgrades).
Phase 1 then still sets `server.replicas=0`.
Every phase pins `postgresql.parameters.max_connections` to the larger of `SHOW max_connections` from before phase 1 and the value that the new chart derives for your site file.
On a host with pull-based reconciliation, the pin goes in `/etc/hephaestus/chart.yaml`.
