---
"hephaestus": minor
---

A stopped upgrade and database maintenance no longer restart PostgreSQL. The new chart value `server.stopped: true` stops the servers and keeps `server.replicas`, which also sizes PostgreSQL's connections. Before, setting `server.replicas=0` to stop the servers changed PostgreSQL's `max_connections`, so PostgreSQL restarted when the servers stopped and again when they started.

**Operators:** stop the servers with `server.stopped=true` instead of `server.replicas=0`, in the stopped upgrade, in database maintenance and in a restore. For the next upgrade from 1.0.0-rc.8 or earlier, whose chart has no `server.stopped`, follow *Stopped schema upgrades*: phase 1 still sets `server.replicas=0`, and every phase pins `postgresql.parameters.max_connections` to the larger of its current value and the value that the new chart derives. PostgreSQL then restarts at most once, in phase 1.
