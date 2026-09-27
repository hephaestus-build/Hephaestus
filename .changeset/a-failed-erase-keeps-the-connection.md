---
"hephaestus": patch
---

Disconnecting a GitHub, GitLab, Slack, or Outline connection no longer marks it disconnected when erasing its mirrored data fails. The connection stays connected with its credentials, so the disconnect can be retried until the data is gone. A provider that cannot be reached still does not block the disconnect. Disconnecting a connection whose integration is disabled on the instance is now refused instead of leaving its data behind.
