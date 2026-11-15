---
"hephaestus": patch
---

A Slack button press that reaches Hephaestus a second time, for example an opt-out, is acknowledged and not applied again. When `hephaestus.integration.oauth-state.secret` is not set, OAuth state tokens use their own key, derived from the webhook secret. An integration connection that a person started before the upgrade must be started again.
