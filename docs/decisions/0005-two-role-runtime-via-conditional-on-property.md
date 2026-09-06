# ADR 0005: Two-role runtime topology via `@ConditionalOnProperty`

**Status:** Accepted
**Date:** 2026-05-20
**Authors:** Server foundations epic (#1097)

## Context

The Hephaestus server runs everything in a single JAR today (HTTP, sync NATS, mentor SSE,
scheduled tasks, agent NATS pull consumer, Docker sandbox). As agent-job workload grows,
we want the option to deploy the worker on a separate pod / node without a refactor — a
deploy-config change only.

Two boundaries matter operationally:

1. **server ↔ worker** — Docker sandbox runtime resource isolation.
2. **server ↔ webhook receiver** — restart independence; push events are not redeliverable.

A draft considered five runtime roles (`api`/`ingest`/`worker`/`mentor`/`scheduler`); the
pressure-test concluded mentor and the webhook receiver live in the same JVM as today, and the
scheduler can stay single-replica. Two roles match the actual operational pressure for this
epic; a third role is anticipated but deferred.

## Decision drivers

- Single JAR ships as either role; deploy config selects which.
- Zero env vars → full monolith boots (DX invariant).
- Composition-layer gating: per-`@Configuration` class, not per-bean.
- `@Profile` is reserved for environments; the existing codebase already uses
  `@ConditionalOnProperty` heavily for capability gates.

## Considered options

1. **Two roles (`server`, `worker`) gated by `@ConditionalOnProperty`** — pragmatic, matches operational reality.
2. **Five roles** (`api`, `ingest`, `worker`, `mentor`, `scheduler`) — speculative; no current operational pressure.
3. **`@Profile`-based gating** — overloads environment-vs-topology semantics.
4. **Multi-module Maven** — premature; doubles build complexity for no current win.

## Decision

Option 1. `core.runtime.RuntimeRole` defines the property keys:

- `hephaestus.runtime.worker.enabled` (default true; **wired**) — gates the worker chain
  via `DockerSandboxConfiguration` and the agent NATS pull consumer (superseded by the 2026-07-21
  and 2026-07-22 updates below)
- `hephaestus.runtime.server.enabled` (default true; **reserved**) — placeholder constant
  for the future server-only role. Not wired in this epic: the server-side bean chain
  (HTTP API, sync NATS, mentor SSE, scheduled tasks, agent dispatch) still loads
  regardless. Wiring a real server-only mode lands when a concrete deploy-split need
  surfaces (likely after the BYO-runner / Kubernetes adapter epics).
- `hephaestus.sandbox.llm-proxy.enabled` — capability flag (not a role), see ADR 0006

Every `hephaestus.runtime.*` gate uses `matchIfMissing=true`, enforced by
`RuntimeRoleBoundaryTest`. The webhook receiver remains in-process under the default profile for
this epic; the third role lands when restart independence becomes a hard requirement.

## Consequences

- Zero env vars → full monolith boots.
- Worker-only deploy is feasible today by setting `hephaestus.runtime.worker.enabled=false`
  on the server pod — the Docker sandbox + agent NATS pull consumer drop out. The
  server JAR remains bit-identical. **Superseded by the 2026-07-22 update below.**
- True server-only mode (HTTP API + sync NATS only) requires the reserved
  `server.enabled` flag to be wired in a follow-up; this epic establishes the
  property-key contract but defers the implementation.
- The split surfaces concrete boundary refactors (AgentNats publisher/consumer split,
  DockerSandbox worker gate, LlmProxy capability gate) — covered in commit messages,
  not duplicated here.
- No `RuntimeSmokeIT` lands in this epic. The role-isolation invariants are enforced
  at compile time by `RuntimeRoleBoundaryTest` (ArchUnit); end-to-end smoke per role
  lands with the deploy-split epic that has a real operational driver.

## Revisit trigger

A real operational need to scale `mentor` independently of `server`; or a third runtime role
becomes load-bearing (e.g., a dedicated webhook receiver pod for restart independence).

## Update — 2026-05-20 (issue #1110)

The revisit trigger has fired. `RuntimeRole.WEBHOOK_PROPERTY` is now wired (third runtime role:
`webhook`), and `RuntimeRole.SERVER_PROPERTY` — reserved by this ADR — is wired for the first
time. `ServerSchedulingConfig`, `NatsConsumerService`, and `WorkspaceStartupListener` are gated
by `SERVER_PROPERTY` so they do not duplicate-run on the dedicated `webhook-server` pod. See
**ADR 0008**.

## Update — 2026-07-20 (issue #1368)

The `hephaestus.sandbox.llm-proxy.enabled` capability flag referenced in the Decision section
above no longer exists — see **ADR 0006**'s 2026-07-20 amendment. The proxy's gate is now derived
from the same job-execution capability expression `AgentJobExecutor` wires on
(`hephaestus.agent.nats.enabled AND hephaestus.runtime.worker.enabled`), not a standalone
property.

## Update — 2026-07-21 (issue #1368)

The "agent NATS pull consumer" named in the Context section, and `hephaestus.agent.nats.enabled`
in the 2026-07-20 update above, no longer exist. The agent job queue moved off NATS JetStream onto
PostgreSQL — each worker replica polls `agent_job` and claims a batch with
`FOR UPDATE SKIP LOCKED` instead of pulling ids off a stream — see **ADR 0025**. The job-execution
capability expression `AgentJobExecutor` and the LLM proxy key off is now
`hephaestus.agent.enabled AND hephaestus.runtime.worker.enabled`: same shape, the left-hand
property renamed. This ADR's "server ↔ worker" boundary and the `hephaestus.runtime.worker.enabled`
gate it establishes are unaffected — only what feeds work to the worker changed. NATS remains
required for webhook ingest (ADR 0008) and SCM/Slack sync, which this change does not touch.

## Update — 2026-07-22 (issue #1368)

Interactive mentor sandboxes run on the application-server replica serving the user's SSE request;
they are worker/sandbox capability, but not queued agent jobs. The LLM proxy therefore follows
`hephaestus.runtime.worker.enabled` alone, while `AgentJobExecutor` retains the two-part
`hephaestus.agent.enabled AND hephaestus.runtime.worker.enabled` gate. This lets operators disable
practice reviews without disabling mentor and makes the split topology explicit: application-server
keeps local Docker capability for mentor; dedicated workers claim queued practice jobs.

**This supersedes the Decision and Consequences sections above on two points.**

1. What `hephaestus.runtime.worker.enabled` gates is now `DockerSandboxConfiguration`,
   `AgentJobExecutor` and `LlmProxyController`. `AgentNatsConsumerConfig`, named in the Decision
   section, no longer exists — the agent queue is PostgreSQL (ADR 0025).
2. **Do not set `hephaestus.runtime.worker.enabled=false` on an application-server pod.** The
   Consequences section offers that as the way to reach a worker-only deploy; it is no longer safe.
   Because `LlmProxyController` gates on this property alone, turning it off also removes the
   in-process LLM proxy and the local Docker capability the interactive mentor sandbox needs — and
   the proxy is the only path a sandbox has to a provider key (ADR 0006). Mentor chat then fails at
   the first turn, with nothing in the application-server's own configuration naming the cause.

   To run application-server without executing queued practice jobs, set `hephaestus.agent.enabled=false`
   and leave `hephaestus.runtime.worker.enabled` at its default. `AgentJobExecutor` needs both, so it
   drops out; the proxy and mentor sandbox keep running. Note that `hephaestus.agent.enabled=false`
   also stops job *submission* and orphan recovery on that pod — see `docs/admin/runtime-roles.mdx`.
   A pod that must have no Docker capability at all is a different deployment shape than this ADR
   describes and needs a real server-only role, still unbuilt.

## Update — 2026-08-22 (a setting must reach a container that can read it)

This ADR establishes that a role decides which beans exist in a container. It says nothing about the
configuration those beans read, and that gap has a failure mode of its own.

A `@ConfigurationProperties` record read only by role-gated beans is, on a container running with
that role off, a variable nothing binds. Compose sets it, `docker inspect` shows it, a runbook quotes
it — and it configures nothing, with no signal anywhere. It surfaced when a disk bound for the
webhook streams (ADR 0008) was delivered to `application-server`, which runs with
`hephaestus.runtime.webhook.enabled=false`: the bound and its documented recovery procedure were both
inert.

**Decision.** `scripts/check-env-roles.ts` fails the build on three shapes of the same defect; its
header is the one home for the full set it checks:

| Failure | What it catches |
|---|---|
| misdelivered | a service sets a variable whose owning role that same service disables |
| undelivered | the deployment forwards it, but no container running the owning role receives it |
| unforwarded | `application.yml` offers it as a `${VAR:default}` knob no service forwards at all |

Ownership is declared per `application.yml` path, not per property record, because it is finer than a
record: `hephaestus.webhook.secret` is read on the server role for outbound registration while
`hephaestus.webhook.stream.*` is read on the webhook role, both out of `WebhookProperties`. A scope
naming a path `application.yml` no longer has fails too, so a rename cannot leave an entry behind
that silently checks nothing. A role a *profile overlay* switches off counts as off, so
`application-worker.yml` is read alongside each service's environment.

The gate runs in the `App Server` quality job, not only in the pre-push hook that `--no-verify`
skips — a gate written for a defect that reached production has to be able to fail a required build.

**Consequences.** Adding a role-gated property block now means adding a `ROLE_SCOPES` entry; leaving
it out means the check passes by assuming the property is readable everywhere, which can miss a
defect but never invent one. Compose is parsed directly rather than through `docker compose config`,
so the gate needs no Docker daemon and its verdict does not depend on the ambient environment — what
the shipped topology does with nothing set is the question.

**Revisit trigger.** A deployment shape whose roles are not decided by Compose environment plus
Spring profiles — a Kubernetes adapter with its own templating, or a BYO runner — makes reading the
Compose files the wrong way to answer the question, and the gate has to move to whatever declares the
topology instead.

## Update — 2026-09-03 (issue #1719)

[ADR 0041](0041-compose-1x-kubernetes-2.md) supersedes the Decision's deferral of further roles to
the BYO-runner and Kubernetes-adapter epics; the 2026-05-20 update above already recorded the third
role landing as ADR 0008. ADR 0041 fixes the steady-state role set, and the BYO-runner epic the
Decision defers to is withdrawn. The Compose role declarations are deleted in 2.0.
