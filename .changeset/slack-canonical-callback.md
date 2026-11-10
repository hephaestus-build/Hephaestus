---
"hephaestus": minor
---

Slack workspace connections no longer need a separately configured redirect address. Hephaestus derives the OAuth callback from the API address, and uses the same callback to authorize and to exchange the code.

**Operators:** Remove `config.slack.redirectUri`, `HEPHAESTUS_INTEGRATION_SLACK_REDIRECT_URI` and `hephaestus.integration.slack.redirect-uri`. Register the canonical callback, normally `https://<host>/api/oauth/callback/slack`, on the Slack app before upgrading. The chart refuses the retired key.
