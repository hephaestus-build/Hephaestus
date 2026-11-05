---
"hephaestus": minor
---

The chart can now run postgres_exporter for the release's PostgreSQL: set `postgresql.metrics.enabled`. It shows the statements that take the most database time, by query id and without their text, dead rows by table, and the oldest open transaction. The exporter runs apart from the database, so turning it on or off does not restart PostgreSQL. It reads as `hephaestus_monitor`, a role with `pg_monitor` only. It needs `DATABASE_MONITOR_PASSWORD` in the release Secret, which `hephaestus-keys.sh --metrics` generates. With the bundled PostgreSQL, a Job of each release creates the role. With an external PostgreSQL, the observability page gives the statements to run first. The exporter is off by default.
