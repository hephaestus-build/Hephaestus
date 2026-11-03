---
"hephaestus": minor
---

Conversations with Hephaestus now have a retention limit. Each message, answer and digest is erased in the two weeks before it is 400 days old, as a deleted message is. What Hephaestus read during an answer, which can copy messages from monitored Slack channels, is erased with the workspace's Slack retention (30 days by default) and when Slack is disconnected. **Operators:** the first weekly runs after the upgrade erase every older entry, and every such tool result older than the Slack retention. To keep conversations longer, set the Helm value `config.conversationRetention` (environment variable `HEPHAESTUS_MENTOR_CONVERSATION_RETENTION`), for example `P730D`, before you upgrade.
