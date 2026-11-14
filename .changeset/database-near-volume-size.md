---
"hephaestus": minor
---

The new `HephaestusDatabaseNearVolumeSize` alert warns when the database holds more than 80 % of the bytes of the bundled PostgreSQL volume. It works with the bundled monitoring stack, because it needs no kubelet metrics. It is an approximation: the WAL, temporary files and the filesystem also take space, so the volume can be fuller than the alert says. The server reads the database size every five minutes as `database_size_bytes`, and the chart gives it the volume size from `postgresql.persistence.size`. With an external database, the alert fires only when the server cannot read the database size.
