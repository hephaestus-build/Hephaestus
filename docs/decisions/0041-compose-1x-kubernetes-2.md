# ADR 0041: Compose for 1.x; Kubernetes only for 2.0

**Status:** Accepted
**Date:** 2026-09-03
**Authors:** Hephaestus maintainers
**Amends:** [ADR 0005](0005-two-role-runtime-via-conditional-on-property.md),
[ADR 0006](0006-llm-proxy-on-coordinator-trust-model.md),
[ADR 0007](0007-sandbox-spi-shape.md), [ADR 0009](0009-worker-runtime-substrate-wss-control-channel.md),
[ADR 0020](0020-context-fabric-everything-is-an-integration.md),
[ADR 0034](0034-signed-release-image-lock.md),
[ADR 0035](0035-pull-request-previews-are-label-gated.md), and
[ADR 0039](0039-git-and-postgresql-own-evidence.md)

## Context

Hephaestus needs one credible path from its single-host installation to a substrate that can isolate
and schedule many concurrent, untrusted agent workloads. Keeping Docker Compose indefinitely would
make every release qualify two installation, networking, storage, upgrade, and recovery systems.
Moving before the agent boundary is independent of Docker would instead reproduce Docker assumptions
inside a second driver.

The 1.0 boundary is therefore architectural rather than calendar based. Before the tag, the Docker
deployment must prove the protocol and ownership boundaries that Kubernetes will reuse. The
intermediate 1.x release line then proves a Kubernetes driver and chart beside the supported Compose
installation. Version 2.0 removes the old path rather than maintaining two products.

This decision also resolves conflicts in the existing records. In particular, earlier designs made
captured payloads or Git keep-refs durable evidence, contemplated remote BYO workers, and let the
sandbox SPI grow transport and mount concepts. The required product guarantee is narrower: verify a
quote byte-for-byte once against exactly what the agent saw, retain the verdict, and never substitute
newer bytes.

## Decision drivers

- One supported installation, upgrade path, protocol, agent image, and conformance suite at steady
  state.
- A worker is the only trusted component adjacent to an untrusted sandbox. Credentials, admission,
  repository caches, and job ownership must not leak into the sandbox or the application role.
- PostgreSQL remains the transactional ledger and JetStream remains the durable delivery log. A
  worker disk may be lost without losing authoritative application state.
- Batch attempts need bounded, terminal scheduling semantics; interactive mentor sessions need
  stable identity, warm capacity, and suspend/resume.
- Context governance must apply to each area and repository before bytes enter a job folder.
- Operational promises need an owned migration, reversal criteria, and measurements rather than an
  open-ended abstraction.

## Considered options

1. **Compose forever.** Keep one substrate and decline Kubernetes. Cheapest to qualify, but no
   isolation or scheduling story for many concurrent untrusted sandboxes.
2. **A permanent dual backend.** Support Compose and Kubernetes side by side indefinitely. Every
   release then qualifies two installation, networking, storage, upgrade, and recovery systems.
3. **A staged single path.** Compose through 1.x, both substrates during one scope-named milestone,
   Kubernetes only from 2.0. Chosen.

## Decision

### Releases and support

Docker Compose with the Docker driver on one supported Linux host is the only supported 1.x install.
The `v1.0.0` scope includes the complete substrate-neutral architecture described below. During the
single, scope-named **1.x — Kubernetes in parallel** milestone, one Helm chart and the Kubernetes Job
driver ship beside Compose and must pass the same driver conformance suite. There is no separate
spike or permanent dual-backend architecture.

Version 2.0 is Kubernetes only. It deletes the Compose production topology, Docker driver, Coolify
production path, and Compose-specific Traefik configuration. The 2.0 release owns an end-to-end
migration guide and rehearsal. The single-host recipe is k3s followed by the same `helm install` used
elsewhere; Hephaestus does not build an appliance or custom operator.

The persistent 1.x state that an operator migrates is deliberately limited to:

| State | Migration operation |
| --- | --- |
| PostgreSQL ledger | Dump and restore into the target PostgreSQL service. |
| JetStream delivery log | Stop ingress; drain until consumer pending and acknowledgement-pending counts are zero; verify the corresponding PostgreSQL outcomes; then recreate empty streams. A migration that cannot reach and verify this boundary stops rather than discarding retained messages. |
| Secrets and release configuration | Map each named value into the chart; never copy an opaque Compose environment wholesale. |

Worker mirrors and job folders are caches and are rebuilt. The guide must inventory secrets, verify
the restored schema and stream state, drain 1.x, install the signed 2.0 release lock, exercise a
practice review and mentor session, and document rollback up to the point at which new writes are
admitted.

### The rule for a substrate split

A portable seam earns its place only when it has **one contract, one `sandbox.driver` selection,
one conformance suite, and no more than a few hundred lines of production code per adapter**. The
driver does only launch, observe, kill, remove, and list managed workloads. Transport, workspace
construction, evidence, credentials, and scheduling policy do not belong in it.

An inventory on 2026-09-03 — `wc -l` over the tracked files under `docker/` and under
`server/application/src/{main,test}/java/**/agent/sandbox/docker/` — found 4,890 lines of Compose and
self-host tooling and 5,366 main plus 5,995 test lines in the Docker driver. They fail that test.
The implementations are transitional, not APIs to preserve. PostgreSQL and NATS do not fail the test:
the former is the ledger and job queue, while the latter is a retained 10–30 GB delivery log that
survives role restarts. They are two different concerns and both remain.

The two-driver window is bounded from the 1.x milestone to 2.0. A change cannot add a driver-specific
protocol branch; both drivers pass the same suite.

### State and storage

PostgreSQL and NATS JetStream are the only stateful dependencies. Clustered Kubernetes runs three
JetStream servers with R3 streams; `docs/admin/webhook-ingestion-operations.mdx` owns the single-node
durability guarantee and its throughput cost. The 1.0 load qualification measures the setting rather
than assuming an acknowledgement implies power-loss durability.

There is no object store or Git server. Each worker disk holds bare repository mirrors and active job
folders. They are disposable caches reconstructable from PostgreSQL and authenticated upstreams.
PostgreSQL stores an opaque folder reference on the job, never a host path, and never stores Git refs
or repository contents.

Reconsider an object store in the shape Cursor's
[Continuity architecture](https://cursor.com/blog/git-at-any-scale) describes — a store of record
with repositories as warm caches — adapted to keep PostgreSQL as the ledger rather than eliminating
it, when **any** of these is observed:

- more than ten worker ordinals;
- any worker PVC above 200 GB;
- a workspace moves between workers more than once per week;
- sustained workspace-tar egress above 2 Gbit/s per worker; or
- a second region needs the same context.

Crossing a threshold starts a design review; it does not silently activate an adapter. The protocol
is additive already: the runner follows a `3xx` response from the workspace-tar URL, so a later object
store can use a presigned download without changing the agent contract.

### One job folder and one context mechanism

At job start, the owning worker creates `jobs/<job-id>/` and renders every permitted non-repository
area from PostgreSQL as plain files. It adds a working copy of each permitted repository; objects are
hard-linked from the worker's bare mirror where the filesystem permits it. Git documents local-clone
hard-linking and its security constraint that the source repository must be owned by the current user
([`git clone --local`](https://git-scm.com/docs/git-clone#Documentation/git-clone.txt---local)). The
worker is the sole writer to its mirror and serializes mirror maintenance.

Governance is evaluated independently per context area and per repository before rendering. The
result intentionally has two read models: PostgreSQL serves the webapp and pipeline; files serve the
agent. There is no publisher and no invalidation trigger because every folder is rendered from a
transactionally consistent job input at job start. Consider an incremental publisher only when p95
render time per job exceeds 60 seconds.

The initial workspace is one required tar request. Runtime capability discovery advertises one
per-job byte budget. Optional, permitted areas or repositories may use subsequent additive tar
responses through the same endpoint and authorization check; every response counts toward that
cumulative budget. This is one workspace-download capability, not a second transport or an
unbounded sequence. Version 1.0 has no tool catalogue, MCP data plane, context-as-Git repository, or
mounted context volume. Hints and provenance are never verdicts, and mentor conversation is not
practice evidence.

### Sandbox Gateway and protocol v3

The worker exposes one dedicated Sandbox Gateway port with four capabilities:

1. an OpenAI-compatible LLM proxy that resolves the job's governed key;
2. the workspace tar download;
3. result upload; and
4. interactive mentor frames.

This gateway is the worker's only non-loopback listener. The sandbox can reach exactly one address;
it receives no provider or repository credential and has no ingress. The gateway never initiates a
connection to a sandbox. `gitProxyUrl` remains absent and no Git server is introduced. BYO provider
support is a credential lookup behind the same proxy, not a second proxy topology; the Squid sidecar
proposal in [#1108](https://github.com/hephaestus-build/Hephaestus/issues/1108) is withdrawn.

Protocol v3 uses a per-job credential, runtime capability discovery with an explicit cumulative-byte
budget, one required workspace-tar request with optional additive responses, admitted result upload,
and interactive frames. Only the worker
holding the job lease accepts its upload; a duplicate terminal upload returns `409`, which drivers
treat as successful convergence. Agent image contract v2 to v3 is one release-lock upgrade;
[ADR 0034](0034-signed-release-image-lock.md) owns the lockstep set and the lock's inventory.

### Evidence admission and deletion

On upload, before admitting observations, the owning worker verifies every citation against the job
folder the agent read. Context quotes use a bounded file read; code quotes use the repository's Git
object. It records a verdict, digest of the quoted bytes, and typed failure reason per citation in
PostgreSQL. A citation to a refused area or repository is rejected. Delivery on any runtime role
reads only these verdicts and never worker storage.

The guarantee is **byte-exact verification once, against what the agent saw**. **No replay is
offered.** The worker deletes the folder after admission, or one hour after an attempt ends without an
admitted result. There are no keep-refs, content-addressed store, evidence payload rows, shared
volume, or retained evidence copies. If regulation, incident response, or user research establishes
a concrete audit-window requirement, a follow-up may add encrypted retained bytes with explicit
authorization, regional placement, erasure, expiry, backup, and restoration semantics. It must not
reconstruct evidence from current upstream content.

### Bash and the sandbox boundary

The agent has Pi's `read`, `write`, `edit`, and `bash` tools plus report tools. Bash is on by default
under one configuration; there is no quality mode that disables it. This matches established coding
agents and code-execution systems: [Claude Code](https://code.claude.com/docs/en/security),
[Codex](https://learn.chatgpt.com/docs/security),
[GitHub Copilot cloud agent](https://docs.github.com/en/copilot/concepts/agents/cloud-agent/about-cloud-agent)
and Cloudflare's [Code Mode](https://blog.cloudflare.com/code-mode/) all treat command execution as a
sandboxing and policy problem rather than an allowlisted-language problem. A tool allowlist was
never a security boundary here because precompute already executes arbitrary Node child processes
inside the same sandbox.

The sandbox is the boundary: non-root user, read-only root filesystem, no host mounts or Docker
socket, credential-free environment, one network destination, resource and PID limits, deadline, and
sized tmpfs only for writable paths. On Kubernetes it also meets the Restricted Pod Security
Standard ([controls](https://kubernetes.io/docs/concepts/security/pod-security-standards/#restricted)).
gVisor is recommended as `runsc` for Compose and as a
`RuntimeClass` on Kubernetes; Kubernetes documents `RuntimeClass` as the mechanism for selecting a
container runtime configuration ([RuntimeClass](https://kubernetes.io/docs/concepts/containers/runtime-class/)).
The evaluation harness gates release quality with bash enabled. A runtime switch cannot turn a weak
agent into a safe one and is not added.

### Kubernetes batch and interactive execution

Each batch attempt is one Kubernetes Job and one Pod with `restartPolicy: Never`, `backoffLimit: 0`,
a `podFailurePolicy`, active deadline, and TTL no greater than one hour. Kubernetes Jobs provide the
terminal and deadline semantics directly ([Job controller](https://kubernetes.io/docs/concepts/workloads/controllers/job/));
the TTL controller cleans finished Jobs and their dependents
([TTL-after-finished](https://kubernetes.io/docs/concepts/workloads/controllers/ttlafterfinished/)).
Image-pull, quota, admission, and scheduling failures are provisioning refusals rather than agent
attempts.

Reconciliation is namespace scoped. It is idempotent across controller restart and treats an already
admitted upload (`409`) as success. The driver uses stable upstream Kubernetes APIs and client
watch/reconcile behavior; it does not recreate a scheduler or remote exec transport.

Workers run as a StatefulSet with one PVC and per-ordinal Service per worker. PostgreSQL holds a
renewable workspace-to-ordinal lease so repository work stays sticky while reassignment remains
possible. NetworkPolicy selects worker Pods and the named gateway port. It never uses a ClusterIP in
an `ipBlock`: Kubernetes warns that service rewriting order relative to NetworkPolicy is
implementation-dependent ([`ipBlock` selector behaviour](https://kubernetes.io/docs/concepts/services-networking/network-policies/#behavior-of-ipblock-selectors)).

Interactive mentor sessions use Kubernetes SIG Apps'
[Agent Sandbox](https://github.com/kubernetes-sigs/agent-sandbox) as the only CRD dependency. Its
`v1beta1` API and implementation version are pinned and upgrade-tested. It supplies warm pools,
stable sandbox identity, and controller-managed pause/resume over persistent storage; it is not
transport or storage. NetworkPolicy for a warm Pod is the chart's to author, not the CRD's: Agent
Sandbox does not manage one. Deep hibernation is on its roadmap and is not depended on. Claims contain
no environment or volumes so a warm Pod can adopt them. A bound ServiceAccount token is verified by
the gateway through Kubernetes TokenReview before a warm Pod registers. TokenReview is the standard
API for validating projected service-account credentials
([verifying private claims](https://kubernetes.io/docs/reference/access-authn-authz/service-accounts-admin/#verifying-and-inspecting-private-claims)).
Batch moves to warm claims only if measurement shows startup latency materially harms its objective;
Jobs remain the default.

The chart uses GA APIs plus this one pinned CRD. It owns Pod Security settings, default-deny network
policy, service accounts, resource requests and limits, disruption behavior, probes, observability,
upgrade hooks, and PostgreSQL/JetStream connection contracts. It does not own a database operator.

### Roles, control channel, and previews

There are exactly three runtime roles — `server`, `worker`, and `webhook` — from one signed image. WSS
remains the control channel for cancel, drain, capacity, and mentor frames. The worker is not a
customer-hosted remote executor: the BYO remote-worker purpose and
[#1318](https://github.com/hephaestus-build/Hephaestus/issues/1318) are withdrawn. “BYO” means a governed
model/provider connection.

2.0 replaces the deployment substrate under previews, not the product policy above them:
[ADR 0035](0035-pull-request-previews-are-label-gated.md) owns where a preview may execute agents and
what it may execute them against.

## Security boundaries and upgrade ownership

The sandbox, gateway and admission boundaries are stated with the decisions that create them above,
and the release boundary belongs to [ADR 0034](0034-signed-release-image-lock.md). The three that
belong to no single decision:

| Boundary | Owner and invariant |
| --- | --- |
| Workspace → workspace | SQL workspace scope and folder construction prevent cross-workspace bytes; cache paths are never authorization. |
| Worker → upstream | Repository credentials stay on the worker and are scoped to the workspace/repository operation. |
| Kubernetes control plane | Namespace-scoped least privilege; projected tokens are audience-bound and checked with TokenReview. |

Maintainers own the protocol, driver suite, image-contract lockstep, chart, CRD pin, migration guide,
and upgrade rehearsal. Operators own backups, credentials, capacity, supported Kubernetes and runtime
versions, and executing the documented drain/migration. Agent Sandbox upgrades are tested against
suspend/resume, warm registration, expiry, NetworkPolicy, and cleanup before their pin moves.

## Verification gates

One behavioural conformance suite binds every driver, and an executable isolation probe runs on every
supported runtime; agent execution fails closed where a cluster cannot enforce that isolation.
Neither is substitutable by a driver-specific test.

## Rejected alternatives

| Alternative | Why rejected; reversal trigger |
| --- | --- |
| Keep a Compose floor after 2.0 | Permanently doubles qualification and incident paths, against the inventory measured in § The rule for a substrate split. Reverse only if measured operator loss outweighs that recurring cost and an independently staffed support policy exists. |
| Ship a k3s appliance | Hephaestus would own host lifecycle and Kubernetes upgrades. The published [k3s quick-start](https://docs.k3s.io/quick-start) plus Helm is sufficient; reverse only for a funded appliance product with its own security/update ownership. |
| Two full sandbox backends | Preserves transport, storage, and policy in both drivers. Only the narrow driver seam and bounded transition are accepted. |
| Add a PostgreSQL ingest table | Duplicates JetStream's retained delivery log and couples webhook admission to ledger availability. PostgreSQL records processed application state, not a second raw event log. |
| Store context or evidence copies in PostgreSQL | Makes repository/file access a database protocol and pays sensitive-byte retention for replay the product does not offer. SQL remains the ledger/read model, not a byte warehouse. Reverse only for the explicit audit-window need above. |
| Add object storage, presigned URLs, or SeaweedFS now | Adds credentials, availability, lifecycle, regional, and erasure boundaries before a measured bottleneck. Use the stated thresholds. |
| Add a Git server | Adds an authenticated network protocol for data already local to the worker; reconsider only if workers must independently share repositories at measured scale. |
| Add a local-directory store adapter | Renames a directory without producing portability; the job-folder contract is the seam. |
| Publish context incrementally | Adds triggers, races, and another liveness model. Reconsider on the render-time threshold above. |
| Tool catalogues or [MCP](https://modelcontextprotocol.io/specification/2025-06-18) for context | Adds round trips and a second authorization surface; files plus bash already provide selective access. Reconsider only for context that cannot be safely materialized. |
| Represent context as Git repositories | Forces non-code data and mutable projections into repository semantics and complicates per-area governance. |
| Restore a shell allowlist | It was never a sandbox boundary and reduces agent quality. Isolation and evaluation are the controls. |
| Use bare Pods or remote exec for batch | Reimplements completion, retry, deadline, and cleanup behavior. Kubernetes Jobs own these semantics. The Kubernetes project itself recommends workload resources rather than managing Pods directly ([workload management](https://kubernetes.io/docs/concepts/workloads/controllers/)). |
| Use Agent Sandbox for all batch work immediately | Adds a CRD to the simple terminal path without evidence that warm adoption helps. A measured latency gate can reverse this. |

## Consequences

- The 1.0 date follows completion of the substrate-neutral contract; scope is not weakened to meet a
  date.
- The 1.x milestone temporarily pays for two drivers, but only behind one protocol and suite and with
  a mandatory deletion release.
- Loss of a worker disk loses active attempts and cache warmth, not ledger or delivery-log state.
- Admission verdicts permit any server replica to deliver without shared storage.
- Kubernetes is an operator prerequisite in 2.0. The chart and migration guide must make the
  single-host path no more ambiguous than Compose, not pretend Kubernetes is operationally free.
- Agent power increases with bash; the isolation boundary and evaluation corpus therefore become
  release-blocking controls.

## Revisit triggers

Each named part carries its own trigger at the point of decision. The Kubernetes-only product
decision is revisited only on evidence of unacceptable 2.0 adoption loss with explicit ownership of a
second long-lived support matrix. A driver exceeding the few-hundred-line budget or requiring a
protocol fork fails the split rule and must be simplified rather than normalized.

## Sources

- [Kubernetes Jobs](https://kubernetes.io/docs/concepts/workloads/controllers/job/),
  [pod failure policy](https://kubernetes.io/docs/concepts/workloads/controllers/job/#pod-failure-policy),
  and [TTL cleanup](https://kubernetes.io/docs/concepts/workloads/controllers/ttlafterfinished/)
- [Kubernetes NetworkPolicy](https://kubernetes.io/docs/concepts/services-networking/network-policies/),
  [StatefulSet](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/), and
  [RuntimeClass](https://kubernetes.io/docs/concepts/containers/runtime-class/)
- [Kubernetes Restricted Pod Security Standard](https://kubernetes.io/docs/concepts/security/pod-security-standards/#restricted)
- [Kubernetes Agent Sandbox](https://github.com/kubernetes-sigs/agent-sandbox)
- [gVisor platform guide](https://gvisor.dev/docs/user_guide/platforms/)
- [Git local clone and hard-link behavior](https://git-scm.com/docs/git-clone#Documentation/git-clone.txt---local)
- [Cursor Continuity: Git at any scale](https://cursor.com/blog/git-at-any-scale)
- [Cloudflare Code Mode](https://blog.cloudflare.com/code-mode/)
