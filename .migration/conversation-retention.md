#### 🔴 Choose how long conversations with Hephaestus stay

Conversations with Hephaestus now have a retention limit of 400 days. Each message, answer, digest and summary is erased in the two weeks before it is 400 days old, as a deleted message is. What Hephaestus read during an answer is erased with the workspace's Slack retention, 30 days by default, because it can copy messages from monitored Slack channels.

The first weekly runs after the upgrade erase every entry that is older, and the erasure cannot be undone.

To keep conversations longer:

1. Before you upgrade, set the Helm value `config.conversationRetention`, for example `P730D`. The value is at least `P15D`.
2. Upgrade.
