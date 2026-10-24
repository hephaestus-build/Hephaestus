#### 🔴 Stop every role for the Heph conversation upgrade, which deletes Heph chat history

This release gives each developer one Heph conversation in each workspace.
One migration drops the Heph chat tables of the previous release: `chat_thread`, `chat_message`, `chat_message_vote` and `mentor_slack_thread`.
A runtime role of the previous release fails when it reads these tables.
Old and new runtime roles cannot use the database together.
There is no rolling upgrade or compatibility period.

Read these instructions before you start any runtime role of this release.
This procedure takes precedence over normal startup.

**What the upgrade deletes.**

- All earlier Heph chat history: web chats, the history of Slack direct messages in Hephaestus, and votes.
  Slack keeps its own copy of the direct messages.
- The link from feedback to the Heph chat message that showed it.
  The migration keeps every feedback row and its state.
  Feedback that Heph showed in a chat stays delivered, but its conversation copy no longer points to a message.

There is no Liquibase rollback for this migration.
The pre-upgrade backup is the only way back to the previous release with its Heph chat history.

**Before you upgrade:**

1. Drain and stop every role: `application-server`, `application-worker` and `webhook-server`.
   The [role drain procedure](https://docs.hephaestus.build/admin/runtime-roles#graceful-drain) applies to the worker.
2. Make a database backup ([Backup and restore](https://docs.hephaestus.build/admin/backup-restore)).
3. Verify that the backup restores.
4. Record the signed release lock and the configuration of the previous release.
   A restore needs this lock, so that the failed release does not migrate the restored database again.

A provider can lose events that arrive while the webhook role is stopped.
Keep the outage short.

**Upgrade:**

1. Apply the changes under **Configuration** below.
2. Install this release for the server, worker and webhook roles.
3. Use the agent image of this release.
   If you pin a custom agent image, rebuild it from the matching source.
   Its `hephaestus.agent.runtime-contract` label must be `8`.
4. Start the server role.
   The migration runs at its startup.
5. Start the worker and webhook roles.
6. Update the Slack app, as **Slack app** below tells.

**If the migration fails.**
Keep every role stopped.
Capture the first failed startup log before you retry:

```bash
docker compose logs application-server | grep -i -m5 liquibase
```

Do not start the previous release against this database.
Choose one recovery path:

1. Apply a forward fix that is qualified for the recorded migration state.
2. Restore the verified backup with its matching signed release lock and configuration.
   Follow [failed-migration recovery](https://docs.hephaestus.build/admin/upgrade#if-migration-fails).
   The backup does not contain data that was recorded after the upgrade.

**Slack app.**
Update the app from `docs/admin/slack-app-manifest-template.yml`.
The manifest adds the bot scope `reactions:write` and removes the `app_context_changed` event.
Reinstall the app in the Slack workspace.
Then select **Reconnect Slack** in each Hephaestus workspace that uses it.
Until then, Heph cannot add 👀 to a question.
See [Update an existing Slack app](https://docs.hephaestus.build/admin/production-setup#update-an-existing-slack-app).

**Preview environments.**

The seed policy now parks copied Heph work before a preview can run it.
The seed loader rejects the earlier seed marker.
Save any preview changes that you need, then recreate the preview through the normal preview procedure.
Do not change the marker or rerun the seed policy over new preview work.

**Configuration.**

- With Slack enabled, the application server now answers Slack interactivity, the App Home and Heph buttons.
  It commits a button's consent change before Slack gets its answer.
  The webhook role does not answer `/webhooks/slack/interactivity`; it keeps `/webhooks/slack` for Events.
  - Give `HEPHAESTUS_INTEGRATION_SLACK_SIGNING_SECRET` to `application-server` as well.
    With Slack enabled and the secret blank, `application-server` does not start.
    `docker/compose.app.yaml` passes it.
  - Route the exact path `/webhooks/slack/interactivity` to `application-server`, without stripping it.
    The route must outrank the `/webhooks` route of `webhook-server`.
    `docker/compose.app.yaml` adds this route (`https-slack-interactivity`, priority 110).
    A custom proxy needs the same route.
    If the Slack app's **Interactivity request URL** names a separate webhook host, point it at a host that reaches `application-server` ([Slack app configuration](https://docs.hephaestus.build/admin/production-setup#slack-app-configuration)).
- Remove the Traefik sticky-cookie labels (`__Secure-hep_workspace_aff`) from a custom proxy configuration.
  `docker/compose.app.yaml` does not have them.
  The `X-Hephaestus-Replica` response header is removed.
- Remove these settings and their `HEPHAESTUS_MENTOR_*` environment variables.
  This release does not read them:
  - `hephaestus.mentor.idle-ttl-seconds`, `grace-timeout-seconds`, `reap-interval-seconds` and `ring-buffer-frames`.
  - `hephaestus.mentor.stdin-write-timeout-ms`, `send-queue-capacity`, `subscriber-queue-capacity` and `attach-first-frame-timeout-seconds`.
  - `hephaestus.mentor.max-sessions-per-user`, `max-sessions-total` and `max-frame-bytes`.
  - `hephaestus.mentor.in-flight-reaper.window` and `hephaestus.mentor.in-flight-reaper.cron`.
- Rename the Heph rate limit `hephaestus.auth.rate-limit.mentor-chat` to `mentor-turns`.
  Rename `HEPHAESTUS_RATE_LIMIT_MENTOR_CHAT_CAPACITY` to `HEPHAESTUS_RATE_LIMIT_MENTOR_TURNS_CAPACITY`.
  Rename `HEPHAESTUS_RATE_LIMIT_MENTOR_CHAT_PERIOD` to `HEPHAESTUS_RATE_LIMIT_MENTOR_TURNS_PERIOD`.
  This release does not read the old names, so a changed limit goes back to its default until you rename it.
- The **Internet access** switch of the Heph model assignment is removed.
  The `allowInternet` field of the agent-binding API and the `workspace_agent_binding.allow_internet` column are removed too.
  In the previous release, the switch gave Heph's sandbox internet access.
  The Heph host runs on an internal network with no internet access.
  [Runtime roles](https://docs.hephaestus.build/admin/runtime-roles#the-heph-host) gives the one exception: a worker that runs outside a container.
- The Heph API under `/api/workspaces/{slug}/mentor/` changed.
  The `chat`, `sandbox` and `threads` endpoints are removed.
  Regenerate a custom API client from the `server/openapi.yaml` of this release.
- [Tunables](https://docs.hephaestus.build/admin/runtime-roles#tunables) lists the new `hephaestus.mentor.turn.*` settings.
  All roles must have the same values.

**Worker and Heph host.**
In the previous release, a worker started one Heph sandbox for each developer and workspace.
In this release, each worker starts one Heph host container when it claims its first Heph turn.
That host runs all Heph turns of the worker.
It has a fixed limit of 2 GiB of memory ([The Heph host](https://docs.hephaestus.build/admin/runtime-roles#the-heph-host)).
The recommended single host increases from 16 GB to 24 GB of memory ([Install](https://docs.hephaestus.build/admin/install#requirements)).

- `HEPHAESTUS_WORKER_MENTOR_MAX` (`hephaestus.worker.capacity.mentor-max`) sets how many Heph turns the Heph host of one worker runs at the same time.
  Its `auto` default is 8.
  In the previous release, it limited the Heph sandboxes of one worker, and `auto` was half the CPUs.
  [Heph capacity](https://docs.hephaestus.build/admin/runtime-roles#heph-capacity) tells how to size it.
- A worker in a container that it cannot identify starts no Heph host, and its Heph turns fail.
  [Docker configuration](https://docs.hephaestus.build/admin/configuration-readiness#docker-configuration) gives the hostname rule.
- Heph reads context documents, code and Git history in turn sandboxes on the worker, not in the Heph host.
  Each worker keeps 2 empty turn sandboxes and starts one for each running Heph turn that reads.
  Each has 1 CPU, 1 GiB of memory and no network ([Turn sandboxes](https://docs.hephaestus.build/admin/runtime-roles#turn-sandboxes)).
  The worker needs Docker for them.
  Heph reads repositories only where Git mirrors are enabled (`GIT_CHECKOUT_ENABLED`).

**Models.**

- Heph refuses a model whose stated context window is below 32,768 tokens or whose output limit is below 4,096 tokens.
  Check the Heph model assignment of each workspace ([AI providers](https://docs.hephaestus.build/admin/ai-providers#the-smallest-model-for-heph)).
- A Heph turn fails when its model provider sends no response headers for 5 minutes, or no byte of its stream for 2 minutes.
  SSE keep-alive comments count as bytes.
  `HEPHAESTUS_MENTOR_TURN_MODEL_FIRST_BYTE_TIMEOUT` and `HEPHAESTUS_MENTOR_TURN_MODEL_IDLE_TIMEOUT` change these bounds.
  A provider gateway that buffers whole answers must stream them or send keep-alive comments.
  Otherwise, set the bounds longer than the longest answer.
- Each worker pauses a provider connection for 60 seconds when its calls keep stalling or failing.
  Watch `resilience4j_circuitbreaker_state{name=~"llm-connection-.*"}`.

**Metrics.**
Update dashboards and alerts that use these series.
[Heph turn metrics](https://docs.hephaestus.build/admin/observability#heph-turn-metrics) lists the series of this release.

- These series are removed: `mentor_attach_*`, `mentor_session_*`, `mentor_relay_*`, `mentor_turn_runtime_ready_seconds`, `mentor_frame_parse_error_total`, `mentor_in_flight_reaper_failure_total`, `mentor_ring_buffer_dropped_total`, `mentor_send_frame_bytes`, `mentor_send_rejected_total`, `mentor_subscriber_dropped_total`, `mentor_subscriber_error_total`, `mentor_watchdog_targets` and `llm_proxy_unbillable_refused_total`.
- The `worker_capacity_*{type="mentor"}` series are removed. Use `mentor_host_turns_active` for the Heph turns that a worker runs.
- The `sandbox` label of `mentor_turn_first_token_seconds` is removed.
- `mentor_turn_cost_usd_USD` is renamed to `mentor_turn_cost_usd`, with the same `_count`, `_sum` and `_max` series.
- `mentor_turn_completed_total{outcome}` has the values `success`, `stopped`, `failed` and `lease_expired`.
  The values of the previous release, such as `client_disconnect`, `poisoned`, `timeout` and `rejected`, are removed.
- `mentor_turn_started_total` counts each attempt that a worker claims, also a retry and an attempt that admission declines.
  In the previous release, it counted each question when a server dispatched it.
- `mentor_turn_first_token_seconds` starts when an attempt starts on a worker.
  In the previous release, it started when a server accepted the question.
- New series: `mentor_turn_retried_total`, `mentor_turn_queued`, `mentor_turn_queue_wait_seconds`, `mentor_turn_lease_reaper_failure_total`, `mentor_turn_claim_failure_total`, `mentor_host_ready`, `mentor_slack_reply_total`, `mentor_live_streams` and `llm_proxy_upstream_stalled_total`.
