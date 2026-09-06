# ADR 0008: Webhook as a third runtime role (`webhook-server` container)

**Status:** Accepted
**Date:** 2026-05-20
**Authors:** Webhook substrate epic (#1110)

## Context

Two operational facts force the design:

1. **Push events on GitHub and GitLab are NOT manually redeliverable.** Both providers expose
   a redeliver button in the UI, but it is documented to NOT apply to push events. A webhook
   drop during an `application-server` restart is permanent data loss.
2. **App-server restarts are frequent and unavoidable.** Deploys, OOM crashes, config flips,
   schema migrations — all routinely cycle the JVM. The receiver must survive each one.

Restart independence between `application-server` and the webhook receiver is therefore a hard
operational requirement, not a nice-to-have. ADR 0005 anticipated this and listed
*"webhook becomes a third runtime role"* as its explicit revisit trigger.

## Decision drivers

- **Single artifact:** ADR 0005's principle was *"single JAR ships as either role; deploy config
  selects which."* A third role must compose with the existing two without forking the artifact.
- **Default-on monolith:** ADR 0005's DX invariant — zero env vars boots the full stack — must
  hold. `bun run dev` starts one process that handles everything.
- **No multi-Maven-module split:** ADR 0005 rejected this. We honour that.
- **Cycle through NATS, not direct coupling:** the publisher and the sync consumer agree on the
  subject grammar; nothing else couples them.

## Considered options

1. **Same JAR, third runtime role, separate container** *(chosen)* — Spring profile `webhook`
   activates `WebhookConfiguration`; production deploys two containers from one image.
2. **In-process webhook handler.** Operationally unacceptable: an app-server restart drops
   webhooks. Push events lost permanently.
3. **Multi-Maven-module.** ADR 0005 rejected this for "premature complexity." Still true.

## Decision

Option 1. Concretely:

- A new constant `RuntimeRole.WEBHOOK_PROPERTY = "hephaestus.runtime.webhook.enabled"`. Defaults
  to `true` (matchIfMissing) so the monolith DX invariant holds.
- A new Spring profile `webhook` with overlay file `application-webhook.yml` that sets
  `server.enabled=false`, `worker.enabled=false`, `webhook.enabled=true`, and disables Liquibase
  on the receiver pod.
- A new Docker container `webhook-server` running `ghcr.io/ls1intum/hephaestus/application-server`
  with `SPRING_PROFILES_ACTIVE=prod,webhook`. Traefik routes `/webhooks/*` (strip-prefix) to this
  container's `/gitlab` and `/github` endpoints. The `application-server` container sets
  `HEPHAESTUS_RUNTIME_WEBHOOK_ENABLED=false` so the receiver beans don't load there.
- The pure verifier/builder classes (`HmacVerifier`, `GitLabTokenVerifier`, `GitLabSubjectBuilder`,
  `GitHubSubjectBuilder`, `DedupIdResolver`) live in
  `de.tum.cit.aet.hephaestus.gitprovider.webhook`. JaCoCo package-coverage gate ≥ 0.95 branch on
  this package keeps subject grammar, dedup ID derivation, HMAC verification, and hex encoding
  honest under refactor.
- `WebhookConfiguration` reuses the existing `Connection natsConnection` bean from
  `config.NatsConfig` — no new NATS connection per pod. Publisher uses Resilience4j Retry with
  exponential backoff + ±25% jitter; stream creation is `addStream`-or-`getStreamInfo` with
  WARN-on-drift (never `updateStream`).

  > Superseded in part by the 2026-08-22 update, §"Limits may be corrected in place; shape still
  > may not": the *limit* fields are now reconciled on an existing stream. Subjects, retention,
  > storage and discard are still never written.
- The previously-reserved `RuntimeRole.SERVER_PROPERTY` is **wired** for the first time. It
  gates `ServerSchedulingConfig` (a new `@Configuration` carrying `@EnableScheduling`, extracted
  from `Application.java`), `NatsConsumerService`, and `WorkspaceStartupListener`. This is the
  minimum surface required to prevent duplicate-run pathologies (cron schedulers, durable NATS
  consumers, workspace bootstrap) when the same JAR runs as two containers.

  > `NatsConsumerService` was renamed `IntegrationNatsConsumer` in the ADR 0015 Phase 1-4
  > restructure. The `SERVER_PROPERTY` gate this ADR establishes is on that class today; nothing
  > about the decision changed.
- `WebhookProperties` is moved to `core/webhook/` with a `@NamedInterface("webhook")` declaration
  so both `workspace.GitLabWebhookService` (auto-registration) and `gitprovider.webhook.*`
  (inbound receiver) can depend on it without forming a cycle. Nested `TokenRotation`, `Publish`,
  `Stream`, `Shutdown`, and `Http` blocks sit under the existing `hephaestus.webhook.*` prefix.
  Incoming-request size is capped by `WebhookPayloadSizeFilter` (rejects requests whose declared
  `Content-Length` exceeds `hephaestus.webhook.http.max-payload-bytes` with
  `413 Payload Too Large`) — Servlet `max-http-post-size` only applies to form-encoded payloads,
  not the `application/json` bodies this receiver accepts.
- ArchUnit boundaries: `HexEncodingArchTest` forbids `Integer.toHexString` /
  `Long.toHexString` inside `..gitprovider.webhook..`; `HexFormat.of()` is the only approved hex
  source. `LocaleSafetyArchTest` forbids no-arg `toLowerCase`/`toUpperCase` and
  `Locale.getDefault()` inside the same scope. `RuntimeRoleBoundaryTest` asserts the correct
  property gates on `WebhookConfiguration`, `ServerSchedulingConfig`, `NatsConsumerService`, and
  `WorkspaceStartupListener`, plus role isolation (webhook package cannot depend on
  workspace/leaderboard/agent) and `@EnableScheduling` uniqueness (only on
  `ServerSchedulingConfig`).

## Consequences

- **Restart independence achieved.** Restarting `application-server` no longer interrupts
  webhook reception; the sync consumer catches up on the buffered JetStream messages once it
  comes back. Webhook drops during an `application-server` restart are zero.
- **Single-artifact CI/build cost.** One Maven build, one Docker image, no separate pipeline.
- **Schedulers cannot duplicate.** `@EnableScheduling` is extracted into
  `ServerSchedulingConfig` gated by `SERVER_PROPERTY=true`. The `webhook-server` container sets
  `server.enabled=false` and consequently fires no `@Scheduled` methods — preventing GitHub and
  GitLab sync schedulers, agent zombie sweepers, mentor in-flight reaper, rate-limit eviction,
  contributor cache eviction, and the GitLab webhook health check from double-running.
- **Sync consumer cannot duplicate-register.** `NatsConsumerService` is gated by
  `SERVER_PROPERTY`. Two containers cannot race the same durable consumer name.
- **Workspace bootstrap cannot race.** `WorkspaceStartupListener` is gated by
  `SERVER_PROPERTY`; only the application-server pod runs PAT workspace creation and GitHub App
  installation activation.
- **Liquibase migrations remain owned by application-server.** The webhook profile sets
  `spring.liquibase.enabled=false`. No race between the two pods at boot.
- **DB connection retained on the webhook pod.** Cheaper than gating every workspace-context
  bean; the receiver itself never touches the DB. Operational cost: ~150 MB extra heap and one
  Hikari pool — acceptable.
- **Endpoints `/gitlab` and `/github` (no `/webhooks` prefix on controllers).** Traefik already
  strips the `/webhooks` prefix; preserving the controller paths keeps existing provider
  registrations valid (GitLab rate-limits webhook re-registration at 5 req/min per group).
- **Webhook secret remains a single shared value (`hephaestus.webhook.secret` /
  `WEBHOOK_SECRET`).** Used identically by auto-registration (sent to the provider) and
  verification (incoming HMAC / token). No env var rename.

## Residual risks accepted

The two-container design eliminates the application-server-restart class of webhook drops, but
the receiver still has known failure windows that we are NOT solving here:

- **`webhook-server`'s own restart.** A SIGTERM during in-flight publish runs the
  `WebhookGracefulShutdown` drain (`hephaestus.webhook.shutdown.drain-timeout`, default 15s)
  against an HTTP server already declared closed. Docker's `stop_grace_period: 40s` covers
  Spring's HTTP drain + the NATS drain + margin (formula:
  `stop_grace_period ≥ server.shutdown drain + shutdown.drain-timeout + 5s`). POSTs that arrive
  AFTER Traefik routes away from the dying pod but BEFORE the new pod is in rotation hit a brief
  outage window. Multi-replica + rolling restart closes this; deferred (see Revisit trigger).
- **NATS unavailability.** Resilience4j retry (5 attempts, ±25% jitter, 9s total) absorbs short
  blips. Beyond that the controllers return 503 → provider retries per its own schedule. Push
  events on GitHub/GitLab are NOT in the provider retry set, so a sustained NATS outage drops
  pushes. Mitigation: NATS uptime SLO (operationally tracked, not gated here).
- **Stream config drift.** `WebhookJetStreamBootstrap` is `addStream`-or-`getStreamInfo` and never calls
  `updateStream`. If a future config change widens `duplicate-window` or `maxAge` in the source,
  an operator must apply it manually via `nats stream edit`. WARN-on-drift surfaces the divergence
  in logs; the receiver continues to publish into the pre-existing stream.

  > Superseded by the 2026-08-22 update, §"Limits may be corrected in place; shape still may not".
  > This risk is no longer accepted for limits; it stands unchanged for shape.
- **DataSource on the webhook pod.** The workspace-context filter is wired unconditionally and
  requires a JPA `DataSource` bean. The receiver itself never writes to the DB; the connection
  exists only to satisfy bean wiring. Followup: make the workspace-context filter
  profile-conditional so the webhook pod can boot without Postgres.

## Observability

> Superseded on two points by the 2026-08-22 update, §"`webhook` joins the readiness group" and
> §"What is measured is loss, not proximity to it": the `webhook-server-down` alert below reads a
> probe that did not contain the `webhook` indicator, and the instrument worth paging on is now
> `webhook.stream.unacknowledged.deletions`. The `webhook.*` publish counters are unchanged.

The receiver exposes Micrometer instruments under the `webhook.*` namespace:

- `webhook.publish{outcome=success|failure}` — counter per NATS publish attempt outcome. The
  receiver's primary SLI.
- `webhook.publish.retry` — counter per Resilience4j retry event. Spike indicates NATS pressure
  or transient broker errors.
- `webhook.rejected{provider, reason}` — counter for every request rejected before publish
  (bad signature, bad token, oversize, invalid JSON, etc.). Lets ops separate scan traffic from
  misconfigured providers.
- Spring Boot's standard `http.server.requests` covers per-endpoint latency / status counts;
  no webhook-specific HTTP histograms are added.

Recommended SLO and alert (operational, defined in the observability stack):

- **SLO:** `rate(webhook.publish{outcome=failure}) / rate(webhook.publish) < 0.01` over a 5-min
  window.
- **Alert — `webhook-server-down`:** `/actuator/health/readiness` returns DOWN for ≥ 60s →
  page on-call. The health indicator goes DOWN on NATS disconnect or unreachable streams.
- **Alert — `webhook-publish-failing`:** SLO breach for ≥ 2 minutes → page on-call.

Dashboards and alert wiring live outside this PR (observability epic).

## Revisit trigger

A real operational need to scale `webhook-server` independently of replica count (e.g., > 50 RPS
sustained per pod) — at which point multi-replica + sticky-cookie + JetStream dedup window
review is appropriate. The `duplicate-window` is currently 2 minutes (JetStream default;
provider redelivery uses the same delivery-UUID so a wider window only protects against
adversarial replays).

> Superseded on the dedup window: it is 10 minutes, floored at the widest per-vendor timestamp
> tolerance (`WebhookProperties.Stream.REPLAY_TOLERANCE_FLOOR`, 5 minutes — GitLab `whsec` and
> Slack v0). "A wider window only protects against adversarial replays" does not hold for GitHub,
> whose deliveries carry no timestamp at all, so for them the window is the only replay defence.
> The scaling trigger itself stands.

## Related

- ADR 0001 — flat top-level layout (updated by this ADR: see "Update" section).

  > The "Update" section this points at was never written. What it would have said is in the
  > Decision above: `core/webhook/` with a `@NamedInterface("webhook")` declaration, so
  > auto-registration and the inbound receiver can share `WebhookProperties` without a cycle.
- ADR 0005 — two-role runtime baseline (revisit trigger fired here; webhook is the third role).
- ADR 0006 — LLM proxy on coordinator (unaffected).
- ADR 0007 — sandbox SPI shape (unaffected).

## Update — 2026-08-22 (webhook ingestion cannot fail silently)

*Written after an incident: a deployment's disk reached 0 bytes free, NATS could not write, and every
inbound GitHub webhook was dropped for hours behind an HTTPS endpoint answering 200 and a readiness
probe answering UP. Nothing above is deleted; the sections this supersedes are named.*

The receiver had every part of a working safety story and no path connecting them. The invariant this
update adds is that **webhook ingestion must not be able to fail silently**, and each decision below
is one place the original design allowed it to. They share that one invariant, which is why they are
one update rather than six:

1. `webhook` joins the readiness group — *supersedes "Observability"*
2. Two bounds, and which one binds is a function of volume — *new*
3. Limits may be corrected in place; shape still may not — *supersedes "Stream config drift"*
4. The broker cannot be asked to hold more than its volume — *new*
5. What is measured is loss, not proximity to it — *supersedes "Observability"*
6. A payload the receiver accepts must be one the broker will carry — *new*

A seventh decision made in the same work — that a role-gated setting must reach a container running
its role — is not about webhooks. It amends **[ADR 0005](0005-two-role-runtime-via-conditional-on-property.md)**,
which owns runtime roles, and the receiver's stream bounds are one instance of the class it names.

### `webhook` joins the readiness group (supersedes "Observability")

The `webhook-server-down` alert above specifies `/actuator/health/readiness` returning DOWN, and
`WebhookHealthIndicator` reports DOWN correctly — but nothing put it in a probe. The group was written
under `management.health.group`, which is not a property: health groups bind to
`HealthEndpointProperties` at `management.endpoint.health.group`, and Spring drops an unbound key in
silence. `/actuator/health/readiness` was therefore the auto-configured probe group, `readinessState`
alone, on every container. On the webhook-only container that is "the Spring context started", and
both the container healthcheck and Traefik's `loadbalancer.healthcheck.path` read it, so the outage
this ADR exists to catch answered 200 throughout. The group is now at the key that binds, and
`WebhookIngestionCannotFailSilentlyTest` asserts it through the same health autoconfiguration a
deployment runs, so a key that binds to nothing fails the build rather than passing it.

The objection to including `webhook` is that a NATS blip would then pull a *combined-role* container
out of Traefik's rotation, taking the SPA and API down with ingestion. That exposure already exists
and was already accepted: `integrationConsumer` is in the same group and reports DOWN on the same
disconnect.

**`management.endpoint.health.validate-group-membership` is set to `false`, deliberately.** It
defaults to `true`, and a group naming a contributor that is not a bean does not report UP — it throws
`NoSuchHealthContributorException` and aborts the refresh. Every name in this group except
`readinessState` is role-gated, so with validation on, `application-server` (webhook role off),
`webhook-server` (server role off, so no `integrationConsumer` and no
`catalogProvenanceBackfillStartup`), any deploy with NATS off, and the `specs` and `test` profiles all
fail to start. The alternative is to register each indicator unconditionally with an explicit
role-inactive branch — the shape `PracticeReviewHealthIndicator` already uses — but `webhook` cannot
take it without a variant that exists with no NATS `Connection` bean, which is a larger change than
the flag buys. What the flag gives up is a spelling check on the member names, and that check now
lives in the test, where it runs against every role at once instead of each container against only
its own.

A separate probe path was considered and rejected. An empty group is not a cheerful UP — the endpoint
returns 404 — but a path that 404s on the container that was never given the webhook role is a probe
that reports nothing where ingestion is most likely to be missing, and one probe that answers for the
container it is served from is easier to alert on than two that answer differently per role.

### Two bounds, and which one binds is a function of volume (supersedes nothing above; new)

`max-messages: 2000000` was the only bound on a stream. A count describes neither disk nor time — the
disk cost of a message is the provider's payload size — and 2,000,000 GitHub deliveries measured
32.3 GB. The count bound is removed rather than supplemented, because the deployment had three
plausible bounds and its real behaviour was governed by the one that meant least.

What remains is two bounds. `max-age` is the retention **ceiling**: 180 days, the longest a delivery
is kept if disk allows. `max-bytes` is the disk-safety **floor** under it. At development volumes
nothing approaches the byte bound and the full 180 days is delivered, which is worth having locally
and costs nothing; at reference-deployment ingest the byte bound binds first and decides retention.
Neither number is a lie — what was a lie was stating one of them and letting the other be discovered
when the disk filled.

**No number here says how many days a deployment gets, and none should.** That quotient is measured
ingest rate divided by a bound, and a rate measured over one incident window on one deployment does
not predict the next month, let alone another operator's traffic. It is published instead:
`webhook.stream.oldest.message.age{stream}` is the effective retention of the stream in front of you.

`max-bytes` is sized against **what a shed message costs**, not against `max-age`. Nightly
`SyncJobType.RECONCILIATION` re-fetches from the provider API over `hephaestus.sync.timeframe-days`,
so inside that window a shed webhook is recoverable by other means and outside it, by nothing. The
GitHub bound must exceed the measured peak rate times that window, and
`WebhookIngestionCannotFailSilentlyTest` fails the build if it stops doing so. The coupling it
enforces does not exist in the code — nothing reads one setting from the other — which is exactly why
it is written down somewhere that runs.

Streams carrying personal message content stay far below the ceiling regardless of volume: SQL is the
system of record and GDPR erasure cannot reach inside a broker stream.

`discard` stays `Old`. Neither policy is lossless at the bound: `Old` sheds the oldest retained
messages, `New` rejects the publish and destroys a message nobody has seen while stopping ingestion
for every workspace at once — the outage this exists to prevent.

### Limits may be corrected in place; shape still may not (supersedes "Stream config drift")

The residual risk above accepted `addStream`-or-`getStreamInfo` and never `updateStream`. That
reasoning holds for the fields deciding what a stream *is* — subjects, retention, storage, discard —
and they are still never written: the update is built over the live configuration, so anything
unnamed survives verbatim. What the policy also blocked was correcting a **bound**, which left the
disk-safety limit this update adds unreachable on every stream that already existed.

| Situation | Startup does |
|---|---|
| Change cannot delete anything | Applies it, logs at INFO |
| Change would delete stored messages | Withholds it, logs at ERROR with exactly what it would cost |
| …and `allow-destructive-limit-updates` is set | Applies it, logs what was deleted |
| Change would leave the stream with no bound at all | Withholds it, logs at ERROR — no flag overrides this |
| Stream state unavailable | Withholds everything — nothing can be proven safe |
| Live shape has drifted | Withholds everything, logs at ERROR |

The fourth row is the one that is easy to get wrong. Dropping `max-messages` only ever admits more, so
a loss estimate says it costs nothing and it looks unconditionally safe. But every existing deployment
arrives at reconciliation bounded **only** by that count, and the same pass can withhold the byte
bound meant to replace it — a stream at 32.3 GB gets its 2,000,000-message cap deleted and nothing put
back. So the release is conditional on a byte bound being in force afterwards, and
`allow-destructive-limit-updates` does not reach it: that flag licenses deleting stored messages, not
running a stream with nothing holding it back.

The last row is not caution for its own sake either. A byte bound written onto a stream whose live
`discard` is `New` makes the broker reject publishes rather than shed old messages, so the
reconciliation meant to prevent an outage would cause one.

### The broker cannot be asked to hold more than its volume

`NATS_JS_MAX_FILE` defaulted to `50G` with no relation to the volume, and the per-stream bounds had
no relation to it either. The distinction that matters: JetStream that fills **its own budget**
refuses new messages and recovers by itself, while JetStream that fills the **filesystem** cannot
write its own metadata and stays wedged after space is freed — which is why the incident needed a
human to restart NATS after the disk was cleared. One value now sets both the broker's `max_file` and
the application's `storage-budget`, and startup refuses to run if the stream bounds sum above it.

This makes ENOSPC unreachable **by webhook stream growth alone**, which is the claim the arithmetic
supports and no more. The check is arithmetic over configuration, so it holds only while the
configured value tracks the volume's real free space; and `max_file` governs the whole JetStream
store, not just the four bounds it is compared against, so anything else sharing the volume can still
fill it.

A supervisor that restarts an unhealthy NATS container was considered and **rejected**. Docker does
not act on a failing healthcheck, which is why 340 consecutive failures changed nothing, but the
remedy is not a restart loop: against a genuinely full disk it produces a container flapping through
JetStream recovery instead of one wedged container and a clear signal. The wedge is a consequence of
ENOSPC, so it is addressed where it is caused. If the failure recurs with the budget chain in place,
that conclusion is wrong and a supervisor is the next step.

### What is measured is loss, not proximity to it

`webhook.stream.bytes.utilization` is published and kept, but it is not the signal to alert on: at
steady state it goes flat and stops carrying information, and a threshold warning fires once into a
log. `webhook.stream.unacknowledged.deletions{stream}` compares the stream's first stored sequence
against each consumer's ack floor. A message below the first sequence that a consumer never
acknowledged was deleted before anyone read it, which is irrecoverable webhook loss — a counter, zero
unless something is genuinely wrong, logged at ERROR with the consumer named.

- `webhook.stream.bytes{stream}`, `webhook.stream.bytes.limit{stream}`,
  `webhook.stream.bytes.utilization{stream}`, `webhook.stream.messages{stream}` — capacity, for a
  dashboard.
- `webhook.stream.oldest.message.age{stream}` — effective retention, in seconds. `max-age` is a
  ceiling and `max-bytes` a floor, so this is the only place the answer for a given deployment exists.
- `webhook.stream.consumers{stream}` — durables on the stream. A deployment deleted rather than shut
  down never removes the durables it created, so a count that climbs and never falls is consumers
  leaking onto a shared broker. `hephaestus.integration.consumer.inactive-threshold` now defaults to
  30 days rather than never, which reaps them.
- `webhook.stream.unacknowledged.gap{stream}` — the standing gap, worst consumer.
- `webhook.stream.unacknowledged.deletions{stream}` — the loss counter.
- `webhook.stream.poll.age{stream}` — seconds since the loss counter was last maintained.

**Alert — `webhook-ingestion-lost`:** any increase in `webhook.stream.unacknowledged.deletions` → page
on-call. This supersedes the near-bound warning as the thing worth waking someone for. Pair it with
`webhook.stream.poll.age`: a monitor that cannot read the broker leaves the counter flat at zero,
which reads exactly like no loss.

Two constraints keep that counter worth paging on, and both are about a **shared** broker. Loss is
charged only to durables under this deployment's own `NATS_DURABLE_CONSUMER_NAME`: preview stacks set
their own on staging's broker and are deleted rather than shut down, so an abandoned durable sits
behind `firstSequence` permanently and would otherwise increment the counter at ERROR on every poll
until it is reaped. And every meter is tagged by stream alone and registered once at construction, so
the series count is fixed at four — a tag per consumer is one permanent time series per preview stack
that ever existed.

### A payload the receiver accepts must be one the broker will carry

NATS' `max_payload` defaults to 1 MB and the receiver admits 25 MiB. Every delivery between the two
was verified, accepted, and then refused at publish. The broker is now configured to the same number,
and the receiver reads the live limit at startup and logs at ERROR if they disagree.

25 MiB is above NATS' own practical recommendation of 8 MB, and that is a deliberate choice rather
than an oversight. GitHub caps a webhook delivery at 25 MB, so the receiver's limit is what the
provider can actually send; lowering it would move the loss from the broker to the edge without
removing it. The costs are real and named here so the next person does not have to rediscover them:
one maximum-size message is 2.4 % of the smallest shipped stream bound, 409 of them fill the GitHub
one, and `max_pending` is left at its 64 MB default, so a subscriber two or three maximum-size
messages behind is disconnected as a slow consumer. The webhook consumers are pull-based and
JetStream-backed, which is why that has not bitten, but a future push subscription on these subjects
has to raise `max_pending` with it.

### Revisit trigger for this update

Any of these says one of the six decisions above was wrong:

- **`webhook.stream.unacknowledged.deletions` increments on a deployment inside its configured
  bounds.** The byte bound is sized against the reconciliation window, not against consumer lag; if
  loss shows up anyway, the sizing rule is the wrong rule.
- **ENOSPC on the JetStream volume recurs with the budget chain in place.** The supervisor that
  restarts an unhealthy NATS container was priced and rejected above on the argument that the wedge
  is a consequence of ENOSPC and is fixed where it is caused. A recurrence refutes that.
- **An operator sets `allow-destructive-limit-updates=true` and leaves it set.** A flag meant for one
  deliberate start-up that becomes deployment state means the withhold-and-log design is asking for a
  decision at the wrong moment.
- **A second consumer family lands on these streams.** The loss accounting compares one ack floor per
  durable against one first sequence per stream; fan-out to consumers with different retention needs
  is a different model.

## Operator documentation

The metric roster, the destructive-update decision table and the recovery procedure for a wedged
broker are published for self-hosters at
[Webhook ingestion operations](https://docs.hephaestus.build/admin/webhook-ingestion-operations).
This ADR records why; that page records what to do.
