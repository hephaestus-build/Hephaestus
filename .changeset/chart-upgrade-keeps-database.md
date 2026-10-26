---
"hephaestus": patch
---

An upgrade of the Helm chart no longer restarts the bundled PostgreSQL and NATS when their images did not change. Before, every new release restarted both, so the database and the broker were briefly unavailable on each upgrade.
