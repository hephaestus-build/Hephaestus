---
"hephaestus": minor
---

Database migrations now run once per release, before the new application starts, so an upgrade is one rollout even with several servers. The bundled Compose stacks run a one-shot `database-migration` service, and the server, worker and webhook receiver wait for it instead of migrating at start. A migration that was killed no longer leaves a lock that blocks the next one. **Operators:** if you start the application image outside the bundled Compose stacks, run it once per release with `SPRING_PROFILES_ACTIVE=prod,migrate` and the database settings before you start the new roles.
