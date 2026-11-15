---
"hephaestus": minor
---

Stopping the servers for a stopped upgrade, database maintenance, a restore, incident containment or decommissioning no longer restarts PostgreSQL. The new chart value `server.stopped: true` stops the servers and keeps `server.replicas`, which also sizes PostgreSQL's connections. Before, setting `server.replicas=0` to stop the servers changed PostgreSQL's `max_connections`, so PostgreSQL restarted when the servers stopped and again when they started.

**Operators:** stop the servers with `server.stopped=true` instead of `server.replicas=0`, in the stopped upgrade, in database maintenance, in a restore, when you contain an incident and when you decommission an instance. For the next upgrade from 1.0.0-rc.8 or earlier, whose chart has no `server.stopped`, follow _Stopped schema upgrades_: phase 1 still sets `server.replicas=0`, and every phase pins `postgresql.parameters.max_connections` to the larger of its current value and the value that the new chart derives. The pin restarts PostgreSQL at most once, in phase 1. Each release also builds its PostgreSQL image again with current security fixes, so an upgrade usually restarts PostgreSQL once when the new chart applies: in phase 2 of a stopped upgrade, while every role is stopped, or during a rolling upgrade.
