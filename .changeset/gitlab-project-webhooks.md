---
"hephaestus": minor
---

GitLab Community Edition and the GitLab Free tier now deliver changes without a wait for the scheduled sync. These tiers have no group webhooks, so Hephaestus registers one webhook on each monitored project instead. It adds the webhook of a newly monitored project and removes the webhook of a project it no longer monitors at the next sync, replaces a deleted or disabled webhook within 6 hours, and removes every webhook when the GitLab connection is disconnected through the API. When a workspace is removed, its GitLab webhooks stay until you delete them; see [GitLab without group webhooks](https://docs.hephaestus.build/admin/connect-gitlab#gitlab-without-group-webhooks). Before, the group webhook failed, no event arrived, and each sync ran the workspace setup again.

If GitLab refuses the group webhook and every project webhook, the GitLab page of the workspace now shows **New work shows only after a sync**, and workspace admins get an email. Give the token the Owner role on the group, or the Maintainer role on its projects. Hephaestus tries again every 6 hours and clears the notice when a webhook works. A project webhook needs the Maintainer role. It does not send member, subgroup, and project events, so those changes, and new projects, show after the next sync.

**Operators:** Install this change in the stopped window before the 1.0.0 cut. Stop every runtime role, then start only the upgraded roles. Do not run old and new servers together during this change: an old server cannot read a connection that records a refused webhook or a project webhook.
