---
"hephaestus": minor
---

Require PostgreSQL 18 and initialize new databases from a compact v0.77.4 baseline instead of replaying the full migration history.

**Operators:** If you run PostgreSQL 17, complete the documented PostgreSQL 18 upgrade using v0.77.4 before installing this release. All existing installations must back up their database, verify the v0.77.4 cut-point, and synchronize the baseline before starting this release. Fresh installations initialize automatically.
