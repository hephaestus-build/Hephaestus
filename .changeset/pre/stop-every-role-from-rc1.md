---
"hephaestus": minor
---

An upgrade from 1.0.0-rc.1 needs every role stopped before the migration, because 1.0.0-rc.1 cannot run on the new schema.

**Operators:** back up, stop every role, run the migration of this release, then start every role from it. To roll back, restore the backup: there is no in-place downgrade. The migration guide gives the steps.
