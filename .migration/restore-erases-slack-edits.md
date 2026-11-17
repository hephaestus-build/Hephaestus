#### 🔴 Write erasure recovery manifests as version 4

- **When:** before the window
- **Action:** Add the Slack messages that their authors edited or deleted in Slack to each erasure recovery manifest, as [Reapply erasures after a restore](https://docs.hephaestus.build/admin/erasure-recovery) describes.

Erasure recovery now erases the old words of Slack messages that their authors edited or deleted in Slack after the recovery point.
A recovery manifest uses version 4 and lists `slackMessageChanges` beside the other erasures. A manifest of version 3 or earlier stops startup.

**Operators:** Keep the `slack.message_changed` log events for as long as your oldest backup. They carry the IDs that the manifest lists, as [Erasure events](https://docs.hephaestus.build/admin/log-records#erasure-events) describes.
