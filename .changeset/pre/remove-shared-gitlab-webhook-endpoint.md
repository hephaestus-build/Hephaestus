---
"hephaestus": minor
---

GitLab events reach Hephaestus only through the hook that each workspace's GitLab connection registers. The shared `POST /webhooks/gitlab` endpoint is removed, and `WEBHOOK_SECRET` now verifies GitHub webhooks only.

**Operators:** Before you upgrade, delete each GitLab group or project hook whose URL ends in `/webhooks/gitlab`. Keep the hooks under `/webhooks/gitlab/connections/`. Hephaestus registers a missing connection hook again on its own.
