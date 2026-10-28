---
"hephaestus": minor
---

0.95.0 is the last 0.x release and the required source for 1.0.
1.0 supports the Helm chart on Kubernetes, including one host with k3s.
The following settings, values and interfaces are deprecated for removal in 1.0.
This notice does not remove them from 0.95.

**Operators:** first upgrade to 0.95 on Compose, then move to the chart at the same version.
Before 1.0, remove the settings below from your deployment and update callers of the affected interfaces.
Stop every old runtime role before the 1.0 migration.
Keep a verified PostgreSQL and JetStream backup, the encryption keys and the signed 0.95 release lock.
An image rollback cannot undo that migration.

- **Compose self-hosting and Docker sandboxes:** the self-host Compose bundle, its host backup timers, the Compose reconciler path and `HEPHAESTUS_STACKS` go.
  Compose remains for local development infrastructure only.
  The `docker` value of `HEPHAESTUS_SANDBOX_RUNTIME` (`hephaestus.sandbox.runtime`) goes. Use the chart's Kubernetes sandbox runtime.
  Remove `SANDBOX_MEMORY_BYTES` (`hephaestus.sandbox.default-resource-limits.memory-bytes`), `DOCKER_GROUP_ID`, `SANDBOX_DOCKER_HOST`, `SANDBOX_DOCKER_TLS_VERIFY`, `SANDBOX_DOCKER_CERT_PATH`, `SANDBOX_DOCKER_OWNER` and `SANDBOX_DOCKER_CONTAINER_RUNTIME`.
  The `SANDBOX_DOCKER_*` properties are `hephaestus.sandbox.docker.host`, `.tls-verify`, `.cert-path`, `.owner` and `.container-runtime`.
  The already rejected names `SANDBOX_TLS_VERIFY`, `SANDBOX_CONTAINER_RUNTIME`, `hephaestus.sandbox.docker-host`, `hephaestus.sandbox.tls-verify`, `hephaestus.sandbox.cert-path`, `hephaestus.sandbox.container-runtime`, `hephaestus.sandbox.app-server-container-id` and `hephaestus.mentor.docker-cli` have no replacement in 1.0.
- **Runtime roles and worker control:** the Spring profile aliases `webhook-server` and `worker-node` go. Use `prod,webhook` and `prod,worker`.
  Remove `NATS_ENABLED` (`hephaestus.sync.nats.enabled`): the runtime role selects whether it uses NATS.
  The worker hub and its `POST /api/workers/exchange` and WebSocket `/api/workers/connect` endpoints go.
  Remove `hephaestus.worker.control.endpoint`, `.registration-token` and `.handshake-timeout`.
  Remove `hephaestus.worker.hub.token.issuer`, `.audience`, `.ttl`, `.registration-token`, `.keys` (entries `kid` and `private-key`) and `.active-kid`.
  This includes `HEPHAESTUS_WORKER_CONTROL_ENDPOINT`, `HEPHAESTUS_WORKER_CONTROL_REGISTRATION_TOKEN`, `HEPHAESTUS_WORKER_CONTROL_HANDSHAKE_TIMEOUT`, `HEPHAESTUS_WORKER_HUB_TOKEN_ISSUER`, `HEPHAESTUS_WORKER_HUB_TOKEN_AUDIENCE`, `HEPHAESTUS_WORKER_HUB_TOKEN_TTL`, `HEPHAESTUS_WORKER_HUB_TOKEN_REGISTRATION_TOKEN`, `HEPHAESTUS_WORKER_HUB_TOKEN_KEYS`, `HEPHAESTUS_WORKER_HUB_TOKEN_ACTIVE_KID` and the Compose input `HEPHAESTUS_WORKER_REGISTRATION_TOKEN`.
  Workers use the database for coordination.
- **Capacity:** `HEPHAESTUS_WORKER_CAPACITY_MENTOR_MAX` (`hephaestus.worker.capacity.mentor-max`) moves to the conversation runtime in 1.0.
  `HEPHAESTUS_WORKER_CAPACITY_REVIEW_MAX` (`hephaestus.worker.capacity.review-max`) moves with review orchestration.
  The Compose aliases `HEPHAESTUS_WORKER_MENTOR_MAX` and `HEPHAESTUS_WORKER_REVIEW_MAX` go with Compose.
  Use the capacity values of the target chart. The 1.0 migration notes name their final replacements.
  The chart already refuses `worker.sandbox.quota.pods`, `worker.sandbox.quota.cpu` and `worker.sandbox.quota.memory` in 0.95.
  Quotas follow `worker.replicas`, `worker.reviews` and `worker.conversationTurns`.
- **Integration switches:** remove `HEPHAESTUS_SYNC_INCREMENTAL_ENABLED` (`hephaestus.integration.github.sync.incremental-sync-enabled`): synchronization is incremental.
  Remove `GITLAB_WORKSPACE_CREATION` and `HEPHAESTUS_FEATURES_FLAGS_GITLAB_WORKSPACE_CREATION` (`hephaestus.features.flags.gitlab-workspace-creation`, chart `config.gitlab.workspaceCreation`): GitLab workspace creation follows `hephaestus.integration.gitlab.enabled`.
  Remove the unused `hephaestus.integration.github.meta.auth-token` setting and its environment forms `GH_AUTH_TOKEN` and `HEPHAESTUS_INTEGRATION_GITHUB_META_AUTH_TOKEN`.
  Contributor lookup uses `hephaestus.contributors.github.auth-token`.
- **GitLab hooks:** the shared `POST /webhooks/gitlab` endpoint goes.
  Re-register each operator-created hook through its workspace connection, then remove the shared hook.
  GitHub, Slack and Outline hooks keep their connection-specific registration procedures.
- **Model requests:** `reasoningEffort` on model creation, update and model responses, and `clearReasoningEffort` on model updates, go.
  This includes the instance model requests at `/admin/llm/models/{id}` and the workspace model requests at `/workspaces/{workspaceSlug}/llm/models/{id}`.
  It also includes model creation at `POST /admin/llm/connections/{connectionId}/models` and `POST /workspaces/{workspaceSlug}/llm/connections/{connectionId}/models`.
  Set reasoning effort on each model assignment instead. One model can then have different effort for each purpose.
- **Other API fields and feature values:** `ownerUserId` on workspace creation and the unused `NOTIFICATION_ACCESS` feature value go.
  The authenticated creator owns the workspace. Remove the ignored owner field and callers' checks for that feature value.
- **Earlier UI addresses:** `/landing`, `/w/:slug/user/:username`, the earlier `practice-groups/:groupSlug` person pages, and the old administration review URLs go.
  Replace bookmarks with links copied from the current UI.
  The old review filter `range=1y` goes. Use `range=all`.
  Links in feedback already posted to reviewed work remain supported.

The [upgrade guide](https://docs.hephaestus.build/admin/upgrade-to-1-0) and the 1.0 migration notes give the stopped upgrade procedure.
0.95 retains the older database baseline path. 1.0 accepts an existing database only after it has run 0.95.0.

The following public operations are also deprecated for removal in 1.0.
Update automation that calls them before the upgrade.
The current UI uses the retained list, review-detail and trace operations instead.
These removals do not delete stored teams, membership, reviews, evidence, usage or provider records.
The immediate scan action and the membership-removal action remain available.

| Operation | Request, relative to the API base URL |
| --- | --- |
| `renameSlug` | `PATCH /workspaces/{workspaceSlug}/slug` |
| `getTeamSettings` | `GET /workspaces/{workspaceSlug}/teams/{teamId}/settings` |
| `updateTeamSettings` | `PATCH /workspaces/{workspaceSlug}/teams/{teamId}/settings` |
| `getLabelFilters` | `GET /workspaces/{workspaceSlug}/teams/{teamId}/settings/label-filters` |
| `addLabelFilter` | `POST /workspaces/{workspaceSlug}/teams/{teamId}/settings/label-filters/{labelId}` |
| `removeLabelFilter` | `DELETE /workspaces/{workspaceSlug}/teams/{teamId}/settings/label-filters/{labelId}` |
| `getRepositorySettings` | `GET /workspaces/{workspaceSlug}/teams/{teamId}/settings/repositories/{repositoryId}` |
| `updateRepositorySettings` | `PATCH /workspaces/{workspaceSlug}/teams/{teamId}/settings/repositories/{repositoryId}` |
| `getMembership` | `GET /workspaces/{workspaceSlug}/members/{accountId}` |
| `reorderPractices` | `PATCH /workspaces/{workspaceSlug}/practices/reorder` |
| `getReviewBatch` | `GET /workspaces/{workspaceSlug}/practices/review-batches/{batchId}` |
| `getReviewEvidenceOutcomes` | `GET /workspaces/{workspaceSlug}/practices/reviews/evidence-outcomes` |
| `getReviewUsage` | `GET /workspaces/{workspaceSlug}/practices/reviews/{reviewId}/usage` |
| `listItemEvents` | `GET /workspaces/{workspaceSlug}/practices/items/{itemId}/events` |
| `listReviewPracticeScripts` | `GET /workspaces/{workspaceSlug}/practices/reviews/{reviewId}/practice-scripts` |
| `getReviewItem` | `GET /workspaces/{workspaceSlug}/practices/items/{itemId}` |
| `getOutlineCollection` | `GET /workspaces/{workspaceSlug}/outline/collections/{collectionId}` |
| `adminGetLlmConnection` | `GET /admin/llm/connections/{id}` |
| `adminGetLlmModel` | `GET /admin/llm/models/{id}` |
| `workspaceGetLlmConnection` | `GET /workspaces/{workspaceSlug}/llm/connections/{id}` |
| `workspaceGetLlmModel` | `GET /workspaces/{workspaceSlug}/llm/models/{id}` |

### Rename conversation interfaces at the 1.0 upgrade

In 1.0, conversation settings, environment variables, metrics and API paths use `conversation`. Update settings and API callers at the 1.0 upgrade. The old names have no aliases. Update dashboard and alert queries too.

### Settings and environment variables

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

### Metrics and spans

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

### HTTP paths

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

### API names and values

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
