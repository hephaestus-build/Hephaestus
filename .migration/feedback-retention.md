#### 🔴 Choose the feedback retention before you upgrade

Feedback and practice results now share the conversation retention: 400 days unless you set another value.
The first daily run after the upgrade, at 03:00 server time, erases every feedback and every result older than that.
The erasure cannot be undone.

1. Decide how long your instance keeps what Hephaestus says about a person. The value is at least `P15D`.
2. On Helm, rename `config.conversationRetention` to `config.feedbackRetention` if you set it. The chart rejects the old name.
   If you did not set it, set `config.feedbackRetention` only to keep data longer than 400 days, for example `P730D`.
3. Outside the chart, the environment variable is `HEPHAESTUS_FEEDBACK_RETENTION`. The server refuses to start while `HEPHAESTUS_MENTOR_CONVERSATION_RETENTION` is still set.
4. Upgrade.

State the period in your privacy notice: [Data retention](https://docs.hephaestus.build/admin/data-retention) lists what it covers.
