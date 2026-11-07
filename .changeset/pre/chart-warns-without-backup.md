---
"hephaestus": patch
---

`helm install` and `helm upgrade` now print a warning when the bundled PostgreSQL runs without a backup (`postgresql.backup.enabled: false`). The warning links to the backup instructions. If you back up the PostgreSQL volume yourself, you can ignore it.
