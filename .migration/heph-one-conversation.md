#### 🔴 Stop every role before the upgrade, which deletes Heph chat history, then reinstall the Slack app

Heph keeps one conversation per developer and workspace, in new tables. The upgrade drops
`chat_thread`, `chat_message`, `chat_message_vote` and `mentor_slack_thread` in the same release.
Thus, an earlier release that still runs fails when it reads them.

**Before you upgrade:**

1. Take the regular database backup ([Backup and restore](https://docs.hephaestus.build/admin/backup-restore)).
   It is the only way back to the previous release with its Heph history.
2. Stop every role: `application-server`, `application-worker` and `webhook-server`.
   Do not do a rolling upgrade for this release.
3. Upgrade, then start all roles.

**All earlier Heph conversations are deleted.** This includes web chats, the history of Slack direct
messages in Hephaestus, and votes. Slack keeps its own copy of the direct messages. Feedback that Heph
showed earlier stays delivered, but its placement no longer points to a message.

**Reinstall the Slack app.** Update the app from `docs/admin/slack-app-manifest-template.yml`. It adds
the bot scope `reactions:write`, and it no longer subscribes to `app_context_changed`. Reinstall the app
in the Slack workspace, then select **Reconnect Slack** in each Hephaestus workspace that uses it. Until
then, Heph cannot add 👀 to a question. See [Update an existing Slack app](https://docs.hephaestus.build/admin/production-setup#update-an-existing-slack-app).

**Remove what no longer exists.**

- The Traefik sticky-cookie labels (`__Secure-hep_workspace_aff`) are removed from
  `docker/compose.app.yaml`. Remove them from a custom proxy configuration.
  The `X-Hephaestus-Replica` response header is gone.
- These settings are no longer read: `hephaestus.mentor.idle-ttl-seconds`,
  `grace-timeout-seconds`, `reap-interval-seconds`, `ring-buffer-frames`, `stdin-write-timeout-ms`,
  `send-queue-capacity`, `subscriber-queue-capacity`, `attach-first-frame-timeout-seconds`,
  `max-sessions-per-user`, `max-sessions-total` and `max-frame-bytes`, all under `hephaestus.mentor.`,
  and `hephaestus.mentor.in-flight-reaper.window` and `.cron`. Remove their `HEPHAESTUS_MENTOR_*`
  environment variables too.
- The Heph rate limit is renamed from `hephaestus.auth.rate-limit.mentor-chat` to `mentor-turns`.
  Rename `HEPHAESTUS_RATE_LIMIT_MENTOR_CHAT_CAPACITY` and `HEPHAESTUS_RATE_LIMIT_MENTOR_CHAT_PERIOD` to
  `HEPHAESTUS_RATE_LIMIT_MENTOR_TURNS_CAPACITY` and `HEPHAESTUS_RATE_LIMIT_MENTOR_TURNS_PERIOD`. The old
  names are no longer read, so a changed limit returns to its default until you rename it.
- The **Internet access** switch of the Heph model assignment is removed, with the `allowInternet` field of
  the agent-binding API and its `workspace_agent_binding.allow_internet` column. On the previous release, the
  switch gave Heph's sandbox internet access. The Heph host now runs on an internal network, with no internet
  access. [Runtime roles](https://docs.hephaestus.build/admin/runtime-roles#the-heph-host) gives the one exception:
  a worker that runs outside a container. Practice reviews always ran on an internal network and refused the switch.
- A worker in a container that it cannot identify starts no Heph host, and its Heph turns fail.
  [Docker configuration](https://docs.hephaestus.build/admin/configuration-readiness#docker-configuration)
  gives the hostname rule.
- Dashboards and alerts on these series need new queries, because the series are gone:
  `mentor_attach_*`, `mentor_session_*`, `mentor_relay_*`, `mentor_turn_runtime_ready_seconds`,
  `mentor_frame_parse_error_total`, `mentor_in_flight_reaper_failure_total`,
  `mentor_ring_buffer_dropped_total`, `mentor_send_frame_bytes`, `mentor_send_rejected_total`,
  `mentor_subscriber_dropped_total`, `mentor_subscriber_error_total`, `mentor_watchdog_targets`, the
  `sandbox` label of `mentor_turn_first_token_seconds`, and the `worker_capacity_*{type="mentor"}`
  series. Use `mentor_host_turns_active` for the Heph turns that a worker runs.
- `mentor_turn_completed_total{outcome}` has new values: `success`, `stopped`, `failed` and
  `lease_expired`. The earlier values, such as `client_disconnect`, `poisoned` and `timeout`, are gone.
- Two series keep their names but change their meaning. `mentor_turn_started_total` counts each attempt that a
  worker claims, also a retry and an attempt that admission declines. On the previous release, it counted each
  question that a server accepted. `mentor_turn_first_token_seconds` runs from the start of the attempt on a worker to the first partial
  answer of its host. On the previous release, it ran from the moment a server accepted the question.
- `mentor_turn_cost_usd_USD` is renamed to `mentor_turn_cost_usd`, with the same
  `_count`, `_sum` and `_max` series. `llm_proxy_unbillable_refused_total` is gone: every proxy
  credential now belongs to one review attempt or one Heph turn attempt. See [Observability](https://docs.hephaestus.build/admin/observability).

**What changes on the worker.** Each worker starts one Heph host container from the agent image when it
claims its first Heph turn. [Runtime roles](https://docs.hephaestus.build/admin/runtime-roles#the-heph-host)
gives the memory and CPU limits of that container.
The recommended single host therefore rises from 16 GB to 24 GB of memory.
See [Install](https://docs.hephaestus.build/admin/install).

`HEPHAESTUS_WORKER_MENTOR_MAX` (`hephaestus.worker.capacity.mentor-max`) now sets how many Heph
answers that host writes at once. [Runtime roles](https://docs.hephaestus.build/admin/runtime-roles#heph-capacity)
gives its default and how to size it. Run the agent image of this release: the runtime contract is 5.

A Heph answer now fails instead of waiting for its deadline when its model provider sends no response
headers for 5 minutes, or its stream sends no byte for 2 minutes; SSE keep-alive comments count as bytes.
`HEPHAESTUS_MENTOR_TURN_MODEL_FIRST_BYTE_TIMEOUT` and `HEPHAESTUS_MENTOR_TURN_MODEL_IDLE_TIMEOUT`
(`hephaestus.mentor.turn.model-first-byte-timeout` and `model-idle-timeout`) change the bounds.
A provider gateway that buffers whole answers must stream them or send keep-alive comments, or the bound
must be longer than the longest answer. A provider connection whose calls keep stalling or failing is
paused for 60 seconds on each worker; watch `resilience4j_circuitbreaker_state{name=~"llm-connection-.*"}`. Heph now refuses a model whose stated context window is below 32,768 tokens or whose
output limit is below 4,096 tokens. Check the Heph model assignments of each workspace
([AI providers](https://docs.hephaestus.build/admin/ai-providers#the-smallest-model-for-heph)).
