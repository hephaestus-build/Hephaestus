# ADR 0041: Compose for 1.x; Kubernetes only for 2.0

**Status:** Accepted (amended 2026-10-09 — debug transcripts; 2026-10-09 — Heph host, [ADR 0057](0057-one-heph-conversation-per-developer.md); 2026-10-24 — [the Kubernetes runtime](#update--2026-10-24-the-kubernetes-runtime); 2026-10-25 — Helm chart support and 1.x security fixes; 2026-10-25 — § Releases and support superseded by [ADR 0058](0058-the-helm-chart-is-the-only-supported-deployment-from-1-0.md))
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

Docker Compose with the Docker driver on one supported Linux host is a supported install for all of 1.x. The Helm chart is supported beside it from the first release that publishes it ([updated 2026-10-25](#update--2026-10-25-helm-chart-support-and-1x-security-fixes)).
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

At job start, the owning worker creates the attempt folder and renders every permitted non-repository
area from PostgreSQL as plain files. It adds a working copy of each permitted repository: JGit, in the
worker's own process, fetches the reachable objects from the worker's bare mirror into a fresh
repository under the fabric root and checks the reviewed commit out, and the worker copies that
snapshot into the attempt folder entry by entry, refusing anything that is not a regular file or
directory. Objects are not hard-linked out of the mirror: the JGit fetch copies them, so the snapshot
shares no file with the mirror, and Git's own local-clone hard-linking would require the source
repository to be owned by the current user
([`git clone --local`](https://git-scm.com/docs/git-clone#Documentation/git-clone.txt---local)). The
worker is the sole writer to its mirror and an in-process lock serializes a fetch against readers.

Governance is evaluated independently per context area and per repository before rendering. The
result intentionally has two read models: PostgreSQL serves the webapp and pipeline; files serve the
agent. There is no publisher and no invalidation trigger because every folder is rendered from a
transactionally consistent job input at job start. Consider an incremental publisher only when p95
render time per job exceeds 60 seconds.

The initial workspace is one required logical tar download. A dropped download retries that same
tar from byte zero; a retry does not spend the additive byte budget. Runtime capability discovery
advertises one per-job byte budget. Optional, permitted areas or repositories may use subsequent
additive tar responses through the same endpoint and authorization check; each additive response
counts toward that cumulative budget. This is one workspace-download capability, not a second transport or an
unbounded sequence. Version 1.0 has no tool catalogue, MCP data plane, context-as-Git repository, or
mount of worker or host context storage. Hints and provenance are never verdicts, and mentor conversation is not
practice evidence.

Downloaded inputs may use an attempt-local ephemeral volume prepared by a separate, trusted non-root
initializer. The runtime mounts those inputs read-only, with separate writable scratch and output
regions. This volume is local storage for the tar transport, not a second data plane: it never mounts
the worker's repository cache or job folder, and is removed with the attempt. The same boundary uses
managed Docker volumes in Compose and `emptyDir` with a non-root init container in Kubernetes.

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

Protocol v3 provides attempt-scoped credentials, byte-budget discovery, one logical workspace download,
result-archive upload, and interactive frames. The upload carries an
[RFC 9530 `Content-Digest`](https://www.rfc-editor.org/rfc/rfc9530#section-2) with SHA-256 over the
complete tar. A successful upload acknowledges transport, not observation admission. Both success
and duplicate `409` return the admitted archive's strong
[RFC 9110 `ETag`](https://www.rfc-editor.org/rfc/rfc9110#section-8.8.3). The runner treats either as
convergence only when that tag matches the bytes it sent. A different result or a conflict from a
non-owning worker is not success. An overlapping upload returns retryable `503`.
[ADR 0034](0034-signed-release-image-lock.md) owns the lockstep image upgrade.

### Evidence admission and deletion

Observation admission is a separate authenticated operation. It verifies citations against captured
artifacts or Git objects and persists verdicts and digests in PostgreSQL under the job's ownership
fence. Result upload does not bypass this check. Delivery reads persisted verdicts, never worker
storage.

The guarantee is **byte-exact citation verification against captured evidence**. **No replay is
offered.** The worker deletes its evidence folder after observation admission commits; feedback
composition runs on the worker after the sandbox ends, from the inputs that the admission retained and the
persisted verdicts.
A sweep retries missed cleanup. Other ended attempts become eligible for deletion one hour after
the worker first records that they have ended. On restart, the worker deletes folders for unknown
or finished attempts immediately and preserves matching running attempts. There are no keep-refs,
content-addressed store, evidence payload rows, shared volume, or retained replay copies; session
transcripts are a bounded debug exception ([Update — 2026-10-09](#update--2026-10-09-debug-transcripts)). Snapshot
repositories under the fabric root are released by the process that created them; age-based cleanup
reclaims only leftovers of a previous process.

### Bash and the sandbox boundary

Practice sessions expose native shell and file tools; feedback composition uses read-only evidence
tools and report tools. The [workspace ABI](../contributor/agent/workspace-abi.mdx#practice-review-tools)
owns the tool sets. A tool allowlist is not a security boundary: precompute also executes child
processes inside the sandbox.

The sandbox is the boundary: non-root user, read-only root filesystem, no host mounts or Docker
socket, credential-free environment, one network destination, resource and PID limits, deadline, and
sized tmpfs plus the attempt-local volumes as the only writable paths. On Kubernetes it also meets the Restricted Pod Security
Standard ([controls](https://kubernetes.io/docs/concepts/security/pod-security-standards/#restricted)).
gVisor is recommended as `runsc` for Compose and as a
`RuntimeClass` on Kubernetes; Kubernetes documents `RuntimeClass` as the mechanism for selecting a
container runtime configuration ([RuntimeClass](https://kubernetes.io/docs/concepts/containers/runtime-class/)).
Release qualification must exercise Bash-enabled reviews; restricting tools is not a substitute for
runtime isolation.

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

## Update — 2026-10-09: debug transcripts

The maintainers authorized one bounded exception to § Evidence admission and deletion.
A worker keeps a review attempt's native session transcripts for 24 hours, for next-day investigation.
The exception does not change input deletion: admitted inputs are still deleted at admission.
It adds no replayable workspace snapshot, no database table, no API and no listing.

- **Custody.** The transcripts sit beside the attempt folder, under the same job lease and the same `person_evidence_copy` receipt.
  Erasure of the job removes them with the job folder before it acknowledges.
  The sweep deletes them after 24 hours, also after a restart and whatever the job's status.
- **Ownership proof.** A transcript can repeat any row that the review copied from another review's observations or feedback.
  This includes the history staged at capture and the reads during the run (IN_APP support and public history).
  Those rows can quote people whom only the source review's receipt names.
  A receipt therefore records the jobs whose rows it copied and unions their receipts.
  It is `dependencies: COMPLETE` only when each such receipt is `READY` and itself `COMPLETE`.
  Receipts written before this update have no marker and never count as complete.
  A read during the run holds the copy fence from before it selects rows until its people are in the attempt's `READY` receipt.
  A receipt that is no longer `READY` is never revived.
- **Fallback.** Without that proof, the worker keeps no file from the sandbox.
  It rebuilds a summary of owned enums and bounded numbers from the collector's manifest and marks the attempt `METADATA_ONLY`.
- **Attempt.** The worker binds a session to its job, attempt, owner and image when the session is registered, before the sandbox starts.
  An upload is kept only while the job is still owned as that attempt.
  Reviews that copy legacy history can keep metadata only indefinitely.
  New dependent receipts do not reconstruct missing legacy ownership.
- **Unavailable.** A parent SIGKILL, a Docker stop timeout before the upload, or a failed upload leaves no transcript.

The [workspace ABI](../contributor/agent/workspace-abi.mdx#session-transcripts) owns the bounds and file formats.

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

## Update — 2026-10-09 (Heph host, [ADR 0057](0057-one-heph-conversation-per-developer.md))

Supersedes the interactive parts of this decision. Heph has no per-developer session to keep warm or
resume, so the driver "interactive mentor sessions need stable identity, warm capacity, and
suspend/resume" no longer holds. Each worker runs one stateless Heph host, and every turn rebuilds its
session from PostgreSQL. Gateway capability 4 is now the Heph host's command channel
(`/internal/llm/runtime/{hostId}/commands`) and its turn endpoints (`/internal/llm/turn/{id}/…`), not
interactive frames. WSS carries no mentor frames. Heph no longer needs the Agent Sandbox CRD or its
warm pools.

## Update — 2026-10-24: the Kubernetes runtime

Supersedes the JetStream sentence of § State and storage, and § Kubernetes batch and interactive execution.
The other sections stand.
[ADR 0058](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0058-the-helm-chart-is-the-only-supported-deployment-from-1-0.md) owns the support promise.

**Deployment.**
The Helm chart and the Kubernetes runtime ship from 1.0.
Before 1.0, the maintainers' staging instance runs the signed chart on k3s on its host, upgraded by the host's pull timer ([ADR 0042](0042-hosts-pull-their-own-releases.md)).
Their production instance follows after one release qualified there.
The chart supports a conformant Kubernetes cluster at a minor version that the Kubernetes project supports ([ADR 0060](0060-the-kubernetes-floor-follows-upstream-support.md) sets the floor).
The cluster needs a network plugin that enforces NetworkPolicy for egress and ingress, and a Gateway API controller for `HTTPRoute` `v1`.
`kubeVersion` in the chart is a technical floor, not the support statement.
CI installs and upgrades the chart on the lowest and the latest supported minor ([`docs/admin/kubernetes.mdx`](../admin/kubernetes.mdx#supported-clusters)).

**State.**
The bundled NATS is one JetStream server with R1 streams; the application has no stream replica setting.
A lost NATS volume loses each delivery that the broker accepted and the server had not yet processed.
The bundled PostgreSQL is one StatefulSet with an optional pgBackRest archive to one S3-compatible bucket, as in Compose.
High availability for either is not a 1.0 promise.

**Execution.**

- A practice review attempt is one `batch/v1` Job, named after the job and the attempt, with `backoffLimit: 0`, so the Job controller never replaces its Pod.
  Its `activeDeadlineSeconds` is the attempt's upload deadline plus two minutes, so the Job ends without its worker.
  The TTL controller removes a finished Job after 15 minutes.
- The Heph host and Heph's turn sandboxes are Pods.
  The host ends when its command channel closes; a turn sandbox has its turn's deadline.
- No sandbox kind uses Agent Sandbox or another CRD.
- The sandbox backend has `launch`, `observe`, `running`, `transcript`, `kill`, `remove` and `list` ([ADR 0007](0007-sandbox-spi-shape.md#update--2026-10-24-one-attempt-runner-over-a-narrow-backend)).
  A handle is the Job's or the Pod's name, so any worker reaches any sandbox.
  `ExecSandboxes` stays separate for turn sandboxes.

**Workers.**
Workers are a Deployment: two replicas by default, spread across nodes with `ScheduleAnyway`, a PodDisruptionBudget of one, and a rollout that starts the new workers before an old one drains (`maxSurge: 100%`, `maxUnavailable: 0`).
A draining worker claims nothing new and serves its running attempts until its drain timeout.
There is no StatefulSet, no per-worker Service and no workspace lease.
The ledger decides custody: a review's sandbox belongs to its job while that job is queued or running.
A Heph host or a turn sandbox belongs to its worker while that worker beats in `worker_registry`.
Every worker removes what neither rule keeps.
The sandbox namespace is the operator's, for one release alone.

**Network.**
One NetworkPolicy lets review and host sandboxes reach the worker Pods' gateway port, and nothing else.
It selects the workers by their label, never by a Service address.
A sandbox's credential, not the network, binds it to its own worker.
Turn sandboxes have no network.
The NetworkPolicy of a new Pod can take effect seconds after the Pod starts on some plugins, so each sandbox Pod first waits, in an init container, until it cannot reach the API server.

**Verification gates.**
Each worker runs the isolation probe at its start and then every 15 minutes by default.
The probe gates, and the worker starts no sandbox while one gate fails:

- The sandbox namespace enforces the Restricted Pod Security Standard.
- A review sandbox reaches the gateway and not the worker's management port, the API server or the DNS server; a sandbox of no kind does not reach the gateway.
- A sandbox has no service account token, a read-only root and a non-root user.
- A named RuntimeClass exists.

The probe records, without gating, the isolation tier: the RuntimeClass, whether user namespaces (`hostUsers: false`) take effect, and the per-Pod process limit of the nodes.
Sandbox Pods run in user namespaces once the probe has shown them.
The worker's log and its metrics show the tier.
AppArmor is not a gate.
A per-review record of the tier needs a schema change and is not part of 1.0.

**Gaps against Docker.**
Kubernetes has no field for `noexec` on an `emptyDir` and no private `/dev/shm` size; `/tmp`, `/run` and a turn sandbox's `/workspace` are therefore executable, and a sandbox keeps `/dev/shm` in its private IPC namespace.
Every command starts through `prlimit`, which sets `nofile` and `core` as Docker's ulimits do.

**Backups.**
The bundled PostgreSQL archives its WAL with pgBackRest and takes scheduled full and differential backups.
The backup Jobs read the data directory through pgBackRest's TLS server in the PostgreSQL Pod, with mutual TLS, so that no other Pod mounts the volume.
A restore is a chart setting: it stops every database role and runs one restore Job on the volume.

**Migrations.**
A Job of each Helm revision runs the server image in its migrate run mode, and every role waits for the schema.
The Job is a normal resource of the release, not a Helm hook, because the bundled PostgreSQL does not exist before the install.
The documented upgrade is `helm upgrade --rollback-on-failure --wait=watcher --wait-for-jobs`, so a failed migration fails the upgrade.

**Practice that the chart follows, and where it deviates.**
Primary sources only.

| Practice | Source | In the chart |
| --- | --- | --- |
| Restricted Pod Security Standard, enforced by Pod Security admission | [Pod Security Standards](https://kubernetes.io/docs/concepts/security/pod-security-standards/), [Pod Security Admission](https://kubernetes.io/docs/concepts/security/pod-security-admission/) | Every Pod meets Restricted. The operator labels both namespaces `enforce=restricted`; the probe proves enforcement in the sandbox namespace. |
| Default-deny NetworkPolicy and explicit allows | [Network Policies](https://kubernetes.io/docs/concepts/services-networking/network-policies/) | Deny-all in both namespaces, then one allow per path. Policies select Pods by label. The worker's API egress is an `ipBlock` of the endpoint that the plugin sees, because the page leaves the order of Service translation and policy to the plugin. |
| No service account token where none is needed | [Configure Service Accounts](https://kubernetes.io/docs/tasks/configure-pod-container/configure-service-account/), [NSA/CISA Kubernetes Hardening Guide](https://www.cisa.gov/news-events/alerts/2022/03/15/updated-kubernetes-hardening-guide) | Only the worker mounts a token; its Role is namespace-scoped. |
| Seccomp, a sandboxed runtime, and user namespaces | [Seccomp](https://kubernetes.io/docs/tutorials/security/seccomp/), [RuntimeClass](https://kubernetes.io/docs/concepts/containers/runtime-class/), [User Namespaces](https://kubernetes.io/docs/concepts/workloads/pods/user-namespaces/) | `RuntimeDefault` or the shipped Localhost profile; RuntimeClass by value; `hostUsers: false` once the probe shows that the nodes support it. |
| Jobs for run-to-completion work with a deadline and a TTL | [Jobs](https://kubernetes.io/docs/concepts/workloads/controllers/job/), [TTL after finished](https://kubernetes.io/docs/concepts/workloads/controllers/ttlafterfinished/) | `backoffLimit: 0`, because the ledger, not the Job controller, decides a retry. |
| Rolling updates, disruption budgets, spread, and taint-based eviction | [Deployments](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/), [Disruptions](https://kubernetes.io/docs/concepts/workloads/pods/disruptions/), [Topology Spread Constraints](https://kubernetes.io/docs/concepts/scheduling-eviction/topology-spread-constraints/), [Taints and Tolerations](https://kubernetes.io/docs/concepts/scheduling-eviction/taint-and-toleration/) | Workers: `maxSurge: 100%`, `maxUnavailable: 0`, PDB `maxUnavailable: 1`, spread with `ScheduleAnyway` so that a one-node cluster still schedules, `tolerationSeconds: 60`. |
| Requests and limits; memory request equal to the limit for the JVM | [Resource Management](https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/), [Paketo memory calculator](https://paketo.io/docs/reference/java-reference/) | The buildpack sizes the heap from the limit, so the JVM roles request their whole memory limit. CPU keeps a lower request. |
| Probes, graceful shutdown and a preStop pause | [Spring Boot on Kubernetes](https://docs.spring.io/spring-boot/how-to/deployment/cloud.html), [Kubernetes probes](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html), [Graceful shutdown](https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html), [Container lifecycle hooks](https://kubernetes.io/docs/concepts/containers/container-lifecycle-hooks/) | Liveness and readiness groups on the management port; a five-second `preStop` sleep action on the roles behind Services; grace periods that cover Spring's shutdown phase and, for workers, the drain. |
| Secrets by reference | [Good practices for Secrets](https://kubernetes.io/docs/concepts/security/secrets-good-practices/) | The chart reads one existing Secret and writes no secret value. |
| Chart structure, schema and OCI distribution | [Chart Best Practices](https://helm.sh/docs/chart_best_practices/), [Charts: schema files](https://helm.sh/docs/topics/charts/), [OCI registries](https://helm.sh/docs/topics/registries/) | `values.schema.json` refuses unknown keys; images by digest; the chart is an OCI artifact of the release. |
| Gateway API for exposure | [Gateway API](https://kubernetes.io/docs/concepts/services-networking/gateway/), [Ingress](https://kubernetes.io/docs/concepts/services-networking/ingress/), [conformance levels](https://gateway-api.sigs.k8s.io/docs/conformance/levels/) | One `HTTPRoute`. The four features that it needs are Extended, so the operator confirms them for the controller. |
| PostgreSQL backups | [pgBackRest User Guide](https://pgbackrest.org/user-guide.html), [Configuration Reference](https://pgbackrest.org/configuration.html) | WAL archive, retention by full backups, encryption, and a TLS server with client certificates. |

Deviations:

- **No Helm hooks.** [Chart hooks](https://helm.sh/docs/topics/charts_hooks/) suit a migration Job, but the bundled PostgreSQL does not exist before an install, and a hook leaves no resource that `helm` tracks. The migration is therefore a plain Job of each revision.
- **No chart-testing (`ct`).** `ct install --upgrade` installs the previous chart revision with the same values files; while the chart's schema still changes, those values do not fit both revisions. CI installs, then upgrades the same chart with changed values, with Helm itself (`scripts/kubernetes-cluster.ts`). Once a released chart exists, the upgrade starts from it. `ct lint` adds a version-increment check to `helm lint`, and the chart's version comes from the release, not from a commit, so the schema job runs `helm lint --strict` alone.
- **The chart creates no Namespace.** A release installs into the namespace that `--namespace` names, and the sandbox namespace is an input too, so that a platform that owns namespaces, their labels and their quotas (a Rancher project, for example) keeps them.
- **One replica of the server, NATS and PostgreSQL.** Each needs work that 1.0 does not have: ordered event processing across server replicas, a stream replica setting, and a PostgreSQL operator.

## Update — 2026-10-25: Helm chart support and 1.x security fixes

The maintainers changed the support promise in [Releases and support](#releases-and-support).
The Helm chart becomes a supported install beside Compose, from the first release that publishes it.
Its values join the public contract under Semantic Versioning.
Compose stays supported for all of 1.x.
After 2.0.0, the last 1.x minor release gets a fixed period of security fixes.
The [compatibility policy](../admin/compatibility-policy.mdx#support-for-1x) owns the terms.
2.0 stays Kubernetes only.

## Update — 2026-10-25 (Kubernetes from 1.0, [ADR 0058](0058-the-helm-chart-is-the-only-supported-deployment-from-1-0.md))

ADR 0058 supersedes § Releases and support. ADR 0058 § Supported deployment owns the supported deployment from 1.0.
It also supersedes the Compose part of the update above: Compose is not a supported 1.x deployment.
The 1.x support terms of that update stay with the chart, and the [compatibility policy](../admin/compatibility-policy.mdx#supported-deployment) owns them.
There is no 1.x milestone with two supported deployments.

The migration of a Compose install moves from 2.0 to the time before 1.0.
ADR 0058 § Migration of a Compose install owns its state table.
The JetStream drain rule of this decision stays in that table.

These parts of this decision change with it:

- **§ Context**, the 1.x milestone and the removal in 2.0. Both move to the time before 1.0.
- **§ Considered options**, option 3. ADR 0058 chooses the chart only, from 1.0.
- **§ The rule for a substrate split.** The Docker driver stays as the driver for development and for the live suites. It passes the same conformance suite. A decision after 1.0 removes it or keeps it.
- **§ Roles, control channel, and previews.** Previews move to the staging k3s before 2.0.
- **§ Rejected alternatives**, row "Keep a Compose floor after 2.0". The row now applies from 1.0. ADR 0058 § Revisit trigger owns the reversal trigger.
- **§ Consequences**, the bullets about the 1.x milestone and about 2.0. Kubernetes is an operator prerequisite from 1.0.
- **§ Revisit triggers**, the Kubernetes-only product decision. ADR 0058 § Revisit trigger replaces it.

The architecture of this decision stays: state and storage, the job folder, the Sandbox Gateway, evidence admission, the sandbox boundary, the Kubernetes execution model, and the three runtime roles.
