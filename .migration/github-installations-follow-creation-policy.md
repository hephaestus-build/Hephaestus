#### 🔴 Decide whether GitHub App installations need an instance admin's approval

Before 1.0, each new GitHub App installation created its own workspace, whatever the workspace creation policy.
Now an installation follows the policy ([Who creates the workspace of an installation](https://docs.hephaestus.build/admin/github-integration#who-creates-the-workspace-of-an-installation)).
Under the default `ADMIN_ONLY`, a new installation waits in **Instance administration → GitHub installations** until an instance admin adopts it.
Existing workspaces and their installations are unaffected.

**Operators:** to keep App installations creating workspaces without approval, set `config.workspaceCreationPolicy: SELF_SERVICE`.
It also opens `POST /workspaces` to every signed-in user.
Otherwise, no action is necessary.
