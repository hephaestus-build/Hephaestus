---
"hephaestus": minor
---

Database migrations now run once per release, before the new application starts, so an upgrade is one rollout. The bundled Compose stacks run a one-shot `database-migration` service, and the server and worker wait for it instead of migrating at start. A killed migration run no longer leaves a lock that blocks the next run. **Operators:** if you start the application image outside the bundled Compose stacks, run it once per release with `SPRING_PROFILES_ACTIVE=prod,migrate` and the database settings before you start the new roles.
