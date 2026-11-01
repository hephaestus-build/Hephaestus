#### 🔴 Delete GitLab hooks on the shared endpoint

This release removes `POST /webhooks/gitlab`. A hook that still posts there receives an error, and Hephaestus does not record its events.

1. Before you upgrade, open **Settings → Webhooks** of each GitLab group and project that a workspace connects.
2. Delete each hook whose URL ends in `/webhooks/gitlab`.
3. Keep each hook whose URL contains `/webhooks/gitlab/connections/`. The workspace's GitLab connection registered it.
4. Keep the previous release running until it has processed the events that the deleted hooks already delivered. Its GitLab consumers then have no pending messages.
5. Upgrade. If a connected group has no connection hook, Hephaestus registers one at its next webhook health check, within six hours.

Events that GitLab sends to the shared endpoint, or before the connection hook exists, are lost. So are shared-endpoint events that the previous release did not process before the upgrade: the new release does not read them. This includes push events. The next periodic reconcile recovers the effect of lost events on issues, merge requests and notes.

`WEBHOOK_SECRET` stays required. It now verifies GitHub webhooks only. GitLab hooks use the token that `WEBHOOK_ROUTING_SECRET` signs.
