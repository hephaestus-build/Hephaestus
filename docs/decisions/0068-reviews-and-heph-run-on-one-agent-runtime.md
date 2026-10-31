# ADR 0068: Reviews and Heph run on one agent runtime

**Status:** Accepted (2026-10-28)
**Date:** 2026-10-27
**Authors:** Felix T.J. Dietrich

**Supersedes:**

- [ADR 0057](0057-one-heph-conversation-per-developer.md) § The Heph host and § Context folder and turn sandbox, for where Heph's loop and tools run.
  The ledger, the conversation model, the context model and delivery stay.
- [ADR 0009](0009-worker-runtime-substrate-wss-control-channel.md), for the WSS control channel between the worker and the server.

**Amends:**

- [ADR 0005](0005-two-role-runtime-via-conditional-on-property.md) and [ADR 0041](0041-compose-1x-kubernetes-2.md): one sandbox runtime for both kinds of agent work.
- [ADR 0007](0007-sandbox-spi-shape.md): the sandbox backend starts Jobs only, and `HostSandbox` goes.
- [ADR 0058](0058-the-helm-chart-is-the-only-supported-deployment-from-1-0.md) § Supported deployment.
  The sentence "Hephaestus does not build an appliance or an operator" becomes a decision with reasons and revisit triggers.
  The Docker sandbox driver goes before 1.0 ([decision D](#d-the-docker-sandbox-backend-goes-before-10), decided).
- [ADR 0060](0060-the-kubernetes-floor-follows-upstream-support.md): sandbox nodes need user namespaces ([decision C](#c-user-namespaces-are-required)).

**Builds on:**

- [ADR 0025](0025-agent-job-queue-on-postgresql.md): PostgreSQL is the queue and the only record of work.
- [ADR 0055](0055-practice-scripts-use-one-result-contract.md): the contract for the model calls of a practice script.
- [ADR 0061](0061-review-seals-then-feedback-composes.md): composition is a worker step, not a sandbox.

Each ADR above has a dated update block that points here.

## Context

In 1.0, reviews and Heph run on one agent runtime. The maintainer set this goal on 2026-10-27.
The runtime is the long-term design, not an interim step.
Changes before 1.0 are cheap.
Effort is not a criterion. Correctness, security, simplicity and fit on the reference node are.

### What runs today

| Part | Kind | Holds | Exists because |
|---|---|---|---|
| Server | Deployment, 1 replica | User sessions, the API, the NATS consumer, schedules, the turn lease reaper, the review zombie sweeper | The product |
| Webhook | Deployment | The provider signature secrets. It publishes to NATS. | A push event cannot be redelivered ([ADR 0008](0008-webhook-runtime-role.md)) |
| Worker | Deployment | A Role in the sandbox namespace, the provider keys, the Git mirrors, the gateway and the LLM proxy, and the attempt token issuer | Kubernetes authority does not sit beside user sessions |
| Review sandbox | A Job for each attempt | The review's runner and its tools | One sandbox runs the whole review |
| Heph host | A bare Pod for each worker | The Heph turns of its worker | The stateless loop of [ADR 0057](0057-one-heph-conversation-per-developer.md) |
| Turn sandbox | Bare Pods with a stager container, a pool of 2 | Nothing. No network. The worker reaches it through `pods/exec`. | `read`, `bash` and `load_context` for Heph |
| Isolation probe | A Job with 3 Pods for each run | Nothing | It proves the NetworkPolicy and Pod Security Admission on the running cluster |

### What went wrong on staging

Most runtime defects on staging had one shape: a second mechanism for a thing that one mechanism already does.

- **A bare Pod beside a Job.** The Heph host exited before its channel opened, and the worker deleted the Pod with its cause.
- **One quota for two tiers.** Heph's host and pool held the shared ResourceQuota, and a review could not start.
- **A probe gate beside readiness.** The worker claimed work before the probe passed, so every claim failed.
- **One knob for two workloads.** A turn held the only slot for 3 hours, under a deadline meant for reviews.
- **The API on hot paths.** A datastore stall of 1 minute on a slow disk stopped the lease tick. The tick shared a thread with an API `list()`.
- **A cold start at every layer after a deploy.** One answer took 367 seconds. It waited for a restart, a 2-minute probe, the host start and a cold turn sandbox of 47 seconds.

Today a review and a Heph turn also have these differences:

- Two recovery models: a worker heartbeat, and a lease for each row.
- Two token kinds.
- Three transports: HTTP pull, a WebSocket, and `pods/exec` with tar.
- Three cleanup paths.

## Decision drivers

1. **One contract.** Each difference between a review and a turn is a parameter, not a code path.
2. **Untrusted code with access to the OS runs apart from every credential.** It runs in its own process and under its own user ID. Access to the OS means processes, files and sockets. Repository content, model output and practice scripts are untrusted.
   Model-written code in an interpreter with no host binding may run in a review loop ([decision H](#h-codemode-stays-in-the-review-loop-as-quickjs)). It never runs in a loop that serves more than one attempt.
3. **Workspaces cannot see each other.** SQL tenancy stays the rule (non-negotiable 2). A process that serves several workspaces runs no workspace code.
4. **PostgreSQL is the only truth for work.** Kubernetes holds bounded lifecycles only.
5. **The install stays namespace-scoped,** and hot paths make few API writes. k3s keeps its datastore on the node's disk.
6. **It fits one 16 GiB node by measured use, not by requests.** A person who waits for Heph waits no longer than today.
7. **A crash, an out-of-memory kill or a release costs as little work as possible.** A review runs a median of 10–16 minutes, and at most 3 hours. Each practice is staged when it ends, and the review seals once ([ADR 0063](0063-each-practice-is-staged-when-it-ends.md)). A lost attempt loses the practices that are not staged, and the seal.

## Considered options

The options differ in where the trusted loop runs.
In every option, untrusted tools run apart from the loop, with no credential.

| | Review loop | Heph loop | Review tools | Heph tools |
|---|---|---|---|---|
| **X: an agent Pod for each attempt** | Review Pod | A Job for each turn, from a warm pool | Sidecar | Sidecar, with gateway egress |
| **W: a Heph loop with a relay** | Review Pod | One Deployment | Sidecar | An agent Pod: a relay with a token, plus tools. The gateway brokers each tool call. |
| **W′: a Heph loop that drives a tools Pod** (chosen) | Review Pod | One Deployment | Sidecar | A tools Pod with no token and no egress, driven by the Heph loop |
| **Z: one loop host for every loop** | Shared host | Shared host | Tools Pod | Tools Pod |
| **Y: one loop host for each active workspace** | The workspace's host | The workspace's host | Tools Pod | Tools Pod |

Three off-the-shelf ways to run the Pods were also weighed: an operator, Kueue and kubernetes-sigs/agent-sandbox.

### X: a Heph turn as a Job (rejected)

Each turn takes a warm agent Pod, runs its own loop in it and never returns the Pod to the pool.

- **Latency.** A Pod start comes before the first model call. A pool of P Pods covers arrivals only while P ≥ arrival rate × start time (Little's law). With a measured start of 23–47 seconds, a pool of 2 covers one turn each 12–23 seconds. Ten questions within 30 seconds give 8 cold starts. After each deploy, the pool is empty.
- **Memory.** Each running turn pays its own loop, about 136 MiB. In a shared loop, an extra turn costs about 3–10 MiB. One load test ran 90 turns in 364 MiB.
- **Fit.** With 8 concurrent turns, X holds about 8 reviews on the reference node, against 10 for W′.

### W: a Heph loop with a relay (rejected)

The Heph loop is warm. Each tools Pod also has a relay container that holds the turn's token.
The gateway brokers each tool call between the loop and the relay.

- It adds a trusted program, a pool token, and a tool-call broker in the gateway.
- It puts a token beside untrusted code in every Heph tools Pod.
- Heph's tools get gateway egress, which they do not have today.
- Each tool call makes two extra hops through the gateway, about 5–15 ms.

W′ keeps everything that W gains and drops these four costs.

### Z: one loop host for every loop (rejected)

One long-lived process runs every review loop and every Heph loop. Tools run in tools Pods.

- **Crash domain.** One out-of-memory kill or uncaught exception ends every running review and turn of the install. A poison review crashes the host on each retry, with its neighbours. Fault attribution to one attempt is not possible.
- **Memory accounting.** V8 has one heap for each isolate. A review that grows, for example on a large diff, cannot be limited alone.
- **Deploys.** Each release rolls the host. One choice is a drain of up to 3 hours. Another is cut reviews that restart from zero. The third is a new contract in PostgreSQL to save and resume a review. W′ needs none of these.
- **Heph latency.** Heph's streaming shares one event loop and one garbage collector with the review loops.
- **Memory gain.** Z saves about 160 MiB for each concurrent review: the loop's code, paid once. On the reference node the model call pool binds before memory does (§ [Fit](#fit-by-measured-use)), so this gain does not become throughput. It is the main revisit trigger.

A shared loop is not rejected on tenancy grounds.
Non-negotiable 2 is SQL tenancy.
Such a loop runs no workspace code and reads no local file except its code. It keeps sessions in memory.
Thus, it is in the trust class of the other trusted processes.
That is why the Heph loop of W′ is acceptable.

### Y: one loop host for each active workspace (rejected)

Y saves memory only when one workspace runs 2 or more reviews at once, by an amount that is not measured.
It adds a host lifecycle (start on demand, stop when idle), routing to the workspace's host, and a cap for each host.
Within a workspace, it has Z's crash and deploy costs.

### An operator (rejected)

An operator pays off when the Kubernetes API is the source of truth.
Here PostgreSQL is, by design ([ADR 0025](0025-agent-job-queue-on-postgresql.md), [ADR 0062](0062-the-item-is-the-review-scope.md)).
Admission, fences, billing, erasure and SQL tenancy stay in the ledger.

- A custom resource status would mirror the ledger through a sync with its own failure modes.
- CRDs are cluster-scoped. The install would need cluster-admin, which shared clusters do not give. Helm installs `crds/` once and never upgrades them.
- Each attempt would add a custom resource and several status writes to the k3s datastore.
- A controller adds a Deployment, CRD versions, leader election and a second set of RBAC rules.

The built-in Job, TTL and ResourceQuota controllers do the work. GitLab Runner, the Airflow KubernetesExecutor and the Actions Runner Controller in Kubernetes mode use the same pattern.

### Kueue (rejected)

- **A second queue.** A claimed attempt would wait again, suspended, with a claim and a token. A resumed Job restarts its `activeDeadlineSeconds`, so the two deadlines drift.
- **No fairness across workspaces.** Kueue shares between LocalQueues, one for each namespace. All sandboxes share one namespace on purpose. Fairness across workspaces belongs in the claim query, where the workspace is a column.
- **A waiting person in a queue.** A Heph turn must not wait at admission.
- **Cluster objects.** Cluster-scoped CRDs, webhooks with certificates, a controller of about 512 MiB, and a `Workload` write for each Job. Kueue is not at 1.0.

What W′ takes from it is native: one ResourceQuota for each PriorityClass tier.

### kubernetes-sigs/agent-sandbox (rejected)

- It is built for stateful, idle singletons, with suspend and resume.
- Its `sandboxd` needs ingress into the container that runs untrusted commands.
- It needs a cluster-scoped controller with a ClusterRole over all namespaces.
- It has no Java client.
- W′ needs only its warm pool. The tools Pod pool is a few Pods for each worker.

## Decision

**W′: one attempt contract, one tools program, one tool protocol, and one Job lifecycle for every untrusted Pod. The trusted loop has two placements, one for each workload.**

W′ has no relay, no pool token, no tool-call broker, no hub and no operator.

**W′ keeps the full power of the tools.**
Every runtime attempt gets a full Linux workspace: a file system, bash, git, ripgrep, fd, the language runtimes and toolchains, practice scripts and code execution.
That workspace holds no credential.
The loop reads the captured inputs itself, and every command runs in the credential-free workspace ([Tool placement](#tool-placement)).

```text
 release namespace                                     sandbox namespace
                                                       Restricted PSA · default deny · no Secrets
 server ──▶ PostgreSQL ◀── worker (Role · keys · mirrors · gateway)
                             │  one claim loop · creates Jobs only
                             │
 Heph loop (Deployment) ─────┼─▶ review agent Pod (Job for each review attempt)
   pulls turn assignments    │     ├ init   isolation, workspace
   from the gateway          │     ├ side   tools   UID A · no credential · /workspace read-only
   one AttemptJwt per turn   │     │                serves tool protocol v1 on a Unix socket
        │                    │     └ main   loop    UID B · attempt token in memory · gateway egress
        │                    │
        └── tool protocol v1 ┴─▶ tools Pod (Job for each turn attempt, from a small pool)
            over TCP, dial-out       tools only · no token · no egress · ingress from the Heph loop only
```

### The attempt contract

A review attempt and a Heph turn attempt are both a [runtime attempt](../contributor/practice-review-glossary.mdx#runtime-terms).

- **The row.** `review_job` and `mentor_turn` keep their domain columns. Both carry `attempt` and `leased_until` on the database clock. `mentor_turn` has both today. A new changelog adds `leased_until` to `review_job`, which has only `attempt`.
- **The token.** One `AttemptJwt` with `kind`, `workspace`, `id` and `attempt`. It reaches the model and its own attempt's endpoints only while that attempt holds the lease.
- **The fence.** Every write is conditional on `(id, attempt, lease)`, not on a worker id. Any worker behind the gateway can verify a token and renew its lease. A worker release then cuts no running attempt.
- **The reaper.** One server reaper for both kinds, with a retry limit for each kind. It reaps only a lease that expired more than one renewal interval before its own last successful tick. A disk stall then cannot requeue healthy attempts in bulk.
- **No fault, no spent retry.** A Pod disruption sets the Pod's `DisruptionTarget` condition. A lease lost that way gives a new attempt without a spent retry, at most 3 times.
- **Admission.** The attempt proposes. The server admits. A lost attempt stores nothing.
- **Control without a channel.** A stop, a cancellation, a lost lease and a deadline have one form. The worker cancels the attempt's model calls in flight and answers the next call with `409` and a reason. The WSS hub, its control channel and its pings go.

### The review agent Pod

Each review attempt is one Job with one Pod:

- An **isolation init** container waits until the NetworkPolicy holds.
- A **workspace init** container downloads the captured archive from the gateway.
- The **tools** container is a native sidecar. It runs under its own user ID, with no credential and `/workspace` read-only. It runs bash, git, rg, fd and the practice-script host. It writes only `/tmp` and its spill directory. It serves tool protocol v1 on a Unix socket.
- The **loop** container is trusted. It runs the review program and holds the attempt token in memory.
  It runs Node with `--permission`: a read grant on `/workspace`, a worker grant for codemode ([decision H](#h-codemode-stays-in-the-review-loop-as-quickjs)), and no child-process grant.
  It owns `work/notes`, the sessions and `out/`. The tools side never writes them.
  The loop's own git calls run on the tools side. The loop checks each git output that it treats as authoritative against files that it reads itself.
- The Pod has egress to the gateway only. The two containers do not share a PID namespace.
- Each container has its own memory limit. An out-of-memory kill of the tools never takes the loop. The attempt then ends with a named capture failure, never with a claim about the work.

### Tool placement

Each tool runs where its trust allows it.
Coverage, admission and uploads come only from bytes that the loop read or verified itself.

| Tool | Review agent Pod | Heph |
|---|---|---|
| `read` of captured inputs | The loop. It counts for coverage. | Documents (`INDEX.md`, `FIELDS.md`, `inputs/context/**`): the attempt's memory in the loop. Repositories: the tools Pod. |
| `read` of scratch and spill files (`/tmp/**`) | The tools side. It never counts for coverage. | The tools Pod |
| `ls`, `find` | The loop, over captured inputs, with a Node glob | The tools Pod, through `bash` |
| `grep` | The tools side, as `rg`. It counts for coverage only when the loop verifies the bytes against the pinned file. | `bash` with `rg` |
| `bash` | The tools side, streamed | The tools Pod |
| git for the change basis and pinned blobs | The tools side. The loop re-checks each authoritative output. | — |
| codemode | The loop, as Pi's QuickJS interpreter ([decision H](#h-codemode-stays-in-the-review-loop-as-quickjs)). Its stubs call the tools of this table. | Never |
| Practice scripts | The tools side, `script.run` | — |
| Result, draft, admission and link tools, and `search_conversation` | The loop | The loop |

### The tools Pod

A [tools Pod](../contributor/practice-review-glossary.mdx#runtime-terms) has the tools container of the review agent Pod and no other container.
One Pod builder makes both.

- It is a Job for one Heph turn attempt, in the conversations tier.
- It has no token and no egress. It accepts ingress only from the Heph loop, on the tools port.
- The worker binds it to one attempt in PostgreSQL. It serves that attempt for its whole life and never goes back to the pool.
- The worker writes a nonce for each Pod into the Pod spec. The tools server's `hello` must return that nonce, so a reused Pod IP cannot take the connection.
- The loop serves Heph's documents from the attempt's memory, with Pi's paging and notices. It takes a tools Pod only at the turn's first `bash` or `load_context`, and then puts the documents into it.
- A small pool of started tools Pods hides the start. The chart derives the pool size from Little's law with the measured rate of turns that use `bash` or `load_context`.
- Pooled Pods carry the label of their owner worker. A worker deletes its old pool at start and checks room before it creates a Pod.

### The Heph loop

The [Heph loop](../contributor/practice-review-glossary.mdx#runtime-terms) is one long-lived Deployment that the chart manages, in the roles tier.

- It runs in the **release namespace**, because its assignment credential reaches every workspace's turns. It has no service-account token and Restricted Pod security settings.
- It pulls turn assignments from the gateway and gets one `AttemptJwt` for each turn attempt.
- It runs every turn of the install, one in-memory Pi session each.
- Node's `--permission` grants no file read except the loop's code, and no write except an empty scratch directory. Documents are the attempt's memory, not files. Every command and every repository read runs in the tools Pod.
- It bounds the bytes that it takes from each call, each attempt and each archive. Thus, one turn cannot stop the others by memory.
- It can reach only the gateway and the tools port of tools Pods. It dials out to a tools Pod. It listens on no port that a sandbox Pod can reach.
- A fault in one turn ends that turn only, with its named outcome.
- It updates with RollingUpdate. On SIGTERM it stops new pulls and finishes its turns. The grace period is the turn deadline plus the stop time.
- The new loop is Ready first, so no turn waits for a cold start after a deploy.
- Its code is compiled JavaScript in the agent image, built once in CI. The worker no longer stages it.
- On several nodes, 2 replicas with anti-affinity.

Each attempt has its own workspace ([decision J](#j-heph-keeps-one-workspace-for-each-attempt)). `INDEX.md` lists the repositories that the conversation loaded earlier, with their commit and time, so Heph can load the right one at once.

`ContextChanged` keeps the turn attempt and its session.
The loop deletes the turn's tools Job and takes a fresh one at the next tool call.
An erasure ends the attempt, deletes the Job, and spends no retry.
The worker always deletes the Job, never only its Pod, with `propagationPolicy: Foreground`, so that no replacement Pod starts. Every sandbox Job also has `backoffLimit: 0`.
The deletion ends every tool process and removes `/workspace` and `/tmp` from the node.

### Tool protocol v1

One framing, over a Unix socket in the review agent Pod and over TCP to a tools Pod.

- **Framing.** Each frame is a length-prefixed JSON header with an optional binary body of at most 1 MiB. The loop dials, and the tools side never starts a request. A keepalive runs every 10 s, and a reply missing for 30 s ends the connection.
- **Request ids.** Every request carries an `id`, and every reply and stream frame names it. Up to 8 requests are in flight on one connection, so Pi's parallel tool calls run at once.
- **Operations:**
  - `hello {protocol}` returns the Pod's nonce, the protocol, a boot id and the limits. The loop compares the nonce with the one that the worker wrote into the Pod spec. The loop never sends the nonce, so a process on a reused Pod IP cannot echo it. A changed boot id tells the loop that the tools side restarted.
  - `put {id, dir, size, sha256}` with body frames, from the loop to the tools side only. The tools side extracts the archive read-only, always.
  - `read {id, path, offset, limit}` returns a page of a file inside `/workspace` or `/tmp`, with Pi's notices.
  - `exec {id, argv | command, cwd, timeoutMs, maxOutputBytes}` streams `out` frames, then one `exit` frame. The `exit` frame gives the code, the signal, the time-out, the truncation and the path of the full output. A `command` runs a fresh `bash -c`, with no state kept between calls. An `argv` runs one program with no shell. The environment holds only a fixed allowlist. Each call runs in its own process group. Every process of the call carries the call's marker in its environment.
  - `cancel {id}` ends one call: its process group, and every process of the tools user with its marker.
    The marker also finds a process that left the group with `setsid`.
    The tools side does the same when a call times out or ends, so no call leaves a process behind.
  - `reset` ends every process of the tools user but the tools program, and clears `/tmp`.
    It finds them by user id in `/proc`. A process cannot leave the tools user, so `reset` also ends one that cleared its environment.
    A review uses `reset` after a restart of the tools side or a stall. Heph deletes the Job instead.
  - `script.run {id, slug, inputs}`, for reviews only. Its one callback is `model.call`, under the checks of [ADR 0055](0055-practice-scripts-use-one-result-contract.md).
- **Limits, on both sides.** At most 8 MiB streamed for each `exec`, with the rest in the spill file. At most 64 MiB streamed for each attempt.
  A spill file holds at most 256 MiB, and the call waits while the disk catches up. The `/tmp` volume's `sizeLimit` (`SandboxLayout.SCRATCH_BYTES`, 1 GiB) bounds all spill files and scratch of an attempt together. A Pod past it is evicted, and the attempt ends with a named capture failure. `put` streams with backpressure up to the checkout bound. The loop bounds the bytes that it buffers for each connection.

The loop treats every byte from the tools side as untrusted:

- It caps every frame and every stream.
- It forwards only allowlisted callbacks, for its own attempt, within budget. For a review, that is model calls under ADR 0055 only, never submission or admission. For Heph, the allowlist is empty.
- The tools side cannot write any path that the loop reads for coverage or uploads.

`load_context` is not a tool operation.
The loop fetches a checkout archive from the gateway and writes it with `put`.
The stager and `pods/exec` go.

Rejected for v1:

- **A PTY or a persistent shell.** No review or Heph workload is interactive. A PTY adds escape sequences and terminal state. A persistent shell adds hidden state, such as the directory, the environment and background jobs, that parallel calls race on.
- **MCP.** MCP serves a model-facing tool catalog across vendors, with capability negotiation. Here the tools side is untrusted infrastructure behind a fixed set of tools. v1 takes three ideas from MCP. An error result is data. A path replaces a large inline body. The client treats server annotations as untrusted.
- **Background processes and input to a running command.** No workload runs a server, a watcher or a REPL.

### Network

Tools never get a network route.
Heph's tools have no egress. A review's tools reach only the gateway, which they share with the loop.
Access to the workspace's own repositories is a capability through the gateway.
The worker checks visibility, builds the checkout with its own credential and streams it.
The model names a resource that `INDEX.md` lists, never a host.

An external read that a turn or a review needs becomes a context loader in the worker.
A loader is a fixed catalog that an operator approves. It uses `GET` only, and it takes no URL from the model.

### Capacity

- **One ResourceQuota for each PriorityClass tier:** conversations and reviews. The chart derives both from one request table, which the fit test also reads.
- **The tiers fit the node.** Their sum is at most the allocatable memory minus the platform's measured use. Thus, the conversations tier never preempts a running review.
- **The tier quotas count requests, not limits:** `requests.memory` and `requests.cpu`.
  Sandbox Pods are Burstable, so their limits are above their requests by design.
  For example, a review requests 640 Mi and has a limit of 1,280 Mi.
  A quota on limits would admit about half the reviews that the node holds.
  The scheduler places Pods by their requests, so the fit test checks the sum of requests against the allocatable memory.
  The limits bound each Pod's own peak, and the real headroom of the [fit](#fit-by-measured-use) covers peaks that coincide.
- **The model call pool decides provider throughput.** The model proxy limits calls at the provider quota boundary and keeps an interactive reserve for Heph turns. The reserve does not bypass a shared provider limit.
- **A cap on model calls in flight for each attempt,** at the proxy, for both kinds. No attempt can take more than its share.
- **Review admission checks quota and provider demand** ([decision G](#g-review-pod-admission-checks-quota-and-provider-demand)). It reserves no model call.
- **Informers** for Pods, Jobs and ResourceQuotas. No `list()` call on a claim or lease path.

### Fit by measured use

The reference node has 16 GiB, of which Pods get 14,196 MiB.
All values are in MiB, at measured use, with 1 GiB of real headroom.

| Term | Value | Basis |
|---|---|---|
| Platform at rest, measured | 6,042 | PostgreSQL uses about 950 of it |
| Real headroom | 1,024 | The server's live set grows under load, and peaks coincide |
| **Budget for agent work** | **7,130** | |
| Heph loop | 210 | 136 with one turn, plus about 10 for each real turn |
| 8 concurrent tools Pods in use, and a pool of 4 | 880 | About 90 in use and 40 idle (estimates) |
| **Room for reviews** | **6,040** | |
| Review agent Pod, practice concurrency 3 | 560 | Loop 270, tools 290: the sum of each container's maximum |
| **Reviews that fit** | **10** | 11 with 2 concurrent turns |

The model call pool binds first.
The earlier throughput estimate at a pool of 32 calls and a reserve of 8 was about 9 reviews at practice concurrency 3.
Thus, **about 9–10 reviews and 8 concurrent Heph turns** is an estimate for the benchmark to qualify.
These figures are not a Pod admission rule or a shipped capacity recommendation.

| Option, 8 turns, concurrency 3 | Reviews that fit | Earlier throughput estimate, pool 32, reserve 8 |
|---|---|---|
| X | 8 | 8 (memory) |
| W | 10 | 9 |
| **W′** | **10** | **9** |
| Z | 14 | 9 |
| Y | 9–13 | 9 |

The loop and tools sizes of the split Pod and the tools Pod are estimates.
The footprint benchmark measures them in the layers that build them, and the fit test then reads measured use.

### What goes

- The Heph host, its WebSocket channel, its pings and its gateway session token.
- The turn sandbox, the stager, `ExecSandboxes`, `KubernetesExecSandboxes`, `DockerExecSandboxes` and `pods/exec` in the worker's Role.
- The WSS worker hub and `WorkerControlClient`.
- The `worker_registry` recovery, in the layer that adds the lease to `review_job`.
- Two of three cleanup paths. One rule stays: a Pod whose attempt holds no lease goes. Pooled Pods also go at their owner's start.
- `JobJwt` and `TurnJwt` as separate rules.
- The shared quota and its hand-set values.
- The Docker sandbox backend ([decision D](#d-the-docker-sandbox-backend-goes-before-10)).

### Threat-model changes

Each layer updates the [threat model](../admin/threat-model.mdx) when it ships. The entries change as follows.

| Entry | Change |
|---|---|
| SB-1: privilege escalation and host escape | Every sandbox Pod runs in a user namespace (`hostUsers: false`), so its root is no user of the node ([decision C](#c-user-namespaces-are-required)). gVisor is optional hardening against the shared kernel ([decision E](#e-gvisor-is-optional-hardening)). It does not separate two containers of one Pod, because they share one gVisor kernel. |
| SB-2: secret theft and proxy abuse | One `AttemptJwt` for each attempt, fenced on its lease. A cap on model calls in flight for each attempt. The tools container of a review agent Pod and every Heph tools Pod hold no credential. The Heph loop's assignment credential is a Secret in the release namespace, mounted only in the Heph loop and the worker. |
| SB-3: exfiltration and cross-execution access | A Heph tools Pod has no egress. It accepts ingress only from the Heph loop, on the tools port. A review agent Pod keeps gateway-only egress. The only way out of an attempt is the delivered text, bounded by admission and by composition from admitted records. An injection canary evaluation measures it. |
| SB-4: persistence after execution | One cleanup rule: a Pod whose attempt holds no lease goes. Each Job has a deadline and a TTL. The worker deletes a Pod at the end, on `ContextChanged` and on an erasure, so person data leaves the node disk with the Pod. |
| SB-6: tampered transport | Tool protocol v1 is a trust boundary. The loop caps frames, forwards only allowlisted callbacks for its own attempt, and uploads nothing that the tools side can write. A tools Pod proves its binding with a nonce. |
| SB-7: a cluster that does not hold the boundary | `pods/exec` leaves the worker's Role. The probe checks the tools Pod policy: ingress from the Heph loop only, and no egress. The probe also checks that no sandbox Pod reaches the assignment path. A probe step runs as the user of the review's tools container. An unauthenticated gateway path gets `401`. Every other address and DNS fail. Nothing listens on loopback. User namespaces are required, so the probe no longer reports them as a tier. |
| New: a confused deputy | The loop acts with a token on bytes from the tools side. The callback allowlist bounds what tools can make it do. Coverage comes only from bytes that the loop read or verified. A hostile tools server test proves both on both transports. |
| New: codemode in the review loop | Model-written code runs in Pi's QuickJS interpreter inside the review loop's process, beside the attempt token ([decision H](#h-codemode-stays-in-the-review-loop-as-quickjs)). The interpreter has no file, process or network binding, and a heap cap of 256 MiB. Its only exits are tool stubs. The cost: an escape from QuickJS and then from V8's WebAssembly sandbox gives the attacker that review's token. The token reaches only its own attempt, under the cap on model calls in flight. |
| New: output floods | A tools side that streams without end cannot stop a loop: each `exec`, each attempt and each archive has a byte bound. |
| New: the Heph loop | One trusted loop runs the Heph turns, in the trust class of the other trusted processes. It runs no workspace code, reads no local file except its code, and keeps sessions in memory. A tenancy test runs two turns of different workspaces, each with a hostile tools server and an injected prompt. It asserts that nothing crosses. |

### Glossary

The [practice review glossary](../contributor/practice-review-glossary.mdx#runtime-terms) defines runtime attempt, review agent Pod, tools Pod, Heph loop and tool protocol v1. This ADR does not repeat their definitions.

## Decisions

The maintainer took decision D on 2026-10-27, and every other decision on 2026-10-28, each as recommended.
Each decision keeps the reasons that the recommendation gave.

### A. Adopt W′

Reviews and Heph run on one agent runtime as this ADR describes:

- Each review loop runs in its review agent Pod.
- The Heph loop runs as one Deployment.
- Heph's tools run in tools Pods.

**Decided by the maintainer on 2026-10-28: adopt.**
W′ is the only option with all of these properties:

- A crash domain of one attempt for long work.
- No deploy cost for reviews.
- A warm Heph loop.
- No token beside Heph's tools.

Z's memory gain does not become throughput while the model pool binds first.
[Consequences](#consequences) gives the costs of this decision.

### B. Heph's tools get no network access

A tools Pod has no egress at all. It accepts ingress only from the Heph loop, on the tools port.
A review's tools reach only the gateway.

**Decided by the maintainer on 2026-10-28: adopt.**
A Heph tools Pod holds the developer's private context and untrusted content together. Any external channel then completes an exfiltration path.
A domain allowlist does not close it: a code host, a package registry and DNS can each carry data out.
Coding agents open the network to install and build. Hephaestus's agents install nothing.

Repositories reach the tools through the gateway as a capability.
A new external read becomes a context loader ([Network](#network)).

**Consequence:** a question about a public library that is not captured gets no answer from the web. The revisit trigger counts such questions.

### C. User namespaces are required

Every sandbox Pod sets `hostUsers: false`, and a node without support fails closed with a named reason.
The k3s installer checks the node in two steps. It is the one install-time check, because the Helm chart on k3s or Kubernetes is the only supported deployment.

- Before k3s is installed, a node check that needs no Kubernetes.
  It checks the kernel version, and it creates a user namespace itself.
- After k3s starts and before the chart is installed, a probe Pod with `hostUsers: false`.
The admin guide documents the floor:

- Linux 6.3 or later, for idmapped `tmpfs`.
- A container runtime with support for user namespaces, such as containerd 2.0 or later. k3s ships it.

This excludes the default kernels of Ubuntu 22.04 (5.15) and RHEL 9 (5.14).
Ubuntu 22.04's hardware enablement kernel and Ubuntu 24.04 meet the floor.
Staging moves to a newer kernel.

**Decided by the maintainer on 2026-10-28: adopt.**
The sandbox's root is then no user of the node, at almost no cost.
The second step starts a real Pod with `hostUsers: false`, so it decides on the node, not on a version string. On a cluster that the installer did not set up, the worker's fail-closed start names the reason.

### D. The Docker sandbox backend goes before 1.0

**Decided by the maintainer on 2026-10-27.** Self-hosting in 1.0 is the Helm chart on k3s or Kubernetes only.

The Docker sandbox backend goes in one layer with its replacement. Local development then runs sandboxes on kind or k3d, and the live suites run on the CI k3s and kind clusters.
That layer comes before the review's tools sidecar and the Heph tools Pods, so the new Pod shapes are built once, for Kubernetes.
This amends [ADR 0058](0058-the-helm-chart-is-the-only-supported-deployment-from-1-0.md) § Supported deployment, which kept the driver until a decision after 1.0.

The layer must meet these requirements:

- 5 parallel worktrees on a laptop of 16–32 GiB.
- The live suites on the CI clusters within the CI time budget.
- Inner-loop times against the local Kubernetes baseline.

**Consequence:** the worker in development tests the production NetworkPolicy, which it does not today. The contributor loop needs a local cluster.

### E. gVisor is optional hardening

An installer option installs `runsc` and a RuntimeClass.
gVisor is not the boundary. The boundary is the Pod, its user namespace, its NetworkPolicy and the user ID split inside it.

**Decided by the maintainer on 2026-10-28: adopt as an option.**
gVisor protects the node from a Pod, not one container of a Pod from the other.
Its combination with `hostUsers: false` is not verified. A CI leg on k3s tests it before the option ships.

The footprint benchmark under gVisor runs `rg` over the large fixture, `git log -p -n 200`, the golden review's wall time and the start time.
Many opens and closes of files are slow under gVisor, and agents use these tools most.
Those numbers decide any later default.

### F. Delete the hub, and fence attempts on the lease

The WSS hub goes. Drain and cancellation move to PostgreSQL: a draining flag, `NOTIFY`, and the proxy's refusal of an attempt.
Every attempt is fenced on `(id, attempt, lease)`, not on the worker.

**Decided by the maintainer on 2026-10-28: adopt.**
One recovery model replaces two, and a worker release no longer cuts running attempts.

The upgrade from 0.x to 1.0 stops the old version and then starts 1.0, with no mixed-version window. ADR 0070 (1.0 is a cutover release) sets this rule. Thus, these layers carry no expand and contract for that upgrade.
A rolling upgrade within 1.x still runs two versions at once. The attempt API and tool protocol v1 carry a version, and each side refuses an unknown version with a named reason.

### G. Review Pod admission checks quota and provider demand

A claim that starts a review Pod requires room in the review tier's ResourceQuota.
The call pool of the review's model must have no live background waiter.
Background demand includes review, composition and direct script calls across all workspaces and workers in that pool.
A call waiting in another provider pool does not block the claim.
An unavailable quota observation or a failed pool read prevents the claim.

The worker checks both conditions at the claim transition.
The observation reserves no memory or provider permit.
Kubernetes enforces the ResourceQuota when it admits the Pod.
A concurrent claim or a later call waiter can cause an admitted review to wait for the provider.
Every physical model call still needs its own pool grant.

Claims run started work first, then the workspace with fewer running reviews, then requested, event, scheduled and backfill work with aging, then the oldest work.
Composition has priority, with a bound on consecutive composition grants while review calls wait.
Composition does not start a review Pod.
The existing composition capacity bounds composers before they load inputs.
Each model call still needs a provider grant.
Turns are ordered by the workspace's running turns.

Resident turn admission follows CPU, separately from tools memory and provider calls.
The automatic count is the declared allocatable CPU, less review reservations and resident role requests, divided by 50m per turn.
Role requests include the fixed conversation host and tools quota.
Its ceiling is 32 turns across the worker replicas. An explicit turn count remains the operator's override.
One declaration supplies the allocatable CPU to both this formula and the chart's fit test.

The provider pool admits each call. Its interactive reserve does not cap resident turns.
The conversation tier's existing quota holds eight tools Pods plus two warm Pods per worker.
A turn requests a tools Pod only for a tool call. If the quota is full, the turn waits with a recorded phase duration and visible progress.

Both single-node profiles admit 32 turns and use `Recreate` where Deployment surge would exceed the declared budget.
This trades deploy continuity for fit. The deployment outage counts against the 99.5% availability budget.
It changes no Pod's request or limit.

The 2026-10-30 admission sweep on commit `af3ef372209c07d912a3d9f0354fec148524458f` is the evidence for this ceiling.
Each case completed 40 synthetic users with three turns each.
At 32 turns, first-text p95 was 17.877 seconds and node CPU p95 was 77.26% of four cores.
The observed CPU cost was 0.044–0.051 cores per resident turn, including baseline activity.
The 50m planning coefficient is not a measured marginal cost or a CPU limit.
The replay used a three-second first-token delay and 500 output tokens, with no tools, reviews or throttling.

It does not qualify tool-heavy traffic, sustained arrivals, real provider quotas, or the ten-second first-text objective.
The evidence bundle is `af3-admission-sweep`, with validated `summary.csv` and `phases.csv` receipts.
The SHA-256 of `summary.csv` is `9701a9601c6884062913781500758da2bbb441fbf9e8b352674795ec29d35cad`.
48 turns require the later tool-heavy and 80-user qualification.

**Decision: amended on 2026-10-30.**
Memory quota and provider-call admission are separate resources.
The review Pod count is not calculated from the provider pool size or from practice-session concurrency.
A review claim creates no model call reservation.

### H. Codemode stays in the review loop as QuickJS

Codemode runs in the review loop as Pi's QuickJS interpreter in a worker thread. The loop gets the worker grant and no other new grant.
Heph never gets codemode: its loop serves more than one attempt. Heph runs code with `node`, `jq` and `rg` in its tools Pod.

It rewords decision driver 2.

**Decided by the maintainer on 2026-10-28: adopt.**
Reads inside codemode then keep their coverage, because its stubs call the loop's tools.
Codemode on the tools side would lose that coverage and needs a second host program.

**Consequence:** model-written code runs beside one review's token, inside an interpreter with no host binding. [Threat-model changes](#threat-model-changes) names the escape risk.

### I. Reviews do not run the repository's build or tests

The tools have no package registry, and reviews run no build or test of the repository.
Build evidence comes from the provider's own CI, as a captured source in a separate ADR.

**Decided by the maintainer on 2026-10-28: adopt.**
A build needs a registry, which is an exfiltration path, and it runs the repository's scripts with the tools' authority.
A run without the project's dependencies, secrets and services is not the developer's environment. Its failure would be a capture failure reported as a claim about the work.

**Consequence:** a practice that needs build results waits for the CI source.

### J. Heph keeps one workspace for each attempt

Each Heph turn attempt gets a fresh workspace. There are no tools Pods that stay with a conversation and no snapshots in 1.0.
`INDEX.md` lists what the conversation loaded earlier.

**Decided by the maintainer on 2026-10-28: adopt.**
Person data then never outlives a turn, also after an erasure or a consent change, and turns of one conversation never share files that change.

**Consequence:** a follow-up loads the same repository again. Two metrics, repeated loads and load time, decide whether a checkout cache in the worker is ever worth it.

### K. One context budget and clearing rule for both loops

Each practice's budget gains an input-token dimension beside its calls and output tokens. A practice that spends it ends as not reached, with the reason `budget`.
Inside a review session and a Heph turn, older evidence-tool results become notes when the live context passes a threshold. Draft, result and admission tools stay whole.

**Decided by the maintainer on 2026-10-28: adopt.** Reads, not output, drive the input tokens, and token cost dominates the cost of the Pods.

**Consequence:** a quote that the model needs again is read again. Coverage does not change, because the receipts live in the loop, not in the context. The clearing interacts with prompt caching, so its order follows the study of the review and feedback lifecycles. `unified-pi-runtime.mdx` and `practice-script-runner.mdx` own the rule.

## Order

The pull requests that implement this ADR are the plan. These ordering constraints bind them:

- Each layer ships alone, with its tests, its docs by audience and its changeset.
- A layer that removes a chart value removes it completely. Its release note and its migration fragment name the one step for the operator.
- 1.0 is a full cutover. Each layer deletes the path that it replaces, in the same pull request.
  It adds no shim, no second path and no setting that only warns, and it keeps nothing "for one release".
- The upgrade from 0.x to 1.0 has no mixed-version window. Thus, no layer carries expand and contract for it.
- The Docker backend goes together with local sandboxes on kind or k3d ([decision D](#d-the-docker-sandbox-backend-goes-before-10)).
  This layer comes before the review's tools sidecar and the Heph tools Pods.
- The tools program and tool protocol v1 come before the review's tools sidecar and before the Heph loop Deployment.
- The lease on `review_job` and the deletion of the `worker_registry` recovery are one layer. It comes before the one claim loop and before the fence on the lease.
- The hub goes in the layer that moves drain and cancellation to PostgreSQL.
- The turn sandbox goes with the Heph tools Pods.
- The Heph host and its channel go with the Heph loop Deployment.
- The clearing rule of decision K comes after the study of the review and feedback lifecycles.

Each layer brings these proofs:

- **The cap on model calls for each attempt** brings the input-token budget of decision K. Proof:
  - `PracticeInputBudgetTest`. A practice past its input budget ends as not reached, with the reason `budget`. Its siblings go on.
- **Heph's tools Pods** bring these changes:
  - Tool protocol v1.
  - Documents from the loop's memory, and a Pod only at the first `bash` or `load_context`.
  - The byte bounds on streams and archives.

  Proofs:
  - `ToolProtocolConformanceTest`, on both transports. Four parallel calls of 1 s finish in under 1.5 s. A cancel of one id leaves the other calls running. A detached child dies on cancel and on reset. A stream ends at its bound with a readable spill file. The tools side refuses malformed frames and duplicate ids.
  - `HephDocumentReadsWithoutPodTest`: a turn that reads only documents binds no tools Pod, and a later `bash` sees the same bytes.
  - `HephLoopOutputFloodTest`. One tools Pod streams 1 GiB during 8 turns. That call ends truncated, and the other turns finish. The loop's memory grows by less than 32 MiB.
  - `HephLoopPutStreamingTest`. A checkout of 400 MiB passes through the loop, which grows by less than 32 MiB. The tools side verifies its digest.
  - The load metrics `heph.turn.load.repeat` and `heph.turn.load.duration`, and one week of measured turn tool use on staging.
  - A chart fit test on `small-16gib`. A worker's rolling update fits the node, at about 12.3 of 15.4 GiB requested.
    It fits once no worker has its own Heph pool.
    The profile then moves from `Recreate` to a rolling update, as `small` did.
- **The review's tools sidecar** brings the tool placement, `grep` over `exec`, `find` with a Node glob, and codemode under decision H. Proofs:
  - `ReviewCoverageAuthorityTest`: a hostile tools side that forges a full read gets no coverage, and the same read through the loop does.
  - `ReviewLoopPermissionTest`: under the loop's grants, `read`, `ls`, `find`, `grep` and codemode work, and a process spawn is refused.
  - The golden review on both fixtures: the same admitted results and problems, and the same coverage for each practice.
  - `HostileToolsServerTest`, extended with an unbounded stream, a slow reply, forged ids and a callback outside `script.run`.
  - The isolation probe step from the tools user.
- **The clearing rule** brings an A/B run of the golden review and of a long Heph turn. It must keep the admitted results and lower the input tokens.
- **The gVisor option** brings the benchmark of decision E.
- **A nightly injection canary evaluation** on a live model starts with the review's tools sidecar.
  The corpus plants a canary in an uncited captured file and in another member's Slack message.
  No canary may reach an admitted result, a composed copy, a Slack reply or a Heph answer.

## Consequences

Good:

- One attempt contract, one tools program, one tool protocol, one Pod builder and one cleanup rule.
- A review's crash, out-of-memory kill and exit stay on its own Pod. A release cuts no running review and no running turn.
- Heph's loop is warm after every deploy. A tools Pod start hides behind the first model call.
- Untrusted code runs apart from every credential. Heph's tools have no egress.
- The install stays namespace-scoped, and PostgreSQL stays the only truth.
- The staging defects above lose their structural cause, not only their symptom.

Bad:

- The trusted loop has two placements, one for each workload.
- W′ holds about 4 fewer reviews than Z where memory binds. On the reference node, the model pool binds first.
- A tool call crosses a socket or a TCP connection. For a review it costs microseconds, and for Heph about 0.1–1 ms.
- Required user namespaces exclude older default kernels.
- The removal of the Docker backend changes the contributor loop.
- This is a large change before 1.0. The maintainer accepted that cost.

## Revisit trigger

- **Memory binds before the model pool, measured.**
  The signal has two parts. The model pool minus its reserve feeds more than 40 review calls for each 16 GiB node.
  The loop code takes more than 120 MiB in a bundled runner.
  Then examine a shared review loop again, but only with review loops that can resume.
  Such loops need a checkpoint for each practice, fenced on the lease.
  They also need a tools Pod that outlives its loop, and a quarantine for a poison attempt.
- **Review loops become resumable for their own reasons.** Then most of Z's deploy cost goes.
- **Repeated loads pass 30 % of follow-up loads, with a p95 over 2 s.** Then add a checkout cache in the worker. Tools Pods that stay with a conversation come only if the cache is not enough, measured.
- **More than 2 % of sessions try to run Python.** Then add `python3`, with no package manager, to the tools image.
- **More than 1 % of Heph `bash` calls time out, or a tool needs interaction.** Then reconsider background processes and input to a running command.
- **More than 5 % of Heph turns compact during the turn or reach 40 calls.** Then reconsider subagents for Heph.
- **Questions about public libraries go unanswered, measured.** Then add a context loader for external documentation.
- **Sandboxes must run on clusters that the worker cannot reach.** Then a runner that pulls through the gateway, not an operator.
- **The cluster becomes the record of work.** [ADR 0025](0025-agent-job-queue-on-postgresql.md) and [ADR 0062](0062-the-item-is-the-review-scope.md) keep PostgreSQL as the record.
- **agent-sandbox ships a namespaced mode and pull-only exec.**
- **gVisor numbers exist.** Then decide the default RuntimeClass.

## Sources

Fetched 2026-10-27.

- [Kubernetes Jobs](https://kubernetes.io/docs/concepts/workloads/controllers/job/): `activeDeadlineSeconds`, `ttlSecondsAfterFinished`, `podFailurePolicy`, `managedBy` and `suspend`.
- [Kubernetes sidecar containers](https://kubernetes.io/docs/concepts/workloads/pods/sidecar-containers/): an init container with `restartPolicy: Always`.
- [Kubernetes user namespaces](https://kubernetes.io/docs/concepts/workloads/pods/user-namespaces/): `hostUsers: false`, and the kernel and runtime requirements.
- [Kubernetes Pod disruption conditions](https://kubernetes.io/docs/concepts/workloads/pods/disruptions/#pod-disruption-conditions): `DisruptionTarget`.
- [ResourceQuota scopes](https://kubernetes.io/docs/concepts/policy/resource-quotas/#quota-scopes): `scopeSelector` on `PriorityClass`.
- [Kueue overview](https://kueue.sigs.k8s.io/docs/overview/): the documented release is v0.20.
- [Red Hat RFE-7713](https://issues.redhat.com/browse/RFE-7713): the Kueue controller requests 512 MiB and 500m CPU.
- [Helm custom resource definitions](https://helm.sh/docs/chart_best_practices/custom_resource_definitions/): Helm installs `crds/` once and does not upgrade or delete them.
- [kubernetes-sigs/agent-sandbox](https://github.com/kubernetes-sigs/agent-sandbox).
- [Kubernetes custom resources](https://kubernetes.io/docs/concepts/extend-kubernetes/api-extension/custom-resources/): when to add a custom resource.
- [Heph capacity](../admin/runtime-roles.mdx#conversation-turn-capacity): 90 turns in 364 MiB, and one call in 4 seconds against 8 calls in 34 seconds each.

## Update — 2026-10-28: captured context stays on the loop's side

This update amends § The review agent Pod and § Tool placement for the layer that adds the review's tools sidecar.

- The workspace init container writes the captured context to a volume that only the loop container mounts.
  The tools container mounts the repository checkout and its own scratch, and nothing else.
- `grep` over captured context runs in the loop, in Node. These files are small.
  `grep` over the repository stays on the tools side, as `rg`.
- A practice script gets its declared inputs in each `script.run` request. It does not read captured context from a file.
- The runner knows the layout from its build, not from a setting.
  `SandboxLayout.CONTEXT_IS_LOOP_ONLY` and the runner's `CONTEXT_IS_LOOP_ONLY` start as false.
  ADR 0069's freshness layer or the review agent Pod layer adds them, whichever comes first.
  They become true only in the layer that mounts captured context into the trusted loop alone.
  Removing the Docker backend does not establish that physical boundary.
  A test pins the two constants to each other.
  The runner reads its constant to know which captured kinds a session could read.

There are two reasons:

- **Sound freshness.** A result depends on every source that its session could read (ADR 0069).
  With context on the tools side, `bash` could read every captured kind.
  The freshness of each result then covered all of them.
  Now a session reads captured context only through the loop's tools.
  These tools check each read against the practice's declared sources.
- **Least privilege.** The tools side runs untrusted commands. It now never sees captured pull request text, comments or CI records.

## Update — 2026-10-28: the hub goes without a draining flag or NOTIFY

This update amends [decision F](#f-delete-the-hub-and-fence-attempts-on-the-lease) for the layer that deletes the hub.

- A cancel, a requeue or a reap ends an attempt on its row.
  The proxy checks the row on every call, so it refuses the attempt's next call at once.
- The worker's heartbeat stops the sandbox of each local attempt that its row no longer gives this worker. This takes at most one heartbeat interval.
- A drain is local to the worker. It flips readiness, stops claims and settles the attempts in flight.

A draining flag and a `NOTIFY` channel would have no reader, so the layer adds neither.
