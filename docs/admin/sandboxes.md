---
title: Sandboxes
description: The worker, the sandbox namespace, its quotas and priorities, the sandbox prerequisites, and the sandbox templates and sizes.
page_type: reference
---

Each practice review and each conversation tool call runs in a sandbox Pod.
This page describes the worker that runs them, the objects that the chart writes into the sandbox namespace, and what the sandbox nodes need.

## Worker

The worker role runs every review sandbox and every tools Pod. The [conversation loop](./helm-chart#conversation-loop) runs with the workers.
The chart's default disables the worker, and `small.yaml` enables it.
Prepare the [sandbox prerequisites](#sandbox-prerequisites).
Set `worker.sandbox.namespace` and `networkPolicy.kubernetesApi` ([Install](./kubernetes#install), step 4).
The chart refuses a worker without them.

The chart runs the workers as one Deployment:

- Two replicas by default, spread across nodes where the cluster has several. `worker.replicas` sets the count.
- A rollout starts all new workers before an old one stops. At two or more workers, a PodDisruptionBudget lets a node drain evict one at a time. A singleton worker blocks voluntary eviction until [planned node maintenance](./maintenance#planned-node-maintenance). `worker.rolloutStrategy=Recreate` stops the old workers first, for a node that cannot hold both ([Profiles](./helm-chart#profiles)).
- A stopping worker hands out no new conversation turns. Kubernetes gives it `worker.drainTimeoutSeconds`, 1800 by default, plus 2 minutes to stop.
- A worker on a lost node is replaced after 60 seconds.
- `AGENT_ENABLED=true` on the workers and the server, so that the server submits practice reviews and the workers run them. Without a worker, the server submits none.
- `worker.sandbox.reviewMemory` sets the memory that the sandbox nodes give review Pods, 3840 MiB by default. The reviews that run at once are that memory over one review Pod's request and `worker.runtime.podOverhead`: 10 by default, five in `small.yaml`. Each worker runs as many of them as its memory limit holds, and the reviews quota holds them once. `worker.conversationTurns` sets the resident conversation turns (`HEPHAESTUS_WORKER_CAPACITY_MENTOR_MAX`, eight by default). Tools start on demand within the separate conversations quota ([Runtime roles § Conversation turn capacity](./runtime-roles#conversation-turn-capacity)). The `small` profile selects `auto` ([Profiles](./helm-chart#profiles)).
- The sandbox namespace and the Pod name and address from the Downward API ([Runtime roles § Sandbox runtime](./runtime-roles#sandbox-runtime)).
- The existing gateway setting `SANDBOX_API_PORT`, with the value 8081.

The sandbox namespace is the one that you created for this release.
The chart creates no namespace.
It writes these objects into it:

- One ResourceQuota for each PriorityClass tier: reviews, and conversations for the tools Pods.
  The chart derives both from `sandbox-pod-requests.json` and from `worker.sandbox.reviewMemory`, `worker.replicas` and `conversationLoop.turns`.
  The conversations quota holds the tools Pods of the whole installation, not of each worker.
  They count requests, not limits: sandbox Pods may use more than they request, up to their limits.
  Thus, Hephaestus's sandboxes never take a review's room, and reviews never take Hephaestus's.
  Each tier's quota counts Pods, CPU and memory. Kubernetes does not let a quota that selects a PriorityClass count disk.
  The quota without a scope counts the disk requests of both tiers together.
  A quota also counts the Pod overhead of a RuntimeClass, such as gVisor's on some clusters.
  For such a class, set `worker.runtime.podOverhead` to its `overhead.podFixed`: `cpuMillis` and `memoryMi`.
  The chart then adds the overhead to each Pod in the quotas.
  Set `worker.sandbox.quota.enabled=false` to manage the quotas yourself.
  `priorityClasses.reviews` and `priorityClasses.conversations` must name different classes, because each quota selects its Pods by class.
  During a rolling update, the chart's quotas hold the workers of the new and the old version.
  A worker that still drains during a second update can find no room.
  Its sandbox then waits and is tried again.
  A worker creates a tools Pod only when every quota that counts the Pod has room for it.
  An unscoped quota counts it, and so does a quota that selects the Pod's PriorityClass.
  The worker's Role lets it list the quotas.
  Its permissions are listed below.
  It reads the Pod overhead of the sandbox RuntimeClass from its own sandbox Pods.
  A quota with another scope does not hold a tools Pod back. A quota that the worker cannot read does not hold it back either.
  Another worker can still take the room first. The quota then refuses the Pod.
  The dispatch of review sandboxes does not read the quotas.
- A deny-all policy for ingress and egress, `<release>-sandbox-deny`.
- One policy, `<release>-sandbox-gateway`, that lets review sandboxes reach port 8081 of the worker Pods, and nothing else.
  Those sandboxes carry the label `hephaestus.build/sandbox-kind` from the runtime.
  The worker policy admits the same sandboxes on the same port.
- One policy for the conversation's tools Pods, which have the label `hephaestus.build/sandbox-kind=tools`.
  `<release>-sandbox-tools` admits ingress to tools Pods on TCP port 7100, only from the conversation loop's Pods in the release namespace.
  Tools Pods get no egress.
- The worker's Role and RoleBinding.

## Priorities

For memory eviction, the kubelet first ranks Pods whose usage exceeds their requests.
Within each request-usage group, it evicts Pods of a lower priority first.
Thus, priority alone does not protect a stateful Pod that exceeds its request.
The scheduler can also preempt Pods of a lower priority to place one of a higher priority.
The chart gives every Pod one of three priorities, highest first:

1. The roles, the conversation loop, PostgreSQL, NATS, and the migration and backup Jobs. They serve everyone.
2. The tools Pods of conversations. A person waits for the answer.
3. The review sandboxes and the isolation probe. A review is an attempt that runs again. It never preempts another Pod.

A preempted or evicted review counts as an attempt that the worker lost, and the review runs again.

See [node-pressure eviction](https://kubernetes.io/docs/concepts/scheduling-eviction/node-pressure-eviction/) for the kubelet’s ranking rules.

By default, the chart creates the three PriorityClasses.
If your account cannot create cluster-wide objects, ask the cluster's administrator for three classes, with values in the order above.
Then set `priorityClasses.create=false` and name them:

```yaml title="values.yaml"
priorityClasses:
  create: false
  roles: platform-high
  conversations: people-waiting
  reviews: batch-low
```

The chart refuses a name while `create` is true, and a missing name while it is false.

The roles can preempt any Pod of a lower priority on the same nodes.
With the classes that the chart creates, the roles' priority is 100000.
Give every add-on that shares those nodes, such as a log collector or a metrics stack, a priority of at least the roles' value.
Otherwise a restore or an upgrade can stop it.
The one-host platform runs Traefik and cert-manager as `system-cluster-critical`.

The workers remove what no attempt and no worker uses in the sandbox namespace.
Do not share it with another release or another application.

## When the sandbox quota is full

When the quota has no room for the Pod of a review sandbox, the Pod does not start.
The reaper takes back each sandbox that did not call the gateway within 2 minutes of its request ([Agent runs § Sandbox reaper](/contributor/agent-runs#sandbox-reaper)).
The review's run stays queued, and the dispatch requests a new sandbox at a later pass.

The worker learns from the events of the sandbox namespace when the quota refuses a tools Pod or an isolation probe Pod.
It reads its quotas to tell whether a tools Pod can fit at all.
It needs `list` on `events`, and `list` and `watch` on `resourcequotas` there. The chart's Role grants them.
Without `list` on `events`, the worker logs one warning, and it waits for a refused Pod until the start timeout.
Without `list` and `watch` on `resourcequotas`, `pods` or `jobs`, the worker stays not ready ([Troubleshooting](./kubernetes#troubleshooting)).

The worker watches the Pods, Jobs and quotas of the sandbox namespace.
Thus, a quota check and a readiness probe send no request to the API server.

The worker reaches the Kubernetes API only at `networkPolicy.kubernetesApi`.
Set the address and port that the selected network plugin sees after address translation.
The plugins that CI runs, k3s's kube-router and Calico, see the endpoint of the `kubernetes` Service ([Install](./kubernetes#install), step 4).
The chart has no default for them, because only the running cluster knows them.

The worker's service account has these permissions in the sandbox namespace, and none elsewhere:

- Pods: `get`, `list`, `watch`, and `create`. The worker creates Pods only through Jobs. The isolation probe uses `create` for a Pod Security dry run, which creates nothing.
- `pods/log`: `get`.
- Jobs: `get`, `list`, `watch`, `create`, and `delete`. Each tools Pod and each isolation probe Pod is a Job. A cancellation or a timeout deletes the Job, and Kubernetes deletes its Pod before the Job goes.
- Events: `list`. The Job controller reports a Pod that the sandbox quota refuses as an event.
- ResourceQuotas: `list` and `watch`. The quota tells whether a tools Pod fits now and whether it can ever fit.
- SandboxClaims (`extensions.agents.x-k8s.io`): `get`, `list`, `watch`, `create`, and `delete`. A subject's sandbox is a claim, and the agent-sandbox controller creates its Pod from the kind's template. The worker deletes each claim that no work needs. It watches foreground deletion until the sandbox and Pod are gone.

The worker cannot patch or delete a Pod, and it cannot run a command in one.
A patch could change the image of a sandbox that runs.

Kubernetes removes a sandbox Job 15 minutes after it ends.
The review-ledger worker identity stays per process.
The chart does not set `HEPHAESTUS_WORKER_ID`.

## Sandbox prerequisites

The sandbox nodes need these items:

1. A network plugin that enforces NetworkPolicy, for egress as well as ingress.
2. The sandbox namespace with the label `pod-security.kubernetes.io/enforce=restricted`.
3. Encrypted node storage, for example LUKS or the disk encryption of the cloud provider.
   A sandbox Pod writes to the node's disk: the review workspace with the repository, `/tmp` and `~/.local`.
   A tools Pod's workspace is in its `/tmp`.
   Only `/run` is in memory.
   Encryption protects a stolen disk and a backup of it without the key.
   It does not protect against a compromised running node or a privileged workload on the node.
   The kubelet deletes a Pod's volumes with the Pod, but deletion does not erase the data from the disk.

4. [User namespace support](https://kubernetes.io/docs/concepts/workloads/pods/user-namespaces/) on every sandbox node.
   It needs Linux 6.3 or later and a compatible container runtime, such as containerd 2.0 or later.
   Every sandbox and probe Pod sets `hostUsers: false`.
   The worker must prove that a real Pod has a private user mapping before it starts sandboxes.

These settings are optional:

- `worker.runtime.runtimeClassName`: a RuntimeClass for sandbox Pods. Use only a RuntimeClass that supports `hostUsers: false` and passes the isolation probe. gVisor (`runsc`) with user namespaces is not qualified. To upgrade an install that uses it, clear this setting to use the default runtime, or qualify a compatible runtime.
- `worker.runtime.podOverhead`: the RuntimeClass's `overhead.podFixed`, as `cpuMillis` and `memoryMi`. The sandbox quotas count it with each Pod.
- `worker.runtime.seccompProfile`: the bundled `sandbox/agent-seccomp-profile.json` as a Localhost profile. Put the file below the kubelet's seccomp directory on every sandbox node, and set its relative path. Without it, sandbox Pods use `RuntimeDefault`.
- `worker.runtime.nodeSelector` and `worker.runtime.tolerations`: the nodes of sandbox Pods.
- A kubelet `podPidsLimit` on the sandbox nodes.

Each worker runs the isolation probe in the sandbox namespace at its start, and then every `worker.runtime.probeInterval`, 15 minutes by default.
The probe checks these items on the running cluster:

- The API server refuses, in a dry run, a Pod that the Baseline standard admits and the Restricted standard refuses.
- A Pod with the labels of a review sandbox reaches the worker's gateway port, directly and through a Service.
  It does not reach the worker's management port, the Kubernetes API, or the cluster DNS server.
- A Pod of no sandbox kind reaches the gateway port by neither route. A tools Pod has no egress either.
- The user of a review's `tools` container reaches the gateway port by neither route, though the Pod's policy admits it.
- The probe Pods have no service account token, a read-only root filesystem, and a non-root user.
- A named RuntimeClass exists: without it, no probe Pod runs.
- A Pod with `hostUsers: false` has a private user mapping. A RuntimeClass does not replace this requirement.

The worker starts no sandbox while one of these items fails.
At its start, the worker then logs each failed item and stops, and Kubernetes starts it again.
On a later probe, it logs each failed item, reports itself not ready, and starts no new sandbox until a probe passes again.

Until a probe passes, at the start and after a failed later probe, the worker is not ready.
It claims no practice review and no conversation turn.
They stay queued for a ready worker. Reviews and turns that already run continue.

A Pod that reaches a node but cannot start with the sandbox configuration fails the probe.
A later probe whose Pods cannot start, because the sandbox quota is full or the nodes have no room, proves nothing.
The same is true when the worker cannot resolve the name of its gateway probe Service.
The worker logs a warning and keeps its last passing proof.
A Pod that the sandbox quota refuses delays that verdict by up to 2 minutes.
The tools probe runs after the other probes, so a refusal of both delays the verdict by up to 4 minutes.

The probe also records the runtime and process limit, which are optional:

| Item | Effect |
| --- | --- |
| RuntimeClass | The runtime that isolates sandboxes from the node kernel, or the cluster's default. |
| Process limit | The number of processes that a sandbox Pod could start before the node refused one, or no limit. |

The worker's log and the metrics `sandbox.isolation.passed`, `sandbox.isolation.user_namespaces` and `sandbox.isolation.process_limit` show the tier.
The probe Pods use the agent image and the sandbox configuration.
They prove the nodes on which they run, not every eligible node. Make sure that every node selected for sandboxes meets the prerequisites.

Each probe Pod is the Pod of a Job.
A run takes 640 MiB of the sandbox quota on the cluster's default runtime.
That is 512 MiB for the Pod that tries 1024 processes, and 128 MiB for the other Pod.
The worker deletes the Jobs when the probe ends.
If the worker stops during a run, the Job's deadline ends a Pod that still waits for a node.
Kubernetes deletes the finished Job with its Pod after 15 minutes.

Some network plugins apply the policy of a new Pod a few seconds after the Pod starts.
Each sandbox Pod therefore starts with an init container that waits, at most 60 seconds, until it cannot reach the Kubernetes API.

## Sandbox templates

With the worker enabled, the chart installs a `SandboxTemplate` for each sandbox kind in the sandbox namespace.
Their names are `<release>-sandbox-review` and `<release>-sandbox-conversation`.
Each template has a `SandboxWarmPool` of the same name with no replicas.
The agent-sandbox controller creates a sandbox's Pod from the template when a `SandboxClaim` names the warm pool.
The release pins the controller in `k3s-agent-sandbox.yaml`: the one-host platform installs it, and on another cluster you apply it yourself ([Install](./kubernetes#install)).

Claims use `DeleteForeground` at expiry. The claim stays visible until its Sandbox and Pod are gone.

A template's Pod has one container, the agent image, and these settings:

- No ingress, and egress only to the worker's gateway port. The policies of the sandbox namespace set this.
  The template sets `networkPolicyManagement: Unmanaged`, because the controller's default policy allows the internet.
- No DNS. A claim gives the sandbox the gateway's address and the sandbox's credential as environment variables.
- No service account token, a non-root user, no capabilities, and `RuntimeDefault` or the configured seccomp profile.
- No volumes and no init container, so that a sandbox starts fast. The workspace is in the container's writable layer, and the template's ephemeral-storage limit bounds it.
- The RuntimeClass `worker.sandbox.templates.runtimeClassName`, or the cluster's default runtime.
  On the default runtime, or another class except `gvisor`, the Pod sets `hostUsers: false`.
  A `gvisor` Pod keeps host users, because gVisor does not support user namespaces yet ([google/gvisor#13303](https://github.com/google/gvisor/issues/13303)). Its own kernel separates the sandbox from the node kernel.
- The resources of `worker.sandbox.templates.<kind>.resources`.

### The agent image on the sandbox nodes

The agent image must be on a node before a sandbox starts there.
A pull of the image from GHCR takes about 7 seconds.
The whole start of a sandbox takes about 1 to 2 seconds.

The DaemonSet `<release>-agent-image` keeps the image on every sandbox node:

- It runs the image that the templates use, by the digest that the release pins.
- It runs on every node that `worker.runtime.nodeSelector` and `worker.runtime.tolerations` select.
  It uses the templates' RuntimeClass, so it reaches every node that can run a sandbox.
- A node that joins the cluster gets a Pod of the DaemonSet, which pulls the image.
- At a release change, the DaemonSet rolls and each node pulls the new image.
  `helm upgrade --wait=watcher` waits for the rollout, so the upgrade ends after every sandbox node has the image.
- The kubelet does not remove the image of a running container, so image garbage collection keeps it.
- Each Pod requests 1 millicore of CPU and 8 MiB of memory.

The scheduler can put a sandbox on a node before the node's DaemonSet Pod is ready.
That sandbox pulls the image itself and starts about 7 seconds later.
This can happen only on a node that has just joined.
The scheduler prefers nodes that have the image already.
On a cluster that adds nodes on demand, add sandbox nodes before the demand, or accept a slower first sandbox on a new node.

A registry mirror must serve the agent image by the same digest.
CI starts a sandbox on a new node after the DaemonSet Pod is ready there, and checks that the kubelet pulls nothing for it.

`node scripts/sandbox-isolation.ts` is the sandbox isolation gate.
It makes a copy of a template that only waits, starts two sandboxes from the copy, and checks from inside one of them:

- The Pod settings above. The Pod has a user namespace of its own, or the gVisor kernel.
- No API credential: no service account directory, no projected token, and no Secret as a volume or a variable.
- No Service variables but the API's `KUBERNETES_*`, which the kubelet always sets. The checks below prove that the API cannot be reached.
- A non-root user, and no effective capabilities.
- It reaches the gateway.
- It does not reach the Kubernetes API, by the address of its Service or by any address behind that Service.
- It does not reach the cluster DNS server, the node's kubelet, or the other sandbox.
- It does not reach the cloud metadata address, the internet, or a public DNS server.
- Its container stops reaching the Kubernetes API within 10 seconds of its start.

A network plugin can start a Pod a moment before the Pod's policies hold.
On k3s, a new sandbox reaches the Kubernetes API for about the first 0.2 seconds.
The gate's sandbox records when its connections to the API stop.

CI runs the gate for each template on every cluster that it installs the chart on.
There, a sandbox must also reach each worker Pod on the gateway port.
It must not reach any server or worker Pod on the application port 8080.
Then CI deletes the NetworkPolicies of the sandbox namespace, and the gate must fail because a sandbox reaches the other sandbox.
CI restores the policies afterward. A gate that passes without the policies cannot show that they hold, for example on a network plugin that does not enforce NetworkPolicy.

Add an address that a sandbox must reach with `--reach <name>=<host>:<port>`, and one that it must not reach with `--forbid <name>=<host>:<port>`.
The gate names each address in its output.
The gate reads the cluster DNS Service `kube-system/kube-dns`.
On a distribution that names it otherwise, give it with `--dns <namespace>/<name>`, for example `--dns kube-system/rke2-coredns-rke2-coredns` on RKE2.

```bash
node scripts/sandbox-isolation.ts --namespace hephaestus-sandboxes --template hephaestus-sandbox-review \
  --gateway "$(kubectl -n hephaestus get service hephaestus-sandbox-gateway -o jsonpath='{.spec.clusterIP}'):8081" \
  --forbid "the server's application port=$(kubectl -n hephaestus get service hephaestus-server -o jsonpath='{.spec.clusterIP}'):8080"
```

### Sandbox sizes

The worker sizes each sandbox Pod from the measured use of that kind of sandbox.
The Pod-kind table owns the memory and storage sizes.
No per-review memory or process setting overrides it.

| Sandbox Pod | CPU request | Memory request | Memory limit | Disk request | Size limit of each disk volume | Disk limit |
| --- | --- | --- | --- | --- | --- | --- |
| Review | 50m | 384 MiB | 1,280 MiB | 1 GiB | 1 GiB, 7 volumes | 7 GiB and 64 MiB |
| Tools Pod | 25m | 160 MiB | 512 MiB | 256 MiB | 512 MiB, 1 volume | 512 MiB and 64 MiB |

The review row counts the whole Pod.
Its `loop` container requests 312 MiB, 30m and 992 MiB of disk and has the memory limit of the table.
Its `tools` container requests 32 MiB, and its `programs` container requests 40 MiB.
Each of these two requests 10m and 16 MiB of disk, and has a limit of 768 MiB and 500m.
A review has `programs` only when it runs a custom practice program.

A review has seven volumes on the node's disk: the workspace, `work`, `out`, `.pi`, `.sessions`, `/tmp` and `~/.local`.
A tools Pod has one: `/tmp`, which holds the tools workspace `/tmp/tools/workspace`.
The disk limit of the main container holds all the volumes full and 64 MiB for its logs.
Each other container, such as the isolation init container, has a disk limit of 16 MiB for its logs.

The tools Pod and each probe Pod request a small part of their CPU limit.
The isolation init container requests 10m.
The chart's sandbox quotas count the requests of this table, not the limits.
Four large reviews at once peaked at 632 to 657 MiB each.

`/run` is in memory, with 16 MiB.
The kubelet evicts a Pod whose volume passes its size limit, or whose disk use passes its disk limit.
Thus, no sandbox can fill the node's disk and cause the eviction of other Pods.
The worker counts the eviction as a failure of the infrastructure, and the review spends a retry.
A review whose sandbox keeps filling its disk ends at `AGENT_MAX_RETRIES`.
The chart's sandbox quota without a scope counts the disk requests of all sandbox Pods.

The sandbox uses the cluster runtime and its volume options.
`/tmp`, with a tools Pod's workspace, and `/run` are not `noexec`, and a sandbox Pod keeps `/dev/shm` in its private IPC namespace.
[ADR 0041](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0041-compose-1x-kubernetes-2.md#update--2026-10-24-the-kubernetes-runtime) records why.

Every container command starts through `prlimit` from the agent image.
`prlimit` sets the `nofile` limit to 1024 and the `core` limit to 0, both soft and hard.
A sandbox process cannot raise them.
