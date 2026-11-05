---
"hephaestus": minor
---

The bundled PostgreSQL now logs lock waits longer than 1 second, temporary files of 10 MB or more, and autovacuum runs of 1 second or more.

PostgreSQL error lines no longer quote the SQL statement that failed. The image sets `log_min_error_statement=panic`, because a statement can hold values from a person's work, and the new log lines would quote theirs too. To find slow statements, use the statement statistics. An error line can still quote a value in its `DETAIL`, for example the key of a unique constraint violation. To get the statement back on error lines, set `log_min_error_statement=error` in `postgresql.parameters` or in the Compose `command`.
