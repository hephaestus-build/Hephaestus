---
"hephaestus": minor
---

The guide [Upgrade to 1.0](https://docs.hephaestus.build/admin/upgrade-to-1-0) now gives each step from any release candidate to 1.0.0, and what the migration refuses and why.

**Operators:** stop every role first, then back up PostgreSQL and JetStream, then migrate. With every writer stopped, the backup holds every write before the outage. This order replaces "back up, then stop" in the 1.0.0-rc.2 entry **Stop every role before you upgrade from 1.0.0-rc.1**.
