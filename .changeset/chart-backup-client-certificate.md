---
"hephaestus": minor
---

The one-host Kubernetes platform now issues and renews the client certificate that PostgreSQL backup Jobs need. The chart reads it from `postgresql.backup.clientTlsSecret`, separately from the database server certificate. The one-host values select `hephaestus-postgresql-backup-tls`. Configure the off-host repository and backup credentials before you enable backups.
