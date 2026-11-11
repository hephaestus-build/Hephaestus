#### 🔴 Use the canonical Slack workspace callback

**Who is affected:** Operators with Slack workspace connections enabled.

**Action:** Register the configured API origin and public API prefix followed by `/oauth/callback/slack` as a Redirect URL on the Slack app. For a standard chart installation, this is `https://<host>/api/oauth/callback/slack`. Do not use a workspace subdomain.

Remove `config.slack.redirectUri` from every values file. Remove `HEPHAESTUS_INTEGRATION_SLACK_REDIRECT_URI` and `hephaestus.integration.slack.redirect-uri` from application configuration. The chart refuses the retired key. The application derives the callback and ignores a leftover `HEPHAESTUS_INTEGRATION_SLACK_REDIRECT_URI`.

Authorization and token exchange send the same callback address. If the registered address already matches it, no Slack app change is required. The Slack sign-in callback and interactivity Request URL keep their existing paths.
