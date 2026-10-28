---
"hephaestus": minor
---

The Kubernetes restore procedure now starts PostgreSQL alone so you can lock down a restored clone before migrations or application roles start. Set `migration.enabled=false` and all application replica counts to zero for this phase. The verified chart contains the lockdown SQL. **Operators:** update custom restore scripts that read `docker/self-host/restore-clone-lockdown.sql` to use `deploy/chart/hephaestus/files/restore-clone-lockdown.sql`, or the `files` directory of the verified release chart. Keep external destinations denied until the isolated restore checks pass.
