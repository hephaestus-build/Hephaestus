---
"hephaestus": minor
---

Integration connections in progress are now signed with their own secret, `HEPHAESTUS_INTEGRATION_OAUTH_STATE_SECRET`, instead of a key derived from the webhook secret. Configuration readiness reports a missing or reused value. The server no longer reads `AGENT_MAX_RETRIES` or `HEPHAESTUS_MENTOR_CONVERSATION_RETENTION`. The Slack setup guide now names the **Activating** and **Activation failed** states of a channel.

**Operators:** Before the upgrade, run `hephaestus-keys.sh` from the target release to add `HEPHAESTUS_INTEGRATION_OAUTH_STATE_SECRET` to the release Secret. Replace `AGENT_MAX_RETRIES` with `HEPHAESTUS_AGENT_CAPACITY_REVIEW_RETRIES` and `HEPHAESTUS_MENTOR_CONVERSATION_RETENTION` with `HEPHAESTUS_FEEDBACK_RETENTION` if you set them.
