---
"hephaestus": minor
---

GitLab and Outline webhook registration now uses the configured API origin, which is the chart host. Receiver paths stay outside the `/api` prefix.

**Operators:** Remove `WEBHOOK_EXTERNAL_URL` and `hephaestus.webhook.external-url`. If the previous full receiver base differs, including its path prefix, record and remove this installation's old GitLab group hooks during the stopped upgrade. After upgrading, run GitLab workspace synchronization. Outline collection synchronization replaces a subscription at a different address automatically.

The production server requires `hephaestus.auth.issuer` to be a root HTTPS origin. Keep an API prefix in `hephaestus.auth.api-base-path`. Changing the issuer rejects existing access tokens, so users must sign in again. Follow the complete migration instructions before upgrading.
