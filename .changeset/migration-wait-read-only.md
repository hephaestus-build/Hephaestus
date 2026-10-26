---
"hephaestus": patch
---

A first install no longer fails at random in its database migration. The server, worker and webhook roles waited for the migration by reading Liquibase's history in a way that created the change log table, so on an empty database a role could create it while the migration was creating it, and the migration failed. The roles now only read while they wait.
