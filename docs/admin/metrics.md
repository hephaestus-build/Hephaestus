---
title: Metrics
description: The Prometheus metrics that each runtime role exports, by area, with their labels and meaning.
page_type: reference
---

This page lists the metrics that the runtime roles export on their management port, by area.
[Set up monitoring](./monitoring) scrapes them, and [Alert rules](./alert-rules) reads them.

## Daily reads

| Question | Query | Healthy |
| --- | --- | --- |
| Does commit link correction make progress? | `commit_identity_pending{job="hephaestus"}` and `sum by (outcome) (increase(commit_identity_resolutions_total{job="hephaestus"}[1d]))` | The queue shrinks while the job is enabled. `commit_identity_link_changes_total` counts the changed links. |
| Did tenancy report or refuse a read? | `sum by (table, mode) (increase(tenancy_violation_total{job="hephaestus"}[1d]))` | No result |

## Feedback trust

| Prometheus name | Type | Labels | Meaning |
| --- | --- | --- | --- |
| `practice_result_recorded_total` | Counter | `outcome` | Results that a review recorded: `met`, `not_met`, `does_not_apply` or `could_not_determine` |
| `practice_result_marked_total` | Counter | `kind`, `change` | Marks that people set or removed. `kind` is `incorrect` or `accepted`, and `change` is `set` or `removed` |
| `practice_feedback_response_total` | Counter | `resolution` | Answers of developers to feedback, when the resolution changes: `addressed`, `disagree` or `not_applicable`. A new comment or a usefulness alone does not count |

A practice is not a label, because each workspace can add practices.
To compare practices, read the database.
This query reads all workspaces, and the results of each workspace stay apart:

```sql
SELECT r.workspace_id, p.slug, r.outcome,
       count(*) AS results,
       count(m.id) AS marked_incorrect
FROM result r
JOIN practice p ON p.workspace_id = r.workspace_id AND p.id = r.practice_id
LEFT JOIN result_mark m ON m.workspace_id = r.workspace_id AND m.result_id = r.id
  AND m.kind = 'INCORRECT' AND m.removed_at IS NULL
WHERE r.recorded_at > now() - interval '7 days'
GROUP BY r.workspace_id, p.slug, r.outcome
ORDER BY marked_incorrect DESC, results DESC;
```

This query counts mark changes in the last seven days, whatever the age of the result.
A mark set and removed in that window counts once for each change.
A recent removal of an older mark counts only as `removed`.
The query groups each workspace separately.

```sql
WITH changes AS (
  SELECT workspace_id, result_id, kind, 'set' AS change
  FROM result_mark
  WHERE created_at > now() - interval '7 days'
  UNION ALL
  SELECT workspace_id, result_id, kind, 'removed' AS change
  FROM result_mark
  WHERE removed_at > now() - interval '7 days'
)
SELECT c.workspace_id, p.slug, c.kind, c.change, count(*) AS changes
FROM changes c
JOIN result r ON r.workspace_id = c.workspace_id AND r.id = c.result_id
JOIN practice p ON p.workspace_id = r.workspace_id AND p.id = r.practice_id
GROUP BY c.workspace_id, p.slug, c.kind, c.change
ORDER BY c.workspace_id, p.slug, c.kind, c.change;
```

## Agent-job metrics

| Prometheus name               | Type      | Labels    | Meaning                                                         |
| ----------------------------- | --------- | --------- | --------------------------------------------------------------- |
| `agent_job_total`             | Counter   | `outcome` | Committed terminal transitions                                  |
| `agent_job_duration_seconds`  | Histogram | `phase`   | Queue, execution-attempt, and total durations                   |
| `agent_job_stage_duration_seconds` | Histogram | `stage`, `outcome` | Duration of one stage of a review attempt |

`outcome` is one of `completed`, `failed`, `timed_out`, or `cancelled`. `phase` is one of `queue`,
`execution`, or `total`. Identifiers, model and provider names, server IDs, and exception text are not
labels. See [Practice Review Operations](./practice-review-operations.mdx) for queue health and the
existing execution metric.

`agent_job_stage_duration_seconds` measures each stage of one attempt:

| `stage` | From | To |
| --- | --- | --- |
| `capture` | The start of the evidence capture | The inputs of every practice are ready |
| `sandbox` | The launch of the sandbox that runs the practice sessions | Its result |
| `composition` | The claim of the `review_compose` run in the review's sandbox | The run's end, or its hand-on to another attempt |

Its `outcome` is `ok`, `failed`, `partial` or `handed_on`.
`handed_on` means that composition stopped for a passing cause, and another attempt continues it.
A sandbox that exits with any other code than 0 or the partial-review code 78 is `failed`, also when it admitted results.
A sandbox that left a practice without a result and staged the rest is `partial`. Another attempt can continue the review.
A sandbox that had no room is not a sample: the attempt waits in the queue.

A composition run that loses its lease is not a sample: the run reaper queues it again.
The stages do not add up to the attempt.
An attempt that resumes a composition has a `composition` sample, but no `capture` and no `sandbox` sample.
The `capture` and `sandbox` stages are also spans, `practice_review.<stage>`, under the attempt's `practice_review.execute` span.

The runner admits results through two server routes.
Their durations are in `http_server_requests_seconds`:

```promql
histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket{uri=~"/internal/llm/(stage-result|admit-results)"}[1h])))
```

`stage-result` runs once for each practice, and `admit-results` runs once at the end of the sandbox.

## Practice review outcome metrics

The server role counts each review once, when its end commits.
A server that stops between the commit and the count loses that count.

| Prometheus name | Type | Labels | Meaning |
| --- | --- | --- | --- |
| `practice_review_ended_total` | Counter | `kind`, `status`, `reason` | Reviews that ended |
| `practice_review_items_ended_total` | Counter | `kind`, `state`, `reason` | The items of the reviews that ended, by their last state |

`kind` is `item` or `scan`.
`status` is `complete`, `partial`, `failed` or `cancelled`.
The `reason` of a review is its stop reason in lowercase, or `none` for a complete review.

The `state` of an item is `judged`, `reused`, `withheld`, `listed` or `staged`.
An item that is still `listed` when its review ends is an item that no attempt reached.
The `reason` of a withheld item is its recorded reason in lowercase, for example `execution_failed` or `consulted_source_unread`.
A reason that is not a constant name counts as `other`.
Other items have the reason `none`.
The `review.stop_reason` and `review_item.reason` columns keep the same reasons for each review.

The server and the worker register each status and stop reason of `practice_review_ended_total` at zero when it starts.
Thus, `increase()` also counts the first end after a start.
An item series starts with its first count, so `increase()` does not count the first items of a new state and reason after a start.

## Feedback readback metric

When a write of feedback on the work was in doubt, the server reads the work again before it does anything else.
This is readback.
A write that the provider confirmed is not read back.

| Prometheus name | Type | Labels | Meaning |
| --- | --- | --- | --- |
| `practice_feedback_readback_total` | Counter | `provider`, `outcome` | Copies of feedback that readback looked for |

`provider` is `github` or `gitlab`.
`outcome` is `found`, `absent` or `unknown`.

`found` records the copy as delivered.
`absent` records that the copy is not on the work. Only an admin retry posts it again.
`unknown` keeps the feedback Unconfirmed. A later attempt reads back again, up to eight attempts.
The `feedback_copy.readback` column records only `absent` and `unknown`: a copy that readback found looks the same as a copy that its write confirmed.

The server registers each provider and outcome at zero when it starts.

## Feedback delivery metrics

Each feedback row is one destination of one review: the work, the practice page or a conversation.
The servers count each change into a settled state, when the change commits.

| Prometheus name | Type | Labels | Meaning |
| --- | --- | --- | --- |
| `practice_feedback_send_attempts` | Gauge | none | Most attempts of a feedback send that has not ended |
| `practice_feedback_send_readback_limit` | Gauge | none | Attempts at which a send whose copies stay unresolved ends `UNCERTAIN` |
| `practice_feedback_settled_total` | Counter | `destination`, `state`, `reason` | Each time feedback reaches a settled state |
| `practice_review_first_delivery_seconds` | Histogram | `trigger`, `approval` | From the occasion of a review to its first posted copy |
| `practice_feedback_oldest_waiting_seconds` | Gauge | none | The age of the oldest feedback on the work that may be sent and has no posted copy |

`destination` is `on_work`, `practice_page` or `conversation`.
`state` is `delivered`, `withheld`, `failed` or `rejected`.
The `reason` of withheld feedback is its stop reason in lowercase, for example `composer_withheld` or `conversation_expired`. Other states have `none`.
Feedback that fails and then delivers after a retry counts once as `failed` and once as `delivered`.

A copy is posted on the work or in a conversation. Feedback on the practice page has no copy, so it starts no first delivery.

For a review of a provider event, the occasion is the time that the provider gives the event.
For other reviews, it is the time of the request or the schedule.
`trigger` is `event`, `request`, `schedule` or `backfill`.
`approval` is `approved` when a person approved the feedback before its post, else `none`.
The buckets end at 1, 2, 5, 10, 15, 20, 30 and 45 minutes, and at 1, 2, 4 and 8 hours.
Two feedback of one review that post at the same moment, in separate transactions, can each count as first.

The server role reads the oldest waiting feedback every 30 seconds.
Feedback that waits for a person's approval, or for the developer to open a conversation, does not count.
The gauge is `0` when no feedback waits, and `NaN` before the first read and after a read that failed.
Take the maximum over the servers.

The roles register every settled state and every first delivery at zero when they start.
Thus, `increase()` also counts the first change after a start.

The server samples send attempts every 30 seconds. If every send has ended, the gauge gives `0`.
Before the first sample or after a read failure, the attempts gauge gives `NaN`.

The readback threshold comes from the same constant that delivery uses.
Each send is one action in the outbox, and the threshold is its maximum number of attempts.
A crash before settlement leaves the send to the outbox, which takes it again when its lease ends. Each reclaim increases its attempts.
A manual retry is a new send that starts at zero attempts.

`HephaestusFeedbackSendRetrying` fires at half the threshold for one minute.
`HephaestusFeedbackSendMetricsUnavailable` reports a missing gauge or a failed read.
Exhaustion ends automatic readback and moves the send to `UNCERTAIN`, so it no longer contributes to the attempts gauge.

These gauges read all workspaces without a workspace label. Take the maximum over servers.

## Release metric

Each runtime role exports `application_build_info` with the value `1`.
Its labels are `version` and `channel`, the same values as `release` in `/actuator/info`.
`channel` is `release`, `commit` or `development`.
This query gives the releases that run in each role:

```promql
count by (runtime_role, version) (application_build_info{job="hephaestus"})
```

## Conversation turn metrics

Each claim of a conversation turn by a server is one attempt.
A turn has a second attempt only after a lease expiry ([Runtime Roles](./runtime-roles.mdx#conversation-turns)).

| Prometheus name                            | Type      | Labels    | Meaning                                                                 |
| ------------------------------------------ | --------- | --------- | ----------------------------------------------------------------------- |
| `mentor_turn_started_total`                | Counter   | none      | Attempts that a server handed to the sandbox driver                  |
| `mentor_turn_completed_total`              | Counter   | `outcome` | Attempts that ended                                                     |
| `mentor_turn_retried_total`                | Counter   | none      | Turns that went back to the queue after a lease expiry                  |
| `mentor_turn_queued`                       | Gauge     | none      | Turns that wait for a server, sampled by the server every 30 seconds    |
| `mentor_turn_queue_wait_seconds`           | Histogram | none      | From a question to the start of its first attempt, also while an earlier question of its conversation runs |
| `llm_proxy_upstream_stalled_total`         | Counter   | `apiProtocol` | conversation model calls and streamed review model calls that the server's LLM proxy cut after the first-byte or the idle bound |
| `resilience4j_circuitbreaker_state`        | Gauge     | `name`, `state` | The provider breaker of each connection, named `llm-connection-<scope>-<connection id>`. `state="open"` is 1 while it refuses calls |
| `resilience4j_circuitbreaker_not_permitted_calls_total` | Counter | `name`, `kind` | Model calls that a provider breaker refused |
| `agent_run_reaped_total`                   | Counter   | `kind`, `outcome` | Expired run leases taken back, by kind, and whether the run was `requeued`, `continued` on a successor, or ended (`attempts_exhausted`, `deadline`, `not_started`) |
| `agent_run_reaper_failure_total`           | Counter   | none      | Runs that the reaper failed to take back. It tries each again next pass  |
| `agent_sandbox_reaped_total`             | Counter   | `reason`  | Sandboxes that the reaper took back: `expired`, `start_limit`, `idle`, or `orphan` for a claim that no sandbox row names |
| `agent_sandbox_dispatch_failure_total`   | Counter   | none      | Dispatch passes that failed to request the sandbox of a due run. It tries again at the next pass |
| `agent_sandbox_reaper_failure_total`       | Counter   | none      | Sandbox reaper passes or claim deletes that failed. It tries each again next pass |
| `agent_sandbox_revoked_total`            | Counter   | `reason`  | Live sandboxes that the server took back before the reaper would: `unisolated` for a sandbox whose network policy did not hold |
| `mentor_turn_duration_seconds`             | Histogram | none      | An attempt, from its claim to its end                                   |
| `agent_run_first_token_seconds`          | Histogram | `kind`, `start` | From the run start to the first part of the answer. `kind="turn"`, `start="cold"` or `"warm"`         |
| `mentor_turn_cost_usd`                     | Summary   | none      | The cost of the calls of one attempt that ended by its end, in US dollars                            |
| `mentor_turn_claim_failure_total`          | Counter   | none      | Claims and starts of a turn that failed with an error                   |
| `mentor_live_streams`                      | Gauge     | none      | Live Hephaestus streams that the app holds open on this server                |

`outcome` is one of `success`, `stopped`, `failed`, or `lease_expired`.
Slack delivery uses the [outbox metrics](#outbox-metrics), with `kind="SLACK_POST"`.
[Slack replies](/contributor/unified-pi-runtime#slack-replies) describes its metadata readback and unknown outcomes.

Web and Slack turns both count.
An attempt that ends before any part of an answer records no first-token time.

`mentor_turn_cost_usd` has `_count`, `_sum` and `_max` series.
An attempt records a cost only when it made a model call and each of its ended calls has a price.

Servers record most of these series.
The server role records `lease_expired` outcomes, the run reaper's counters, the cost of an attempt that lost its lease, the Slack replies and the live streams.
Thus, sum each series across all roles.

The share of attempts that did not succeed shows problems that developers see.
This query gives that share for the last hour:

```promql
sum(increase(mentor_turn_completed_total{outcome=~"failed|lease_expired"}[1h]))
  / sum(increase(mentor_turn_completed_total[1h]))
```

A high `lease_expired` share shows sandbox drivers that stop during turns, or turns whose calls reach no server.
A high `failed` share with no lease expiries shows one of these causes:

- Admission declines turns.
- Turns reach their deadline.
- A server cannot start a turn that it claimed.
- Turns get an error from the model.

The server logs each failed attempt at `WARN` as `conversation turn <id> attempt <n> failed: <reason>; last failed model call: <kind> <status>`, or `none`.
The reason tells which of these happened, for example `its conversation loop reported ERROR: model_error`.
The sandbox driver reports a kind of failure from a closed set, for example `model_error`, `context_overflow` or `upstream_stalled`.
It does not report the error text of the model provider, a tool or the server, or the messages of the conversation.
A server that ends an attempt itself names the class of the failure, for example `starting it failed: IllegalStateException`.
A lease call that ends an attempt after its deadline names `its conversation loop did not end it within PT30S after its deadline`.

The server logs each failed model call of a turn at `WARN` as `conversation model call failed`.
The line has the turn id, the attempt, the kind of failure and the status that the provider or the proxy sent.
It does not have the request or the response.
It is the only `WARN` line for a failed model call of a conversation turn.
A call that the end of its turn cancelled is not a failure.

The turn row keeps the cause, so the cause stays after the server stops:

| Column of `mentor_turn` | Holds |
|---|---|
| `failure_cause` | The reason of the log line |
| `failed_call` | The kind of the last failed model call of the attempt. From the provider: an exception class, `upstream_status`, `upstream_stalled`, `upstream_paused` or a stream outcome such as `upstream_idle`. From the proxy: a refusal such as `refused_budget`, or `gateway_error`. |
| `failed_call_status` | The HTTP status that the provider or the proxy sent for that call, if one was sent |

The model calls of an attempt are rows of `llm_usage_event`: `source_type` is `MENTOR_TURN`, `source_id` is the turn and `source_attempt` is the attempt.
Each row keeps its first failure kind and optional HTTP status in `call_failure`, and its database recording time in `failed_at`.
These fields survive a server restart and do not contain a provider response body.
Late usage reports and repricing do not change them.
The existing model-call warning and `llm.proxy.usage.accumulate.failure` meter remain the signals for a failed call and a failed ledger write.

The sandbox driver's end, the server's own end and a lease that expires write the first three columns.
A lease that expires writes no `failed_call`, because the run reaper ends the turn.
The columns are empty for a turn that did not fail, and for a turn that admission declines.

A turn that admission declines, for example because the budget is spent, is logged at `INFO` as `was declined at its start`.
A turn whose second attempt loses its lease fails on the server role, which logs it at `INFO` as `Conversation turn lost its holder`.
Its `failure_cause` is `its lease expired on its last attempt`.

An increase in `mentor_turn_claim_failure_total` shows a server that claims turns but cannot start them.
The server fails each turn that it cannot start, and claims the next one.
A claim that fails answers the loop's pull with `503`. The loop then pulls again, maybe from another server.
[Runtime roles](./runtime-roles.mdx#conversation-sandboxes) tells what the sandbox driver does when it stops, and what happens to its turns.

## Call pool metrics

Each server reports the [call pools](./runtime-roles#call-pools) whose models it calls.
A server registers every series of a shared pool at start, so a rate starts from zero. It registers a model's own pool at that pool's first call.

The labels are `pool`, `band`, `reason`, `status` and `cause`.
`pool` is the pool's slug. An instance model's own pool has the slug `own-model-<id>`.
All pools of workspaces' own models report under the one label `workspace-models`.

`status` is `429`, `503`, or `529`. `cause` is `refusal` or `delay`.

`band` is `interactive`, `batch`, or `background`: the [band](./runtime-roles#call-pools) of the call.
A conversation call is `interactive`. A composition call and the calls of a requested or event review are `batch`.
The calls of a scheduled or backfill review and product feedback are `background`.
No series names a workspace, a workspace's model, a job or a turn.

| Prometheus name                            | Type      | Labels                     | Meaning |
| ------------------------------------------ | --------- | -------------------------- | ------- |
| `llm_pool_permits_active`                  | Gauge     | `pool`, `band`             | Permits that this server holds. Sum over the servers for the pool's calls in flight |
| `llm_pool_permit_wait_seconds`             | Histogram | `pool`, `band`, `reason`   | How long a queued call waited for its permit. `reason=capacity` includes concurrency, rate, and cooldown admission |
| `llm_pool_permit_abandoned_total`          | Counter   | `pool`, `band`, `reason`   | Calls whose attempt ended, or whose work deadline passed, while they waited |
| `llm_pool_permits_granted_total`           | Counter   | `pool`, `band`             | Grants that this server's calls received |
| `llm_pool_limit`                           | Gauge     | `pool`                     | The calls in flight that the pool allows now: the learned limit under its ceiling. All servers read it from the database. Take the maximum over the servers. `workspace-models` is the sum of its pools |
| `llm_pool_waiters`                         | Gauge     | `pool`, `band`             | Live calls that wait for the pool, by the band that each call asked for. All servers read it from the database. Take the maximum over the servers |
| `llm_pool_limit_decreases_total`           | Counter   | `pool`, `cause`            | How often a provider's refusal (`refusal`), queue (`delay`) or stream without an answer (`unanswered`) lowered the limit |
| `gen_ai_client_operation_time_to_first_chunk_seconds` | Histogram | `pool`, `band` | How long a served stream waited for its provider's first content event, from the request after the grant. A role, an error event or a comment is not content. The limit learns from the same samples. Buckets follow the GenAI semantic conventions, up to 327.68 seconds |
| `gen_ai_client_operation_duration_seconds` | Histogram | `pool`, `band` | How long a call took from its pool's grant to its end, with the GenAI buckets. The wait for the grant is `llm_pool_permit_wait_seconds` |
| `gen_ai_client_calls_total` | Counter | `pool`, `band`, `outcome` | Calls that reached their provider, by how they ended |
| `gen_ai_client_tokens_total` | Counter | `pool`, `band`, `type` | Tokens that the provider reported. `type` is `input`, `output`, `cache_read`, or `cache_write`. `input` excludes the cached tokens |
| `llm_pool_first_token_baseline`            | Gauge     | `pool`                     | The fastest first token that the pool knows, in milliseconds. All servers read it from the database. `workspace-models` is the largest of its pools |
| `llm_pool_first_token_baseline_resets_total` | Counter | `pool`                     | How often a pool at its floor took a new baseline. A repeating rise means that the provider stays slow with no load from this instance |
| `llm_pool_upstream_bucket_reached_total`   | Counter   | `pool`, `bucket`           | Served answers whose rate limit headers showed an empty bucket, which also pauses the pool. `bucket` is `requests`, `tokens`, `input_tokens`, or `output_tokens` |
| `llm_pool_upstream_throttled_total`        | Counter   | `pool`, `status`           | Answers of the pool's provider that it serves too many calls |
| `llm_pool_upstream_absorbed_total`         | Counter   | `pool`                     | Those answers that the caller never saw, because its call waited and asked again |
| `llm_pool_cooldown_active`                 | Gauge     | `pool`                     | 1 while this server knows the pool to be paused after a refusal. Take the maximum over the servers |
| `llm_pool_self_fence_cancelled_total`      | Counter   | `pool`, `reason`           | Calls that a server cancelled because it no longer held their permit. `reason` is `no_renewal`, `lease_lost`, `attempt_ended`, or `past_deadline` |
| `llm_pool_permits_expired_total`           | Counter   | `pool`                     | Permits whose server stopped renewing them while their call was in flight. The pool keeps their debit |
| `llm_pool_lease_renewal_failures_total`    | Counter   | none                       | Failed renewals of a server's permits |

Claim reservations do not add a second wait observation when the model call uses them.

`outcome` tells how a call that reached its provider ended:

| `outcome` | The call |
| --- | --- |
| `completed` | The model finished its answer |
| `length` | The answer stopped at its token or context limit |
| `refused` | The model declined to answer: Anthropic's `refusal`, or OpenAI's `content_filter` or a refusal part |
| `unanswered` | A served stream sent no content and no usage, and the provider stated no refusal, length or error. A stated one wins |
| `error` | The provider answered with an error status, failed the stream, or could not be reached |
| `stalled` | The provider sent nothing for the proxy's silence bound |
| `cancelled` | The caller or the attempt ended the call first |

`refused` is the model's own decision.
A call that the proxy refuses never reaches a provider, so it has no outcome.
`llm_proxy_refused_total` counts those, by `refusal`: `capacity`, `calls_in_flight`, `budget`, `work_time`, `attempt_ended`, or `ai_choice`.
A refusal at a budget cap also counts in `llm_budget_blocked_total`, with `surface="model_call"` and its `cap`.

## Privacy-job metrics

Account erasure, export generation, export expiry and LLM usage retention each report one terminal
outcome per run, published by the server role.

| Prometheus name               | Type    | Labels           | Meaning                                      |
| ----------------------------- | ------- | ---------------- | -------------------------------------------- |
| `privacy_job_completed_total` | Counter | `job`, `outcome` | Terminal privacy-job executions              |
| `privacy_job_affected_total`  | Counter | `job`            | Rows or subjects the run removed or produced |

`job` is one of `account_erasure`, `export_generation`, `export_retention`, or `llm_usage_retention`.
A scrape sets its own `job`, so the store keeps this label as `exported_job`.
`outcome` is `success`, `incomplete`, or `failure`.
An empty backlog is a `success`.

`HephaestusPrivacyJobNotRunning` reports an hourly sweep that ended no run.
`HephaestusPrivacyJobFailing` reports a job that failed or left work at least twice in 26 hours.
`incomplete` means the run ended without error but exhausted its time budget while eligible work remained.

The LLM usage retention sweep deletes in batches for at most five minutes.
It reports `incomplete` when expired rows remain.
A deployment that reports this each day never finishes deletion.
Account, workspace, export, and source identifiers are not labels.
The [personal-data map](./dsms/personal-data-map.md) lists the erasure and retention paths these metrics cover.

For export generation, `success` and the affected count are published only after the export commits.
A failed attempt reports `failure` even if the database is unavailable and cannot save the failed
status. Consult the server log when an export remains queued or processing after a reported failure.

## Transaction metrics

Every process with a database times each database transaction from its begin to its commit or rollback.
A transaction holds a pooled connection for at most that time, so a long transaction can make other requests wait for a connection.

| Prometheus name                                | Type      | Labels                | Meaning                                                    |
| ---------------------------------------------- | --------- | --------------------- | ---------------------------------------------------------- |
| `database_transaction_duration_seconds`        | Histogram | `boundary`, `outcome` | Time from transaction begin to commit or rollback. `_bucket`, `_count` and `_sum` series |
| `database_transaction_duration_seconds_max`    | Gauge     | `boundary`, `outcome` | The longest such time in the current step                  |

`boundary` is the class and method that open the transaction, for example `SyncJobService.completeJob`.
A programmatic transaction without a name reports `programmatic`.
`outcome` is `commit` or `rollback`. A commit that fails reports `rollback`.

The buckets are at 1 s, 5 s and 30 s.
Arguments, identifiers and workspaces are not labels.
Every transaction is timed in the same way, so the count is the transaction rate of each boundary.

The 95th percentile of each boundary over 1 hour:

```promql
histogram_quantile(0.95, sum by (boundary, le) (rate(database_transaction_duration_seconds_bucket[1h])))
```

The bucket edges limit its resolution. Use the `_max` series for the longest transaction.
Compare a slow boundary with `hikaricp_connections_pending` and `hikaricp_connections_acquire_seconds_max`.

`database_transaction_http_requests_total` (Counter, label `boundary`) counts the outbound HTTP requests that started while a transaction was open.
Such a request holds the connection and the row locks of its transaction until the other service answers.
It counts the requests to GitHub, GitLab, Outline, model providers and other HTTP services.
It does not count Slack or Kubernetes requests.

The boundaries that make such requests, over 1 day:

```promql
sort_desc(sum by (boundary) (increase(database_transaction_http_requests_total[1d])))
```

## Provider removal repair metrics

Removal records and credential expiry are independent.
The [integration guide](./integrations.mdx#confirm-a-provider-removal) owns the repair procedure.

| Prometheus name | Type | Labels | Meaning |
| --- | --- | --- | --- |
| `integration_provider_repair_attempts_total` | Counter | `kind`, `outcome` | Ownership checks and removal attempts by their recorded outcome |
| `integration_provider_repair_open` | Gauge | `kind`, `state` | Unresolved provider identities. Aggregate reads are cached for at most ten seconds |

`kind` is `github`, `gitlab`, `slack`, or `outline`.
`state` is `registering`, `pending`, `checking`, `dispatching`, `awaiting_confirmation`, `needs_authorization`, or `confirmation_required`.
`outcome` is `verified_absent`, `different_owner`, `awaiting_confirmation`, `needs_authorization`, `terminal_success`, `admin_confirmation`, `cancelled`, or `discovery_unconfirmed`.
An earlier uncertain attempt keeps its block even when a later read shows absence.
Only a terminal receipt for that exact attempt or explicit admin confirmation can end that uncertainty.
Metrics contain no workspace, person, token, callback URL or provider resource label.

`HephaestusProviderRemovalNeedsConfirmation` identifies sustained authorization or confirmation needs.
The [alert response](./production-operations-runbook.mdx#alert-response) states the action.

## WAL archive metrics

The server role reads `pg_stat_archiver` and `archive_mode` from the database every 30 seconds.
A scrape does not query the database.

| Prometheus name                              | Type    | Meaning                                                                          |
| -------------------------------------------- | ------- | -------------------------------------------------------------------------------- |
| `database_wal_archive_failing`               | Gauge   | `1` when a failed archive attempt came after the last archived segment, else `0` |
| `database_wal_archive_sample_failures_total` | Counter | Reads of the archiver statistics that failed                                     |

The gauge makes no claim that the server did not check:

- Before the first read, the gauge is `NaN`.
- While `archive_mode` is `off`, the gauge is `NaN`. A database without archiving is not reported as healthy or as failing.
- After a read that failed, the gauge is `NaN` until the next read, and the sample counter increases.
  The last value is not kept, because it could report a healthy archive that the server did not check.
  `HephaestusWalArchiveFailing` also fires on failed reads that continue, so a read outage does not resolve it.

The bundled PostgreSQL archives its WAL only when `postgresql.backup.enabled` is `true`.

## HTTP route metrics

`http.server.requests` uses route templates, not individual workspace slugs, message IDs, or query
strings. Its URI-tag budget is 320: enough for the supported route inventory and the framework's
bounded unmatched-request and management tags. A new URI beyond the budget is refused rather than
creating unbounded time series. A limit warning therefore means metrics are incomplete and needs
investigation, not a logging-level change.

Concrete IDs or URLs in `uri` tags indicate a tagging defect.

The histogram has buckets at 25, 50, 100, 250 and 500 milliseconds, and at 1, 2, 5 and 10 seconds.
The objectives read the 1-second and 2-second buckets.
Each label set of the meter exports 13 series: 9 finite buckets, `+Inf`, a count, a sum and a maximum.
This query gives the 95th percentile of each route over one hour:

```promql
histogram_quantile(0.95, sum by (uri, le) (rate(http_server_requests_seconds_bucket{job="hephaestus",runtime_role="server"}[1h])))
```

`hikaricp_connections_acquire_seconds` has buckets at 1, 5, 10, 50, 100 and 500 milliseconds, and at 1, 5, 10 and 30 seconds.
`agent_job_duration_seconds` has buckets from 1 second to 4 hours, so a review of many minutes has a percentile.
`agent_run_first_token_seconds` has buckets from 0.5 to 60 seconds, with one at the conversation objective's 10 seconds.
`mentor_turn_duration_seconds` has buckets from 1 second to 10 minutes.
The roles register both turn timers at zero when they start.

1. When the limit is reached, first inspect the actual `uri` tag values.
2. Fix a tagging defect before considering a larger budget.
3. Do not turn off the bound.

## Page experience metrics

The web app can report how each visit felt to the reader.
**`PAGE_EXPERIENCE_METRICS`** turns it on, and it is off by default.
In the EU, turning it on can require consent or a documented legal basis.
Read [Page experience](./legal-pages.mdx#page-experience) before you turn it on.
When it is off, the browser does not load the measurement library, and the server has no `POST /web-vitals` endpoint and none of these metrics.

When it is on, the web app measures with the [`web-vitals`](https://github.com/GoogleChrome/web-vitals) library.
It loads the library only for a document that started on a signed-in page, after it reads the setting.
The library then reads the timings that the browser recorded since the page opened.

Each measurement belongs to one navigation:

- `load` is the first page of the document.
- `route` is one page that the reader opened after it, without a new page load.
  Chromium 151 and later detect such a route change, and measure each route view on its own.
  Firefox and Safari do not detect it, so for them `load` covers the whole document.
  A route change that the browser does not detect stays in the navigation before it.

The library reports the INP and layout shift of a navigation when the next navigation starts, or when the page hides.
The web app sends each measurement once, with the first value that the library reports.
It sends them to `POST /web-vitals` each time the reader leaves the page.
Thus, the INP and layout shift of the current navigation are the values when the reader first leaves it.
A page that the browser shows again from its back-forward cache reports nothing more.

A report names no person, workspace or page address.
The server keeps no record of it.
It only updates these metrics:

| Metric | Tags | What it measures |
| --- | --- | --- |
| `web_vital_duration_seconds` | `vital`, `navigation`, `page`, `region` | `lcp`: the largest contentful paint. `inp`: the slowest interaction to its next paint. `ttfb`: the time to the first byte of the page, for `load` only. |
| `web_vital_layout_shift_thousandths` | `navigation`, `page`, `region` | The cumulative layout shift of the navigation, in thousandths of the score. A score of 0.1 is 100. Chromium only. |

`page` is the page of the navigation: `practice_profile`, `ask_hephaestus`, `feedback`, `feedback_approval`, `activity` or `other`.
`region` is the landmark of the element that the measurement names: `sidebar`, `header`, `page` or `other`.
TTFB measures the whole document, so its region is always `other`.
The largest contentful paint of a `route` view counts only the elements that the route change painted again.
Thus, compare `route` with `route` and `load` with `load`.

The server accepts only these values, so the number of series stays small.
`web_vital_duration_seconds` has at most 102 tag combinations: LCP and INP for each navigation, page and region, and TTFB for each page.
`web_vital_layout_shift_thousandths` has at most 48 combinations.
Each combination exports its finite buckets, a `+Inf` bucket, a count, a sum and a maximum.
That is 13 series for each duration combination and 6 for each layout shift combination, 1,614 series in total.
One account or address can send 30 reports a minute (`HEPHAESTUS_RATE_LIMIT_BROWSER_REPORTS_*`).

The finite bucket bounds include the [Core Web Vitals thresholds](https://web.dev/articles/vitals):

- LCP: 2.5 s and 4 s.
- INP: 200 ms and 500 ms.
- TTFB: 0.8 s and 1.8 s.
- Layout shift: 100 and 250 (0.1 and 0.25).

The other duration bounds are 100 ms, 1.2 s and 8 s.
Thus, a percentile from these buckets is an estimate.
A percentile above 8 s shows as 8 s.

To separate the reader's time from the server's time, compare `web_vital_duration_seconds` with `http_server_requests_seconds` for the routes the page reads.
For example, the 75th percentile of LCP on the practice profile:

```promql
histogram_quantile(0.75, sum by (le) (rate(web_vital_duration_seconds_bucket{vital="lcp", navigation="load", page="practice_profile"}[1h])))
```

## Browser faults

The web app can report the failures that readers meet in the browser.
**`BROWSER_FAULT_REPORTS`**, or `config.browserFaultReports` in the Helm chart, turns it on, and it is off by default.
In the EU, turning it on can require consent or a documented legal basis.
Read [Browser fault reports](./legal-pages.mdx#browser-fault-reports) before you turn it on.
When it is off, the browser listens for no failure, and the server has no `POST /browser-faults` endpoint and no `browser_faults_total` metric.

When it is on, each page reports these kinds of failure:

- `uncaught_error`: an error that no code caught.
- `unhandled_rejection`: a promise that failed with no handler.
- `render_error`: the page showed an error view instead of its content.
  A not-found or no-access view is not a failure.
  A view that shows a failed request is reported as `request_failed`.
- `recovered_error`: the page failed to render a part and rendered it again.
- `chunk_load_failed`: a script of the web app did not load when the reader opened a page.
  A deploy that removed the script is a common cause.
- `request_failed`: a request to the server failed with a 5xx status, or did not reach the server.
  A request that the reader cancelled is not a failure.

Readers who are not signed in report too, because a broken sign-in page is a failure.
A page sends its failures after five quiet seconds, or when the reader leaves it.
It counts a repeated failure instead of sending it again.
It sends at most 20 distinct failures, in at most 10 reports, one at a time.

A report has only closed values: the kind, the journey page, the class of the error and the release of the web app.
The release is the commit that the web app image was built from, or its version.
A report also has the positions of the stack frames in the web app's own scripts.
It has no error message, page address, query string, user agent or identifier of a person or workspace.
The server refuses a value outside these sets.
It drops a field that it does not know, and never logs it.

The server also refuses a report that the browser marks as sent from another site, and a body above 16 KiB.
One account or address can send 30 reports a minute (`HEPHAESTUS_RATE_LIMIT_BROWSER_REPORTS_*`).
A refused report writes no log record.
These metrics count the refusals:

| Refusal | Metric |
| --- | --- |
| Above the rate limit (429) | `auth_ratelimit_blocked_total{bucket="browser-reports"}` |
| A body above 16 KiB (413), or a body without a length (411) | `browser_faults_refused_total{reason}`, with `payload-too-large` or `length-required` |
| A value outside the sets (400), or a report from another site (403) | `http_server_requests_seconds_count{uri="/browser-faults"}`, by `status` |
| Another origin or no CSRF token (403) | `http_server_requests_seconds_count{uri="UNKNOWN",status="403"}`, together with these refusals of every other endpoint |

The server writes each failure as one log record and counts it:

| Name | Where | Values |
| --- | --- | --- |
| `browser.fault` | `event.name` of a WARN log record | `browser.fault.kind`, `browser.page`, `error.type`, `browser.release`, `browser.fault.count`, `browser.fault.frames` and `browser.signed_in`. For a `request_failed` with a status: `browser.request.trace_id` and `http.response.status_code` |
| `browser_faults_total` | Counter, tags `kind`, `page` and `error_type` | Each failure adds its count |

`error.type` is `type_error`, `reference_error`, `syntax_error`, `range_error` or `other` for a script error.
It is `chunk` for `chunk_load_failed`, and `http_5xx` or `network` for `request_failed`.
`browser.request.trace_id` is the trace id that the server returned for the failed request as `X-Request-Id`.
Search the log store for it to find every server record of that request ([Correlating a request](./monitoring#find-the-records-of-one-request)).
`browser.fault.frames` lists the frames as `<hash>:<line>:<column>`.
The hash is the last eight characters of the script's file name, which name one build of the script.

## Outbox metrics

The outbox carries out every effect that Hephaestus has on a provider, such as an email.
Each server replica samples the backlog every 30 seconds.
A failed sample keeps the last values.

| Prometheus name | Type | Labels | Meaning |
| --- | --- | --- | --- |
| `action_attempts_total` | Counter | `kind`, `result`, `recorded` | Attempts to carry out an action, by the adapter's result, and whether the result was recorded |
| `action_ended_total` | Counter | `kind`, `state`, `reason` | Actions that ended: `DONE`, `UNCERTAIN`, `FAILED` or `CANCELLED`, with the reason |
| `action_backlog` | Gauge | `kind`, `state` | Actions that have not ended: `PROPOSED`, `ADMITTED` or `EXECUTING` |
| `action_oldest_due_seconds` | Gauge | `kind` | How long the oldest due action of the kind has waited. An admitted action is due from its next attempt time, and an action in its retry backoff is not due yet. An action whose lease expired, and that no replica took again, is due from the end of that lease. 0 when none is due |
| `action_backlog_sampler_failures_total` | Counter | none | Backlog samples that failed |

## Sync-status notifications

`integration_sync_push_messages_total` counts `postgres_publish` and `postgres_receive` by `outcome` (`success` or `failure`).
A receive failure includes a listener disconnect or a refused malformed routing hint. It carries no content label.
The sync SSE counters record subscriptions and local delivery separately.
`HephaestusSyncNotificationsFailing` reports failures that persist for five minutes. REST polling continues while hints are lost.
