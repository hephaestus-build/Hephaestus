# ADR 0080: Provider events go to a PostgreSQL inbox, and the server receives them

**Status:** Accepted
**Date:** 2026-11-10
**Authors:** Felix T.J. Dietrich

**Supersedes:** [ADR 0066](0066-the-webhook-receiver-has-no-database.md), [ADR 0008](0008-webhook-runtime-role.md) and [ADR 0013](0013-no-jetstream-dlq-stream.md). Parts of [ADR 0005](0005-two-role-runtime-via-conditional-on-property.md) (the role list).

**Amends:** [ADR 0079](0079-one-agent-system.md) § Runtime components (no webhook role, no NATS).

**Amended by:** [ADR 0100](0100-the-server-serves-the-web-app.md). The server serves the web app, so the nginx web app image of § The runtime that follows goes.

**Amended by:** [ADR 0102](0102-sync-status-hints-use-postgresql-notifications.md). Sync-status hints also use PostgreSQL; no broker remains.

## Context

The webhook role verifies a provider delivery and publishes it to NATS JetStream.
Each server consumes one durable per workspace and stream, with one unacknowledged message at a time.
PostgreSQL already holds every other fact of an event: the committed handler steps, the dead letters, the replay records and the activation of a workspace.
The state of one event is thus split across two stores.
The recent ingest defects are at that seam (ING-11, ING-12, the reuse of a sequence by a replaced stream, retention that reads the broker, the replay fence).
NATS also adds a second lease model, which [ADR 0079](0079-one-agent-system.md) § Good rules out ("one lease, one claim, one reaper").
It adds a stateful service with its own volume, certificate and credential (SEC-5, SEC-7).
NATS cannot erase the content of one Slack channel or Outline document before the stream ages out.

## Decision drivers

- One lease model, one claim and one reaper for all queued work.
- Erasure reaches every copy of a person's content.
- The fewest stateful services that an operator must run, back up and secure.
- Order per scope and at-least-once handling across server replicas.

## Considered options

1. **Keep NATS JetStream** (ADR 0008, ADR 0066). Reception survives a database outage. All the costs in the context stay.
2. **A PostgreSQL inbox, received by the server (chosen).**
3. **A PostgreSQL inbox, with a separate receiver role.** Rejected: once the receiver needs PostgreSQL, a separate Deployment of the same JAR only keeps reception alive during a server restart. Several server replicas with rolling updates already do that.

## Decision

- The server receives every provider delivery on the existing `/webhooks/**` paths. It verifies the delivery and writes its `provider_event` rows in one short transaction. Then it answers `202`. The in-flight budget and the size limit stay.
- **Fan-out happens at insert.** The receiver writes one row for each workspace whose subscription matches the delivery's subject: a monitored GitHub repository or organization, a GitLab connection, a Slack team, an Outline subscription. An installation event gets one row with no workspace. Thus each workspace has its own copy, its own order and its own retries, and a second workspace on the same organization never loses the event.
- **Deduplication stays, for each workspace.** Each row carries the provider and the delivery key that the provider's `SubjectKeyDeriver.deriveDedupKey` derives today (the `Nats-Msg-Id` of ADR 0008). The unique index is `(provider, delivery_key, workspace_id) NULLS NOT DISTINCT` (PostgreSQL 15 or later), so the installation row with no workspace dedupes too. The insert is `ON CONFLICT DO NOTHING`, and the server answers `202` for a copy. A redelivery thus never gets a second `provider_event` id in a workspace, so `provider_event_step` cannot repeat a handler's effects. The key stays unique for as long as the row exists, which is at least the replay window and so longer than the 3-hour window of ADR 0008.
- **The receiver answers well before the provider gives up.** Slack waits 3 s ([Events API](https://docs.slack.dev/apis/events-api/)), Outline 5 s (`timeout: 5000` in Outline's `plugins/webhooks/server/tasks/DeliverWebhookTask.ts`), GitHub 10 s ([best practices](https://docs.github.com/en/webhooks/using-webhooks/best-practices-for-using-webhooks#respond-within-10-seconds)) and GitLab 10 s by default ([webhooks](https://docs.gitlab.com/user/project/integrations/webhooks/)). The pool's default acquire timeout (45 s, `application.yml`) is far longer. Thus the receive path has its own deadline of at most 2 s. It acquires its connection with a short timeout, and its transaction runs `SET LOCAL statement_timeout`. When PostgreSQL cannot answer in time, the receiver answers `503` at once. Slack retries a failed delivery by itself. GitHub does not, so `GitHubWebhookRedelivery` redelivers its failed deliveries.
- `provider_event` holds the payload, the headers needed for dispatch, the provider, the scope key, the arrival time, the state (`QUEUED`, `LEASED`, `DONE`, `DEAD`), the attempt, `leased_until` and the stop reason. A row that a workspace owns carries `workspace_id` from its first migration. An installation event has no workspace, as today.
- A claim takes the oldest `QUEUED` row of a scope with `FOR UPDATE SKIP LOCKED`. A scope is a workspace and one resource of the subject, such as one pull request, which is finer than today's workspace and stream. A partial unique index allows one `LEASED` row per scope. This is the claim and lease of `agent_run`, on the database clock. A `NOTIFY` wakes an idle claimer.
- **Handler writes are fenced.** A claim raises the row's `lease_epoch`. Each write transaction of a handler first locks its `provider_event` row and checks that it is still `LEASED` with that epoch, as a run's holder does ([ADR 0079](0079-one-agent-system.md)). The `provider_event_step` claim is part of that check.
- The reaper requeues an expired lease. At the attempt budget, the row becomes `DEAD` and keeps its payload. A replay sets `DEAD` back to `QUEUED`.
- `provider_event_step` keys by the event id. Handlers keep their named steps.
- The receiver reads Slack consent and the active Outline routes from PostgreSQL directly. The key-value projections and their rebuilds go. Outline secrets stay derived from the routing secret.
- **Erasure fences in-flight handlers.** Erasure of an account, a channel or a workspace locks and deletes the matching `provider_event` rows, the leased ones included, in its own transaction. A handler that passed its checks before the erasure then fails its fence check in its next write transaction, and it rolls back. For example, the Outline handler exports a document before its write transaction, and that write finds its event gone. Content that a handler had committed before the erasure is erased with the rest. Thus no erased content comes back after the erasure commits.
- A `DONE` row goes after the replay window (default 3 days).
- NATS, its chart templates, its credential, its certificate, the `HostnameVerifiedNatsDataPort` and the webhook role go.

### The runtime that follows

- **The webhook role folds into the server.** With the inbox, a separate receiver keeps nothing alive that the server replicas do not. In 1.0, one JAR has one runtime role, the server, plus the migrate run mode.
- **The webapp keeps its nginx image.** It is stateless, it owns the security headers and the SPA fallback, and it has no open defect. To serve it from the JAR would make the Gradle build depend on the pnpm build and move the headers into Spring. That saves one Deployment, and the cost is not worth it for 1.0.

### Timing

The change lands before the 1.0 tag, in the cutover of [ADR 0070](0070-1-0-is-a-cutover-release.md).
The receiver folds into the server only together with, or after, the server's move to several replicas with rolling updates and a disruption budget.
Today the chart runs one server replica with `Recreate`, so a restart of a folded receiver would stop reception. The fold must not ship before that rollout.
1.0 carries no compatibility for the old path. NATS and the webhook role are deleted, not kept behind a flag.

## Consequences

### Good

- One store holds an event. ING-12 cannot happen, and the activation boundary of ING-11 is a row id.
- One lease model for events, runs and actions.
- An operator runs one stateful service fewer. The backup covers events.
- Erasure reaches event payloads.

### What gets harder

- **A database outage stops reception.** Providers that do not retry lose those deliveries.
  The routine sync repairs lifecycle facts. A GitHub recovery job redelivers failed deliveries through the App API. Slack retries by itself.
  Transient activity that only a GitLab or Outline event carries is lost.
- **Payloads sit in PostgreSQL.** The table needs retention and a size alert. A 25 MB GitHub payload goes to TOAST.
- **The upgrade drops a short gap.** The upgrade stops the receiver, waits until the stream is empty, and starts the new release. Deliveries in the gap are lost. The routine sync repairs the lifecycle facts of the work, but it cannot recover every delivery: transient GitLab or Outline activity in the gap is lost.

## Revisit trigger

- The chart is otherwise final and one Deployment fewer matters to operators. Then serve the webapp from the server.
- Event volume makes the inbox a measurable share of database load.
- An operator needs reception during planned database maintenance that the sync cannot repair.

## Update — 2026-11-14: retries, order, retention and wake-up as built

The inbox shipped with these rules. Where they differ from § Decision or § Consequences, they hold.

- **Failure classes.** A payload that cannot be parsed makes its row `DEAD` at once.
  An operational failure retries without a limit and spends no budget: a transport or database error, or a provider that answers `5xx`, `408` or a rate limit.
  Any other failure spends one of `hephaestus.integration.consumer.poison.max-attempts` (default 10), and the row then becomes `DEAD`.
  A lease that expires spends one too.
- **Backoff.** `available_at` holds a row's next attempt.
  The wait doubles from `poison.base-delay` (2 s) to `poison.max-delay` (5 min), with equal jitter.
  A row that waits for its retry stays the oldest open row of its scope, so the later rows of the scope wait for it.
- **Scope.** The scope key is the workspace, or `installation`, and the subject without its event token.
  A scope is thus one GitHub repository or organization, one GitLab connection, one Slack channel or team, or one Outline subscription, not one pull request.
  All GitHub App installation events share one scope.
  A claim takes the oldest due `QUEUED` row whose scope has no `LEASED` row and no older open row.
- **`DEAD` does not block its scope.** The later rows of the scope continue.
  A replay sets the row back to `QUEUED` as a first attempt, and the row keeps its id.
  It thus runs before the newer open rows of its scope, but after the newer rows that are already `DONE`.
  A replay can apply an old event after newer ones, so handlers keep their stale-write guards.
- **Retention.** A `DONE` row stays for `replay-window` (3 days), so that a provider's redelivery finds it.
  A `DEAD` row keeps its payload for `dead-retention` (30 days), the time for a replay.
- **Wake-up.** The server that stores or replays a row wakes its own claimers after the commit.
  The other servers find the row at their next poll, every 5 seconds.
  No `LISTEN` connection opens, so the chart's connection budget does not change. This replaces the `NOTIFY` of § Decision.
- **Receive deadline.** The receive transaction runs on its own thread with a deadline of 2 s.
  Each statement in it runs with `SET LOCAL statement_timeout` of 1.5 s.
  When the deadline passes, also while the transaction waits for a pooled connection, the receiver answers `503`.
  This replaces the short acquire timeout of § Decision.
  A transaction that commits after its `503` is harmless, because the provider's retry adds no row.
- **Table hygiene.** Retention deletes the ended rows every hour, in one statement.
  Partial indexes keep the claim and the reaper on `QUEUED` and `LEASED` rows.
  Per-table autovacuum settings, batched deletes and partitions wait for the revisit trigger on event volume.
- **Slack.** § Decision and § What gets harder say that Slack retries by itself. That is too broad.
  Slack retries a failed delivery 3 times: almost at once, after 1 minute and after 5 minutes ([Events API](https://docs.slack.dev/apis/events-api/) § Retries).
  When more than 95% of delivery attempts fail within 60 minutes, Slack disables the app's event subscriptions, unless the app receives fewer than 1,000 events an hour.
  An operator then re-enables them in Slack's app management.
  A database outage longer than about 6 minutes thus loses Slack deliveries. The nightly Slack history sync repairs the messages of monitored channels.
