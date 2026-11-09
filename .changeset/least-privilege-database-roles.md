---
"hephaestus": minor
---

The server and the worker now connect to PostgreSQL as `hephaestus_app`, a role that only reads and writes rows. Only the migration Job connects as `hephaestus_owner`, the owner of the schema. Neither role is a superuser or creates roles, and row-level security applies to the application role, so a compromised process or an injected query can no longer administer the database server. PostgreSQL receives only the SCRAM verifiers of the database passwords, never the passwords, and not even the verifiers reach the statement statistics or the server log. The setup also works with the administrator of a managed PostgreSQL service. No Hephaestus process logs in as the PostgreSQL administrator.

**Operators:** the release Secret replaces `DATABASE_USERNAME` and `DATABASE_PASSWORD` with `DATABASE_OWNER_PASSWORD`, `DATABASE_APP_PASSWORD` and, for the bundled PostgreSQL, `DATABASE_ADMIN_USERNAME` and `DATABASE_ADMIN_PASSWORD`. With an external PostgreSQL, set up the two roles with the chart's `files/database-roles.sql` first. `helm upgrade` stops while a key is missing or the Secret still holds an old one. See the migration guide.
