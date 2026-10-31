#### 🔴 Delete GitLab hooks on the shared endpoint

This release removes `POST /webhooks/gitlab`. A hook that still posts there receives an error, and Hephaestus does not record its events.

1. Before you upgrade, open **Settings → Webhooks** of each GitLab group and project that a workspace connects.
2. Delete each hook whose URL ends in `/webhooks/gitlab`.
3. Keep each hook whose URL contains `/webhooks/gitlab/connections/`. The workspace's GitLab connection registered it.
4. Upgrade. If a connected group has no connection hook, Hephaestus registers one again at its next webhook health check, every six hours, or at the next periodic reconcile.

Events that GitLab sends before the connection hook exists are lost. The next periodic reconcile recovers their effect on issues, merge requests and notes.

`WEBHOOK_SECRET` stays required. It now verifies GitHub webhooks only. GitLab hooks use the token that `WEBHOOK_ROUTING_SECRET` signs.
