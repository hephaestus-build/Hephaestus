#### 🔴 Remove settings that 1.0 no longer reads

This release removes switches and alternative names for settings.
Before you upgrade, make these changes in your Helm values and environment.

| Remove or change | Action |
| --- | --- |
| Helm value `config.gitlab.workspaceCreation` and env `GITLAB_WORKSPACE_CREATION` | Remove them. GitLab workspace creation is available whenever GitLab is enabled (`GITLAB_ENABLED`). The chart refuses the old Helm value. |
| `SLACK_OAUTH_CLIENT_ID`, `SLACK_OAUTH_CLIENT_SECRET` | Set `HEPHAESTUS_INTEGRATION_SLACK_CLIENT_ID` and `HEPHAESTUS_INTEGRATION_SLACK_CLIENT_SECRET` instead. The Slack login provider reads only these names. |
| `GH_AUTH_TOKEN` as `hephaestus.integration.github.meta.auth-token` | Nothing reads that key. `GH_AUTH_TOKEN` still sets `hephaestus.contributors.github.auth-token`. |
| `SPRING_PROFILES_ACTIVE=webhook-server` or `worker-node` | Use `prod,webhook` or `prod,worker`. The chart already does. The old names now activate nothing, so the process would start without the production profile. |
| `hephaestus.integration.github.enabled`, `hephaestus.mentor.slack.projector.enabled` | Remove them. Nothing reads them. |

`WEBHOOK_EXTERNAL_URL`, `HEPHAESTUS_WEBAPP_URL` and `GITLAB_DEFAULT_SERVER_URL` still work.
When they are unset, they take the application URL or the GitLab login URL from the setting itself, not from a second environment variable.

The runtime role now decides whether a process uses NATS: the server and webhook roles do, and the worker role does not.
Remove `NATS_ENABLED` and `HEPHAESTUS_SYNC_NATS_ENABLED`.
The chart now passes the broker address to the webhook role as `NATS_SERVER`, like the server role.
