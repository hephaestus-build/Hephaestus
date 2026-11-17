#### 🔴 Add the OAuth state secret and remove two retired settings

- **When:** before the window
- **Action:** Before the upgrade, add `HEPHAESTUS_INTEGRATION_OAUTH_STATE_SECRET` to the release Secret: run `hephaestus-keys.sh` from the target release, or set it to the output of `openssl rand -hex 32`. Replace `AGENT_MAX_RETRIES` and `HEPHAESTUS_MENTOR_CONVERSATION_RETENTION` with their new names.

The server signs the state of each integration connection in progress with `HEPHAESTUS_INTEGRATION_OAUTH_STATE_SECRET`.
It no longer derives that key from `WEBHOOK_SECRET`.
The chart reads it as a required key of the release Secret, so a server pod without it does not start.
Outside the chart, a production server without it refuses to start.
Configuration readiness reports a missing key.
`hephaestus-keys.sh` adds the key to the release Secret and keeps every other value.
A connection that an admin started before the upgrade fails. The admin connects again.

The server no longer reads `AGENT_MAX_RETRIES`. Set `HEPHAESTUS_AGENT_CAPACITY_REVIEW_RETRIES` if you changed the count.

**If you still set `HEPHAESTUS_MENTOR_CONVERSATION_RETENTION` (`hephaestus.mentor.conversation-retention`), move its window to `HEPHAESTUS_FEEDBACK_RETENTION` before the upgrade.**
The server no longer checks for the old setting at startup.
Without the move, it erases conversations irreversibly at the default retention of feedback.

| Where | Before | After |
| --- | --- | --- |
| Secret | `WEBHOOK_SECRET` also signed the state of integration connections | `HEPHAESTUS_INTEGRATION_OAUTH_STATE_SECRET` |
| Server settings | `hephaestus.mentor.conversation-retention`, `HEPHAESTUS_MENTOR_CONVERSATION_RETENTION` | `hephaestus.feedback-retention`, `HEPHAESTUS_FEEDBACK_RETENTION` |
