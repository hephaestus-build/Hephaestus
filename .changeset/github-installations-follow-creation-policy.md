---
"hephaestus": minor
---

GitHub App installations now follow the workspace creation policy. Under the default `ADMIN_ONLY` policy, a new installation waits in **Instance administration → GitHub installations** until an instance admin adopts it; adopting creates its workspace with that admin as the owner, and the audit log records each adopt and decline. A declined installation stays listed and can still be adopted. When GitHub returns the installer to Hephaestus, a setup page confirms the installation and says what happens next, instead of the home page. The new chart value `config.workspaceCreationPolicy` sets the policy.

**Operators:** Under the default `ADMIN_ONLY` creation policy, a new GitHub App installation by another account now waits for an instance admin to approve it in the instance admin console. Existing workspaces are unaffected. To keep App installations creating workspaces without approval, set `config.workspaceCreationPolicy: SELF_SERVICE` (it also opens `POST /workspaces` to every signed-in user).
