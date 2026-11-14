---
"hephaestus": minor
---

Slack channel monitoring starts only after Slack confirms the consent announcement. Channels show **Activating** while delivery is pending and **Activation failed** when confirmation fails. Check the Slack connection and bot membership, then use **Retry activation**. A retry checks for an earlier announcement before it posts another one. Messages sent while monitoring was off stay unread.

Refusal and account-link replies in Slack direct messages now use the same durable delivery tracking as answers. Temporary delivery failures can be retried without sending a second reply after an uncertain result.

**Operators:** Stop every server replica before you migrate and start this release. The previous release cannot read the new Slack action kinds and channel states. Follow the stopped-upgrade procedure.
