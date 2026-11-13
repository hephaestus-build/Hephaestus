---
"hephaestus": patch
---

The role setup no longer passes the database passwords on to `psql`: `psql` receives only their SCRAM verifiers, also when you run `files/database-roles.sh` or `files/database-monitor.sh` for an external PostgreSQL.
