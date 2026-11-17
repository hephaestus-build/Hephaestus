#### 🔴 Write erasure recovery manifests as version 4

- **When:** before the window
- **Action:** Add the erased Slack channels, and the Slack messages that their authors edited or deleted in Slack, to each erasure recovery manifest, as [Reapply erasures after a restore](https://docs.hephaestus.build/admin/erasure-recovery) describes.

Erasure recovery now erases again each erased Slack channel, and the old words of Slack messages that their authors edited or deleted in Slack after the recovery point.
A recovery manifest uses version 4 and lists `slackChannelErasures` and `slackMessageChanges` beside the other erasures. A manifest of version 3 or earlier stops startup.

Convert any version 3 manifest that you prepared. A server before this release logs no Slack edit or deletion, so a restore to a backup from before the upgrade cannot list them.

**Operators:** Keep the `slack.channel_erased` and `slack.message_changed` log events for as long as your oldest backup. They carry the IDs that the manifest lists, as [Erasure events](https://docs.hephaestus.build/admin/log-records#erasure-events) describes.
