---
"hephaestus": minor
---

Feedback is erased 400 days after delivery, or after it was written if never delivered. A practice result is erased 400 days after it was recorded, once no kept feedback cites it. **Operators:** existing installations get the 400-day default on upgrade, so the first daily run after the upgrade irreversibly erases every older feedback and result. To keep them longer, set the Helm value `config.feedbackRetention` (environment variable `HEPHAESTUS_FEEDBACK_RETENTION`), for example `P730D`, before you upgrade. This value also sets the conversation retention and replaces `config.conversationRetention`, which the chart now rejects. The server refuses to start while the old `HEPHAESTUS_MENTOR_CONVERSATION_RETENTION` is set.
