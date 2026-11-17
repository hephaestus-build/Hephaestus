---
"hephaestus": minor
---

Erasure recovery after a database restore now also erases again each Slack channel that an admin revoked or that Slack deleted, and the old words of Slack messages that their authors edited or deleted in Slack, after the recovery point. Before, a restore brought them back. A deleted message is deleted again. An edited message has no words until the next history read of its channel stores the words that Slack shows then.

**Operators:** A recovery manifest now uses version 4 and lists `slackChannelErasures` and `slackMessageChanges` beside the other erasures; a manifest of version 3 or earlier stops startup. After each Slack channel erasure, edit or deletion, the server logs a `slack.channel_erased` or `slack.message_changed` event with the IDs to list. See [Reapply erasures after a restore](https://docs.hephaestus.build/admin/erasure-recovery).
