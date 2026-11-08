---
"hephaestus": minor
---

One-host installers refuse release changes and failed or pending releases instead of starting an unsafe migration.
**Operators:** Use the stopped schema-upgrade procedure for a release change: stop every role, verify a matching restore point, migrate, then start the new roles.
Restore that backup before returning to the old release after a migration failure or refusal.
