---
"hephaestus": minor
---

GitLab workspace creation is available whenever GitLab is enabled, to the people who may create workspaces. Unused switches and alternative setting names are removed: the GitLab workspace-creation and notification-access feature flags, the `webhook-server` and `worker-node` profile names, the NATS switch (the runtime role decides it), the unused GitHub metadata token, the Slack login-provider aliases, and two unused integration switches. Stored GitLab connections keep working.

**Operators:** before you upgrade, remove the settings that this release no longer reads, and rename the Slack login settings. The migration guide lists each one.
