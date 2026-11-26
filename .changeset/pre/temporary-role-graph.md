---
"hephaestus": patch
---

The database migration now also stops when a Hephaestus database login can reach the `TEMPORARY` privilege by switching to another role with `SET ROLE` and inheriting it from there. The earlier check followed each kind of role membership on its own and missed this path. If your database grants such a path, for example through a custom group, the migration names the problem: remove that grant and run the migration again. A default installation needs no action.
