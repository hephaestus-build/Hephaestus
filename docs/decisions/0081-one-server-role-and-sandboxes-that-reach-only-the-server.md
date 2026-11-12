# ADR 0081: One server role runs Hephaestus, and agent work runs in sandboxes that reach only the server

**Status:** Accepted
**Date:** 2026-11-10
**Authors:** Felix T.J. Dietrich

**Supersedes:** [ADR 0005](0005-two-role-runtime-via-conditional-on-property.md), [ADR 0006](0006-llm-proxy-on-coordinator-trust-model.md), [ADR 0009](0009-worker-runtime-substrate-wss-control-channel.md), [ADR 0025](0025-agent-job-queue-on-postgresql.md), [ADR 0036](0036-agent-runtime-runs-on-node-24.md), [ADR 0068](0068-reviews-and-heph-run-on-one-agent-runtime.md), [ADR 0074](0074-a-reviews-width-and-count-follow-the-provider.md)

**Builds on:** [ADR 0079](0079-one-agent-system.md), [ADR 0080](0080-provider-events-go-to-a-postgresql-inbox.md), [ADR 0070](0070-1-0-is-a-cutover-release.md)

## Context

Seven ADRs described the runtime, and each one carried a chain of updates.
ADR 0005 alone had 11, and ADR 0068 had 7 plus edits in place.
To learn what runs in 1.0, a reader had to replay the chain and then subtract [ADR 0079](0079-one-agent-system.md) and [ADR 0080](0080-provider-events-go-to-a-postgresql-inbox.md).
This ADR states the runtime at 1.0 as rules.
ADR 0079 keeps the design of the agent system, and ADR 0080 keeps the design of provider events.

1.0 is a cutover ([ADR 0070](0070-1-0-is-a-cutover-release.md)).
A rule that main does not satisfy yet says "1.0 target" and names the pull request that delivers it.

## Decision drivers

- One of each: one runtime role, one lease model, one model path and one outbox.
- Only one process holds provider keys and Kubernetes authority.
- A sandbox runs code that a model wrote. Its boundary must hold even if the code is hostile.
- An operator does not tune for a provider's limits.

## Considered options

1. **Keep the worker role, and move only the proxy.** The worker holds the claim, the proxy, the sandbox lifecycle and the Kubernetes client. A second role keeps a second deployment, a second registry and a drain protocol.
2. **One server role with agent-sandbox sandboxes (chosen).** The server takes the worker's concerns. Sandboxes are the only other workload. [ADR 0079](0079-one-agent-system.md) § Considered options prices this choice against a shared loop host, a workflow engine and Kueue.

## Decision

1. **One runtime role.** The JAR runs as the server, with N replicas and rolling updates, and as the `migrate` profile in the migration Job. The server also serves the web app. There is no worker role, no webhook role, and no web app container or nginx.
   ADR 0100 ([#4135](https://github.com/hephaestus-build/Hephaestus/pull/4135)) owns how the server serves the web app. It also owns the path map. The server answers `/api/**`, `/webhooks/**` and `/.well-known/**`. The gateway port answers `/internal/**`. Every other path is the web app.
   [#4079](https://github.com/hephaestus-build/Hephaestus/pull/4079) removed the worker role. 1.0 target: [#4100](https://github.com/hephaestus-build/Hephaestus/pull/4100) removes the webhook role. No pull request yet removes the web app image, the Gateway `/api` rewrite and `hephaestus.auth.api-base-path`, as ADR 0100 decides.
   Enforced by: `RuntimeRoleBoundaryTest`, `scripts/check-env-roles.ts` and the rollout assertions in `scripts/kubernetes-chart-render.test.ts`.
2. **Provider events** go to a PostgreSQL inbox in the server, as [ADR 0080](0080-provider-events-go-to-a-postgresql-inbox.md) decides.
3. **Agent work is a row.** A run is an `agent_run` row. A claim takes it with `FOR UPDATE SKIP LOCKED`. The claim sets a lease on the database clock and a new lease epoch. One reaper requeues an expired lease. Every write of a run is fenced on its epoch.
   Enforced by: `AgentRunsIntegrationTest` (a stale claim, an expired lease, a second leased run of one subject) and, for conversation turns, `HephTurnsIntegrationTest#shouldClaimAQueuedTurnAndHandOutATokenWhoseWorkEndsAtTheAdmittedTimeout`.
4. **Sandboxes:** one agent-sandbox `Sandbox` for each subject, as [ADR 0079](0079-one-agent-system.md) § Subject and sandbox decides.
5. **Only the server holds model keys.** A sandbox holds a token for its own subject, and its egress reaches only the server's gateway port 8081. Every model call passes the server's LLM proxy, which counts its usage against its run.
   1.0 target: [#4214](https://github.com/hephaestus-build/Hephaestus/pull/4214) moves the product feedback draft onto the proxy. On main, `ProductFeedbackModelAdapter` calls the provider directly. `LlmModelCapabilityProbe` also calls providers directly, and no pull request moves it yet.
   Enforced by: `LlmProxySecurityConfigTest` for the 404 rules of both ports. The chart test "network policy denies by default and names exact peers" and `scripts/sandbox-isolation.test.ts` check the egress.
6. **The sandbox Pod is restricted.** It meets the `restricted` Pod Security Standard of Kubernetes. It runs as a non-root user, with no privilege escalation and with all capabilities dropped. Its seccomp profile is `RuntimeDefault` or `Localhost`. It also mounts no service-account token. The chart's default runtime is runc with `hostUsers: false`. gVisor is an option where a node offers it, and the one-host k3s platform selects it. A node without user namespaces fails closed with a named reason. No Docker backend exists.
   Enforced by: the sandbox template assertions in `scripts/kubernetes-chart-render.test.ts`, `scripts/sandbox-isolation.ts`, and `SandboxArchitectureTest#applicationMustNotUseADockerSandboxClient`.
7. **The agent image runs Node.js 24 with no package manager.** Each runner process runs under `--permission` with a bounded heap. A practice script gets no child-process grant.
   Enforced by: the package-manager check in `docker/agents/pi/Dockerfile`, and `PiRuntimeFactoryTest#runtimeFlagsForPractice`.
8. **Reviews run no build and no tests of the repository.** The sandbox has no package registry. Build evidence comes from the provider's CI as a captured source.
   Enforced by: the same Dockerfile check. The egress rule of decision 5 leaves no route to a registry.
9. **The provider sets the pace.** The runner starts the next practice session only when no session waits for the model and the sandbox has room. The server bounds the calls in flight to each provider. It learns that limit from refusals (429, 503, 529) and from latency.
   Enforced by: `pi-session-pace.spec.ts`, `LlmCallLimitTest` and `LlmCallPoolLearningIntegrationTest`.
10. **The agent proposes, and the server acts.** Each write to a provider, Slack or email is an `agent_action` row with an idempotency key. The transaction that decides the write inserts the row. One executor sends it.
    Enforced by: `ActionStoreIntegrationTest`. No architecture rule forbids a provider write outside the executor yet.

The loop keeps two rules of ADR 0068:

- **Codemode** runs inside the review loop. A read or a search in a codemode script thus gives its receipt ([workspace ABI](../contributor/agent/workspace-abi.mdx)).
- **One context budget** (ADR 0068 decision K) stays decided and is not shipped. Each practice gets an input-token budget beside its call and output budgets. Older evidence-tool results become notes when the live context passes a threshold. No pull request delivers it yet.

## What changed, and why

- **One role, not two or three** (ADR 0005, ADR 0080). The worker kept sandboxes and keys away from user sessions. With agent-sandbox, the server needs only a narrow Role. That Role can create and delete claims in one namespace and nothing else (ADR 0079 § Consequences).
- **The proxy has one place** (ADR 0006). ADR 0006 chose the coordinator, ADR 0041 the worker, and ADR 0079 the server. The capability flag of ADR 0006 is gone.
- **The chart defaults to runc with user namespaces, and gVisor is an option.** The chart leaves `runtimeClassName` empty, because a managed cluster may have no gVisor. The one-host k3s platform installs gVisor and selects it, as ADR 0079 decides. gVisor cannot run in a user namespace yet ([google/gvisor#13303](https://github.com/google/gvisor/issues/13303)). This keeps ADR 0068 decision E.
- **Fixed review targets go** (ADR 0074). "32 reviews on one worker" and "at most 30 MiB of worker memory for each review" were tuning values. Their role no longer exists. The pace rule stays.

## Consequences

- Good: one Deployment to run, scale and roll. The webhook, worker and web app Deployments, their values and their role gates go.
- Good: one lease model and one reaper for reviews, turns and actions.
- Good: provider keys and Kubernetes authority live in one process with one audit trail.
- Bad: the server holds Kubernetes authority beside user sessions. The Role is narrow, but a server compromise can create sandboxes.
- Bad: reception of provider events stops while PostgreSQL is down ([ADR 0080](0080-provider-events-go-to-a-postgresql-inbox.md) § Consequences).
- Bad: a node needs Linux 6.3 or later and a container runtime with user namespaces. The default kernels of Ubuntu 22.04 and RHEL 9 do not qualify.
- Bad: a practice that needs build results waits for the provider's CI.

## Confirmation

Each decision above names its test or gate.
Decision 10 has no rule against a provider write outside the executor. Code review catches it until [#4081](https://github.com/hephaestus-build/Hephaestus/pull/4081) moves the last sender.

## Revisit trigger

- One server process cannot hold the gateway traffic of its sandboxes beside the API.
- gVisor supports user namespaces ([google/gvisor#13303](https://github.com/google/gvisor/issues/13303)). Then measure it as the default.
- A provider limit that the adaptive limit cannot learn, for example a daily token quota.

## Sources

- Kubernetes, [User namespaces](https://kubernetes.io/docs/concepts/workloads/pods/user-namespaces/) and [Pod Security Standards](https://kubernetes.io/docs/concepts/security/pod-security-standards/). User namespaces are stable since v1.36 and need Linux 6.3 for idmapped `tmpfs`. This makes `hostUsers: false` a requirement that we can rely on, not an option. The `restricted` profile is the checklist of decision 6.
- PostgreSQL 18, [The locking clause](https://www.postgresql.org/docs/18/sql-select.html#SQL-FOR-UPDATE-SHARE). `SKIP LOCKED` "can be used to avoid lock contention with multiple consumers accessing a queue-like table". This replaces a broker for runs (decision 3).
- Chris Richardson, [Transactional outbox](https://microservices.io/patterns/data/transactional-outbox.html). Store the message "in the database as part of the transaction that updates the business entities". A relay then sends it. The relay can send twice, so each action has an idempotency key (decision 10).

## Already priced and declined

Each line links the options section that priced the option.

- [ADR 0005](0005-two-role-runtime-via-conditional-on-property.md#considered-options): five roles, `@Profile` gating, and a multi-module Maven build.
- [ADR 0006](0006-llm-proxy-on-coordinator-trust-model.md#considered-options): a proxy capability flag, and the proxy on the worker.
- [ADR 0009](0009-worker-runtime-substrate-wss-control-channel.md#considered-options): a NATS-only control channel, gRPC, a frame router, frame replay, and named drain modes.
- [ADR 0025](0025-agent-job-queue-on-postgresql.md#considered-options): JetStream for agent jobs, a poll with no claim, and another broker.
- [ADR 0030](0030-agent-runtime-is-typescript-on-bun.md): Bun as the agent runtime.
- [ADR 0068](0068-reviews-and-heph-run-on-one-agent-runtime.md#considered-options): a Heph turn as a Job, and a Heph loop with a relay.
- [ADR 0068](0068-reviews-and-heph-run-on-one-agent-runtime.md#considered-options): one loop host for all loops, or one for each workspace.
- [ADR 0068](0068-reviews-and-heph-run-on-one-agent-runtime.md#considered-options): an operator, and Kueue.
- [ADR 0074](0074-a-reviews-width-and-count-follow-the-provider.md#considered-options): more Pods at width 3, and several practices in one session.
- [ADR 0074](0074-a-reviews-width-and-count-follow-the-provider.md#considered-options): a model check before the sessions.
- [ADR 0079](0079-one-agent-system.md#considered-options): a shared loop host with a tools Pod for each turn, and a Pod for each conversation.
- [ADR 0079](0079-one-agent-system.md#considered-options): a workflow engine (Temporal, Restate or DBOS).
