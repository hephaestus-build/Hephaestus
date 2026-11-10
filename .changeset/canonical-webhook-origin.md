---
"hephaestus": minor
---

GitLab and Outline webhook registration now uses the configured API origin, which is the chart host. Receiver paths stay outside the `/api` prefix.

**Operators:** Remove `WEBHOOK_EXTERNAL_URL` and `hephaestus.webhook.external-url`. If you used a different receiver origin, stop the application roles and remove this installation's old provider registrations. After upgrading, run GitLab workspace and Outline collection synchronization to create registrations at the canonical origin. Follow the complete migration instructions before resuming normal use.
