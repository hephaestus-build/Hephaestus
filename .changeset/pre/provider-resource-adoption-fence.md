---
"hephaestus": minor
---

Connections cannot reuse an app installation, hook, subscription, or Slack bot identity while an earlier removal can still affect it. Reconnecting GitLab or Outline uses a fresh hook or subscription. Pausing Outline now records cleanup before it stops receiving events, and reconciliation repairs a lost wake or failed removal.

Instance admins can review unresolved removals, including those whose workspace was deleted. They can confirm removal at the provider and release the exact block. Each release records its actor and confirmation. Workspace-only admins can see their connection's pending repairs and cannot release them.

**Operators:** pending OAuth requests must restart after this upgrade. Credential erasure keeps its existing deadlines. Unresolved removals retain pseudonymous provider identifiers for integrity and security until resolution or an audited admin release. Check **Provider removals** after upgrading or changing provider credentials. A timed-out deletion can require manual confirmation even when the provider no longer lists the resource.
