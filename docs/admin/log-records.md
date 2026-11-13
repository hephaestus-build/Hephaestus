---
title: Log records
description: The fields of the JSON log records of each runtime role, the lifecycle and outbox events, and the PostgreSQL log lines.
page_type: reference
---

Each runtime role writes one JSON record per event to standard output, in the ECS format of Spring Boot.
This page lists the fields and the events.
[Collect logs](./monitoring#collect-logs) ships them to a log store.

## Fields

| Field                                             | Meaning                                                                                          |
| ------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| `@timestamp`                                      | UTC event time, in ISO-8601 format                                                               |
| `log.level`, `log.logger`, `message`              | Severity, originating logger, and human-readable event description                               |
| `service.name`, `service.version`                 | `Hephaestus` and the release that the deployment names in `APP_VERSION`, the same values that traces carry. The chart sets `APP_VERSION` from `release.version` on every container of the server image, the migration Job included |
| `service.environment`                             | `DEPLOYMENT_ENVIRONMENT`                                                                         |
| `service.node.name`                               | The container ID, the same value as the `X-Hephaestus-Replica` response header                   |
| `context.event.name`                              | Stable name on structured application events. Do not parse the message to identify these events  |
| `trace.id`, `span.id`                             | The active trace scope, when there is one: the W3C trace ID and span ID, as ECS names them        |
| `context.job.id`, `context.workspace.id`          | Thread-scoped context, when available                                                            |
| `context.run.id`, `context.run.epoch`, `context.subject.key` | The [agent run](#agent-run-and-outbox-events) whose holder writes the record, the epoch of its claim, and its subject, such as `review:<id>`. Two records of one run with different epochs come from different claims |
| `context.sandbox.name`                            | The agent sandbox that made a gateway request: its claim, Sandbox and Pod name. Each gateway request also names its subject, workspace and review when its subject is a review. A request for one run also names `run.id` and `run.epoch`, after the gateway checks that the run is the sandbox's own |
| `context.installation.id`                         | The GitHub App installation of a sync run, when it has one                                      |
| `context.proxy.principal`, `context.proxy.api_protocol` | The proxy caller's scope ID and selected API protocol. These contain no credential |
| `context.action.id`, `context.action.kind`, `context.action.attempt` | The outbox action, kind, and attempt count. Every record inside an attempt carries these fields, including adapter records. A workspace action also carries `workspace.id` |
| `context.integration.route`, `context.integration.delivery` | On `integration.message.failed`: provider and event (`github.push`), and the delivery count |
| `context.*`                                       | Every other event field and thread-scoped value                                                  |
| `error.type`, `error.stack_trace`                 | Exception class and frames when a call site includes the exception. Not present on every failure |

Spring Boot writes a dotted name such as `log.level` as nested JSON objects.
Event fields and thread-scoped values sit in the `context` object, so they cannot replace a field of the record.
When an event names a field that the thread-scoped context also holds, for example `job.id`, the record carries the event's value once.
This page names them without the `context.` prefix below.
Read records with a JSON tool, not by text position.
Event-specific numbers and booleans remain JSON numbers and booleans.

A JSON string can contain escaped newlines.
Read logs as JSON, not as extra records from their rendered text.

A stack trace is at most 8192 characters and at most 32 frames for each exception.
Docker splits a log line at 16 KiB, and the pieces are not JSON, so one record must stay below that.
The trace starts with the root cause, and each exception that wraps it follows on a `Wrapped by:` line.
When a long chain of wrappers reaches the limit, the outermost frames are cut, not the root cause.
`error.type` is still the class of the outermost exception.

JSON escaping makes the written trace slightly longer than its limit.
A message that a call site writes has no limit of its own.
Each exception line carries a hash, so you can count repeated failures of one kind.

A record names each exception in a stack trace, including causes and suppressed exceptions, by its class only.
It contains no `error.message`.
A database driver quotes rejected row values in its messages, an HTTP client the request URL, and a parser the input that it rejected.
These boundaries have their own guard:

- The PostgreSQL driver runs with `logServerErrorDetail=false`, so its messages omit the `DETAIL` line that quotes rejected row values.
  The primary message, the SQLSTATE and the constraint name remain.
  A primary message can still quote a value for some kinds of error.
- Hibernate's own log of the driver message for a failed statement, the `org.hibernate.orm.jdbc.error` category, is off.
  The application still logs the exception class and frames, and conflicts still record the SQLSTATE.
- Tomcat's request parser, `org.apache.coyote.http11.Http11Processor`, logs at `WARN` and above only.
  At `INFO` and `DEBUG` it writes the raw target of a request that it cannot parse, query string included.

When the [database migration](./runtime-roles#database-migrations) stops because the changelog fails validation or a changeset precondition fails, its `APPLICATION FAILED TO START` record gives the reason in its message.
An example is a database that does not meet the [database prerequisites](./compatibility-policy#database-baseline-prerequisites).
A changeset precondition that cannot run, or a failed migration statement, quotes the driver message, so its record names the exception class only.

The application writes an exception's class, not its message, into its own log messages.
Sync, provider and webhook failures log the class with the operation, the status where known, and opaque identifiers.
The subject, which names an owner and a repository, stays out of the record.
Workspaces are identified by `workspace.id`. The workspace slug is not a log field.

Provider-event failures identify `integration.operation` and `integration.event.id`.
When a database failure supplies a valid five-character SQLSTATE, the failed and dead-letter records include it as `db.response.status_code`.

The bounded frame list keeps the failure origin and the first application call site, even when framework proxies fill its 32-frame budget.
An omission marker counts the framework frames between the retained sections.
The application section can start in shared tenancy advice.
The operation values are `handle`, `write-fence` and `capture-for-replay`.

Expected inbox write-fence contention uses `integration.message.retry` at `DEBUG`, with its reason and event ID. It returns the event to the inbox without spending its failure budget.

Run production at `INFO`.
`DEBUG` on framework, client or driver packages can write request content, provider responses and container settings that no guard above covers.

The startup event `runtime.roles.configured` records the `runtime.server.enabled` boolean.
It is informational when the server is enabled and warns when it is disabled. A role configuration log is not a readiness probe.

At the shared HTTP boundary, `integration.request.failed` identifies the request method, upstream
host, and exception class without recording the full URL, headers, or exception message.
`database.constraint.conflict` identifies the exception class and a valid five-character SQLSTATE
(`db.response.status_code`) without recording SQL or rejected row values. Both remain warnings.
The response still reports service unavailability or a conflict.

GitLab webhook registration failures expose the workspace and HTTP status in
`integration.webhook.registration.failed`, never the provider's response body. Outline request
diagnostics use `integration.request.failed` with the API operation, host, exception type, and HTTP
status when available. They stay at debug level and do not include raw transport/decoding messages.

When GitLab already refused a connection's credential, each initialization logs `integration.credential.refused` as a warning, with `integration.kind` and `workspace.id`.
Initialization still inspects the token, because that call can see the credential accepted again.
It does not discover projects until then.
The admin sees the same state as an attention problem on the connection.

A deletion sweep that cannot prove the provider's listing complete deletes nothing and logs `integration.deletion_sweep.skipped`, with `integration.kind` and `reason`.
The record is a warning, except for `reason=featureDisabled`.
That reason means that the repository switched its issues or its pull requests off, which is an expected state, so the record is informational.
It also does not give the reconciliation job a warning. The sweep's summary counts these repositories.

When the server cannot read the quotas of the sandbox namespace, it logs `sandbox.quota.unreadable` as a warning, and reviews wait.
When it can read them again, it logs `sandbox.quota.readable` with `duration.ms`, the time that reviews waited.
A short gap also occurs when the watch of the namespace cache reconnects.

When a review sandbox fails, the log records the size of its transcript as `sandbox.transcript.withheld`, not its content.
The transcript holds what the run was asked, what it read and the provider errors that it saw.
No log level writes it.
For a next-day investigation, use the server's bounded debug session transcripts ([retention decision](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0041-compose-1x-kubernetes-2.md#update--2026-10-09-debug-transcripts)).

The formatter masks secret field names (for example `token`, `authorization`, `PRIVATE-TOKEN`, `X-Gitlab-Token` and `privateKey`) and known credential formats.
These formats are bearer and basic credentials, GitHub, GitLab and Slack tokens (including `xoxe-` and `xapp-`), JWTs, credentials in a URL, and signed or token query parameters.
This masking is defense in depth, not redaction.
It does not detect names, email addresses or private text that a call site writes into a message.

Keep raw requests, provider responses, signed URLs, and private runtime text out of logs.
Restrict log access and retention as part of the instance's data-protection policy.
Identifiers such as `workspace.id`, `job.id`, a trace ID or a container ID are not anonymous.
They connect records to a workspace and its people, so they are personal data under the same policy.

The same holds for metrics.
When tracing samples a request, the server attaches its trace ID and span ID to some histograms as exemplars, for example to `http_server_requests_seconds`.
It does this only when a scraper asks for the OpenMetrics format, and with `management.tracing.sampling.probability` above 0.
The chart's [monitoring stack](./monitoring#run-the-monitoring-stack) keeps no exemplars: VictoriaMetrics stores the sample and drops its exemplar.
A Prometheus with exemplar storage keeps them.
Then its retention and access follow the policy for trace IDs.

## Agent-job lifecycle

Lifecycle records contain `event.name`, `job.id`, `workspace.id`, `review.id`, `job.phase`,
`job.outcome`, and `duration.ms`.

| `event.name`         | Emitted when                  | `job.phase` | `job.outcome`                                   | `duration.ms`                 |
| -------------------- | ----------------------------- | ----------- | ----------------------------------------------- | ----------------------------- |
| `agent.job.queued`   | A new job transaction commits | `queue`     | `queued`                                        | `0`                           |
| `agent.job.started`  | A server starts a claimed job | `queue`     | `started`                                       | Creation to start             |
| `agent.job.terminal` | A terminal transition commits | `total`     | `completed`, `failed`, `timed_out`, `cancelled` | Creation to terminal emission |

An attempt that fails before its sandbox starts logs `agent.job.preparation_failed` at `WARN`.
It has no phase, outcome or duration.
`job.preparation.stage` names the stage that failed.
`job.preparation.kind` and `job.preparation.reason` name the kind and the reason of the failure.
`exception.type` and `exception.cause.types` name the exception classes.
`job.result` names what the job does next.

## Review and conversation events

Each record has an `event.name` and `workspace.id`.

| `event.name` | Emitted when | Fields |
| --- | --- | --- |
| `practice_review.stage.ended` | A stage of a review attempt ends | `review.stage`, `review.stage.outcome`, `duration.ms` |
| `practice_review.ended` | A review reaches its terminal status | `review.id`, `review.kind`, `review.status`, `review.reason` |
| `feedback.copy.settled` | Feedback reaches a destination's final state | `feedback.id`, `review.id`, `feedback.destination`, `feedback.state`, `feedback.reason` |
| `mentor.turn.ended` | An attempt of a conversation turn ends | `turn.id`, `turn.attempt`, `turn.outcome`, `turn.surface` (`app`, `slack`, `system`) |

`practice_review.stage.ended` takes `workspace.id`, `job.id`, `job.attempt`, `review.id`, `run.id`, `run.epoch`, `subject.key` and the trace ID from the log context of its attempt. `job.attempt` starts at zero. `run.epoch` identifies the lease claim and starts at one. Join stages by `job.id` and `job.attempt`. Use `review.id` to join attempts of one review, and `run.id` with `run.epoch` to identify the holder.

## Agent run and outbox events

A review attempt runs under an agent run.
Each record that the attempt writes carries `run.id`, `run.epoch` and `subject.key` in its thread-scoped context, next to `job.id`.
Join the records of one run by `run.id`, and the records of one claim by `run.id` and `run.epoch`.
These are fields, not metric labels.

| `event.name` | Emitted when | Fields |
| --- | --- | --- |
| `agent.run.continued` | The reaper replaces an expired composition run and relinks its review in one transaction | `run.id`, `run.epoch`, `subject.key`, `workspace.id`, `run.kind`, `run.next_id` |
| `agent.run.requeued` | The reaper takes back an expired lease, and the run has retries left | `run.id`, `run.epoch`, `subject.key`, `workspace.id`, `run.kind` |
| `agent.run.ended` | The reaper ends a run: its lease expired at its retry limit, or it passed its deadline | The same, and `run.stop_reason` |
| `agent.run.reaper.failed` | The reaper cannot take back one run. It tries the run again on the next pass | The same, and `error.type` |
| `action.attempt.failed` | An outbox adapter threw | `action.id`, `action.kind`, `action.attempt`, `error.type`, and `workspace.id` when the action has a workspace |
| `action.result.late` | An attempt ended after another claim took its action, or after an erasure deleted it. Its result changes nothing | `action.id`, `action.kind`, `action.attempt`, and `workspace.id` when the action has a workspace |
| `action.ended.uncertain` | An action ended `UNCERTAIN`: its write may have reached the provider, and nothing confirms it | `action.id`, `action.kind`, `action.reason`, and `workspace.id` when the action has a workspace |
| `action.batch.outlived` | Outbox attempts of one replica ran longer than their lease. They keep running, and their late results change nothing | `action.claims` |

An outbox event names no person and no payload.
An account email has no workspace, so its events have no `workspace.id`.

## Abnormal sandbox ends

When a sandbox ends abnormally, the server logs `sandbox.exited` with the container, its exit code and the Kubernetes reason from the Pod's status.
For a practice review, the line also carries the last diagnostic lines of the review runtime as `sandbox.stderr.tail`.
Each kept line names one runtime event with counts, flags and closed codes, such as `[pi-runner] tool.failed phase=review turn=1 practice=0 tool=bash reason_chars=42`.
A turn and a practice are named by their positions in the review's practice index.
The line never holds a tool result, a provider's error message, model output, reviewed code, a name, a path or an identifier.
The server keeps only lines whose event, fields and values are in the runtime's diagnostic vocabulary, and counts the others in `sandbox.stderr.dropped`.

A practice review can stop with some practices still without a result, for example when a practice reaches its deadline.
The runtime then keeps the results that it staged and exits with code 78.
The server logs this end at `INFO` as `partial`, not as an error.
`sandbox.review.without_result` names each practice without a result by its position and stop code, such as `practice=9 program_stop=deadline`.
If the attempt staged a practice that no earlier attempt had, the next attempt runs only the open practices and spends no retry.

## Database logs

The bundled PostgreSQL image logs four kinds of events that show contention and maintenance:

| Event | Setting | Line |
| --- | --- | --- |
| A statement waits for a lock for longer than `deadlock_timeout`, 1 second | `log_lock_waits=on` | `still waiting for ShareLock`, then `acquired`, with the holding process and the relation |
| A query writes a temporary file of 10 MB or more | `log_temp_files=10MB` | `temporary file`, with its size |
| An autovacuum or autoanalyze takes 1 second or more | `log_autovacuum_min_duration=1s` | `automatic vacuum` or `automatic analyze`, with the table |
| A checkpoint | `log_checkpoints=on`, the PostgreSQL default | `checkpoint complete`, with its duration |

No line quotes a statement.
`log_min_error_statement=panic` keeps the statement text out of these lines and out of error lines, because the text can hold values from a person's work.
The image never sets `log_statement` or `log_min_duration_statement`.
To find slow statements, read [the statement statistics](./monitoring#read-database-statement-statistics), which hold no parameter values.
An error line can still quote a value in its `DETAIL`, for example the key of a unique constraint violation.

The chart does not expose values for these logging settings.
The log collector of the chart does not read the PostgreSQL container.

## Review drain deadline

`agent.review.drain.expired` means the server reached its configured shutdown bound with leased reviews.
`run.count` gives their count. The run reaper recovers leases after shutdown.
If upgrades repeatedly reach this bound, increase `server.drainTimeoutSeconds` and the Helm timeout.
[Graceful drain](./runtime-roles#graceful-drain) gives the shutdown order.
