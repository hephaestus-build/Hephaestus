#### 🔴 Prepare for the interfaces that 1.0 removes

This release deprecates settings and interfaces for removal in 1.0.
It does not remove them.
The release note **0.95.0 is the last 0.x release** lists each affected setting, value and endpoint.
Use that list to check your deployment and API callers before 1.0.
It includes `reasoningEffort` and `clearReasoningEffort` on both model-update requests.

Upgrade a Compose install to 0.95.0 before you move it to the chart at the same version.
Keep its PostgreSQL and JetStream backups, encryption keys, values and signed release lock.
Then follow [Upgrade from 0.x to 1.0](https://docs.hephaestus.build/admin/upgrade-to-1-0).
Do not start a 0.x runtime role on the 1.0 schema.

The release note also lists 21 public operations that 1.0 removes.
Update any automation that calls those exact methods and paths.
The immediate scan action and membership removal stay available.
A removed HTTP wrapper does not remove its underlying records.

##### Rename conversation interfaces at the 1.0 upgrade

In 1.0, conversation settings, environment variables, metrics and API paths use `conversation`. Update settings and API callers at the 1.0 upgrade. The old names have no aliases. Update dashboard and alert queries too.

##### Settings and environment variables

| Removed in 1.0 | Replacement |
| --- | --- |
| `hephaestus.mentor.turn.capacity` / `HEPHAESTUS_MENTOR_TURN_CAPACITY` | `hephaestus.conversation.turn.capacity` / `HEPHAESTUS_CONVERSATION_TURN_CAPACITY` |
| `hephaestus.mentor.turn.lease` | `hephaestus.conversation.turn.lease` |
| `hephaestus.mentor.turn.lease-reaper-delay` | `hephaestus.conversation.turn.lease-reaper-delay` |
| `hephaestus.mentor.turn.max-follow-ups` | `hephaestus.conversation.turn.max-follow-ups` |
| `hephaestus.mentor.turn.model-first-byte-timeout` | `hephaestus.conversation.turn.model-first-byte-timeout` |
| `hephaestus.mentor.turn.model-idle-timeout` | `hephaestus.conversation.turn.model-idle-timeout` |
| `hephaestus.mentor.agent.max-prompt-chars` | `hephaestus.conversation.agent.max-prompt-chars` |
| `hephaestus.mentor.slack.projector.enabled` | `hephaestus.conversation.slack.projector.enabled` |
| `hephaestus.auth.rate-limit.mentor-turns.capacity` / `HEPHAESTUS_RATE_LIMIT_MENTOR_TURNS_CAPACITY` | `hephaestus.auth.rate-limit.conversation-turns.capacity` / `HEPHAESTUS_RATE_LIMIT_CONVERSATION_TURNS_CAPACITY` |
| `hephaestus.auth.rate-limit.mentor-turns.period` / `HEPHAESTUS_RATE_LIMIT_MENTOR_TURNS_PERIOD` | `hephaestus.auth.rate-limit.conversation-turns.period` / `HEPHAESTUS_RATE_LIMIT_CONVERSATION_TURNS_PERIOD` |
| `hephaestus.worker.capacity.mentor-max` / `HEPHAESTUS_WORKER_CAPACITY_MENTOR_MAX` | Use the conversation-loop capacity setting named in the 1.0 upgrade guide. |
| `HEPHAESTUS_WORKER_MENTOR_MAX` | Remove this Compose input when you move to the chart. |

The 0.95 chart uses `worker.conversationTurns` for its conversation capacity.

##### Metrics and spans

Replace `mentor.` with `conversation.` in these metric names:

- `mentor.turn.completed`
- `mentor.turn.cost`
- `mentor.turn.duration`
- `mentor.turn.first_token`
- `mentor.turn.started`
- `mentor.turn.retried`
- `mentor.turn.queued`
- `mentor.turn.queue_wait`
- `mentor.turn.lease_reaper.failure`
- `mentor.turn.claim.failure`
- `mentor.live.streams`

Also rename `llm.proxy.usage.mentor.failure` and `llm.proxy.usage.mentor.superseded` to `llm.proxy.usage.conversation.failure` and `llm.proxy.usage.conversation.superseded`. Change `surface=mentor` to `surface=conversation`. Replace `heph.turn.*` span/event names, including `heph.turn.tool_call`, with `conversation.turn.*`.

`mentor.host.ready`, `mentor.host.turns.active` and `heph.host.id` leave with the old host. Use the replacement instrumentation documented for the conversation runtime. Remove queries that use the retired host instrumentation.

##### HTTP paths

All paths below are relative to `/workspaces/{workspaceSlug}`. Keep the existing HTTP verbs.

| Removed in 1.0 | Replacement |
| --- | --- |
| `/mentor/conversation` | `/conversation` |
| `/mentor/conversation/read` | `/conversation/read` |
| `/mentor/entries` | `/conversation/entries` |
| `/mentor/entries/{entryId}` | `/conversation/entries/{entryId}` |
| `/mentor/entries/{entryId}/thread` | `/conversation/entries/{entryId}/thread` |
| `/mentor/entries/{entryId}/vote` | `/conversation/entries/{entryId}/vote` |
| `/mentor/turns` | `/conversation/turns` |
| `/mentor/turns/{turnId}/status` | `/conversation/turns/{turnId}/status` |
| `/mentor/events` (server-sent events) | `/conversation/events` |

The 0.95 web page uses `/w/{workspaceSlug}/conversation`. The redirect from the old web page `/w/{workspaceSlug}/mentor` stays because messages that were sent already use it. The 1.0 HTTP API does not redirect the removed endpoints.

Slack message metadata and button action identifiers `heph_entry`, `heph_notice` and `heph_feedback` keep their names. Existing messages depend on them.

##### API names and values

Update generated or handwritten API clients for these names and values at the 1.0 upgrade.
The `Mentor` API tag becomes `Conversation`.
Operation names that contain `Mentor` use `Conversation` instead, including `getMentorConversation` → `getConversation`.
`MentorConversation` becomes `Conversation`. Other `Mentor*` response and request types become `Conversation*`.

| Removed value | Replacement and use |
| --- | --- |
| `HEPH` as a message kind | `ANSWER` |
| `HEPH` as a feedback location | `APP` |
| `MENTOR` as an agent purpose or usage surface | `CONVERSATION` |
| `MENTOR_TURN` | `CONVERSATION_TURN` |
| `MENTOR_NOTES` | `CONVERSATION_NOTES` |
| `MENTOR_UNAVAILABLE` | `CONVERSATION_UNAVAILABLE` |
