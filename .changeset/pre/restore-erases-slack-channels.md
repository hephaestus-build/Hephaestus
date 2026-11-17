---
"hephaestus": patch
---

Erasure recovery after a database restore now also erases again each Slack channel that an admin revoked or that Slack deleted after the recovery point. Before, a restore brought back its messages and threads. A version 4 recovery manifest also lists these channels as `slackChannelErasures`, and the server logs a `slack.channel_erased` event with the IDs to list. See [Reapply erasures after a restore](https://docs.hephaestus.build/admin/erasure-recovery).
