---
"hephaestus": minor
---

The bundled PostgreSQL now logs lock waits longer than 1 second, temporary files of 10 MB or more, and autovacuum runs of 1 second or more. Its log lines no longer quote SQL statements, also on errors, because a statement can hold values from a person's work. To find slow statements, use the statement statistics. You can override each setting in `postgresql.parameters` or in the Compose `command`.
