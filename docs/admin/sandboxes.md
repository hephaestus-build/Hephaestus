---
title: Sandboxes
description: The sandbox namespace, its quotas and priorities, the sandbox prerequisites, templates and sizes, and the sandbox isolation gate.
page_type: reference
---

Each practice review and each conversation turn runs in a sandbox Pod.
The server creates a SandboxClaim for it, and the agent-sandbox controller creates the Pod from a template of the chart.
Reviews and turns use the same claim, lease, and gateway.

## Sandbox settings

The chart turns sandboxes off by default. The `small` and `cluster` profiles turn on `sandbox.enabled`.
With sandboxes on, the chart needs `sandbox.namespace` and `networkPolicy.kubernetesApi`, and refuses to render without them.

| Value | What it sets |
| --- | --- |
| `sandbox.enabled` | The server runs practice reviews and conversation turns in sandboxes (`AGENT_ENABLED`). |
| `sandbox.namespace` | The namespace that you created for the sandboxes of this release alone. |
| `sandbox.reviewMemory` | The memory for review sandboxes. The reviews at once are this value divided by one review's memory request and RuntimeClass overhead. |
| `sandbox.conversationTurns` | The conversation turns at once ([Sandbox capacity budget](./helm-chart#sandbox-capacity-budget)). |
| `sandbox.templates.<kind>.resources` | The requests and limits of each sandbox kind ([Sandbox sizes](#sandbox-sizes)). |
| `sandbox.templates.runtimeClassName`, `sandbox.templates.podOverhead` | The RuntimeClass of the sandboxes, and its `overhead.podFixed` as `cpuMillis` and `memoryMi`. |
| `sandbox.runtime.nodeSelector`, `sandbox.runtime.tolerations` | The sandbox nodes. |
| `sandbox.gateway.maxConnections` | The connections that the sandbox gateway of each server holds open. |

Sandboxes call the server on port 8081, the sandbox gateway.
A stopping server takes no new claims, and keeps its gateway open during its [graceful drain](./runtime-roles#graceful-drain).

The chart writes these objects into the sandbox namespace:

- One ResourceQuota for each PriorityClass tier: reviews and conversations.
  Each quota counts Pods, CPU requests, and memory requests. A third quota without a scope counts the disk requests of both tiers.
  Set `sandbox.quota.enabled=false` to manage quotas yourself.
  `priorityClasses.reviews` and `priorityClasses.conversations` must differ.
- A deny-all ingress and egress policy, `<release>-sandbox-deny`.
- The policy `<release>-sandbox-gateway`. Sandbox Pods can reach only the sandbox gateway of the server on port 8081.
- The server's Role and RoleBinding ([Server permissions](#server-permissions)).

The servers remove what no attempt and no server uses in the sandbox namespace.
Do not share it with another release or another application.

## Priorities

For memory eviction, the kubelet first ranks Pods whose usage exceeds their requests.
Within each group, it evicts Pods of a lower priority first.
Thus, priority alone does not protect a stateful Pod that exceeds its request.
The scheduler can also preempt Pods of a lower priority to place one of a higher priority.
The chart gives every Pod one of three priorities, highest first:

1. The server, the webapp, PostgreSQL, the controller, the agent image DaemonSet, monitoring, and the Jobs. They serve everyone.
2. The conversation sandboxes. A person waits for the answer.
3. The review sandboxes. A review is an attempt that runs again. It never preempts another Pod.

A preempted or evicted review counts as an attempt that the server lost, and the review runs again.
See [node-pressure eviction](https://kubernetes.io/docs/concepts/scheduling-eviction/node-pressure-eviction/) for the kubelet's ranking rules.

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

The first tier can preempt any Pod of a lower priority on the same nodes.
With the classes that the chart creates, its priority is 100000.
Give every add-on that shares those nodes, such as a log collector or a metrics stack, a priority of at least that value.
Otherwise a restore or an upgrade can stop it.
The one-host platform runs Traefik and cert-manager as `system-cluster-critical`.

## When the sandbox quota is full

A sandbox Pod waits while its quota has no room.
The reaper takes back a sandbox that did not call the gateway within its start deadline ([Sandbox reaper](/contributor/agent-runs#sandbox-reaper)).
Its run stays queued, and dispatch can request a new sandbox later.
The server does not read Pod events or quotas to admit a run.

## Server permissions

The server reaches the Kubernetes API only at `networkPolicy.kubernetesApi`.
Set the address and port that the network plugin sees after address translation.
The chart has no default: only the running cluster knows this endpoint.

The server's service account has only these permissions in the sandbox namespace:

- SandboxClaims (`extensions.agents.x-k8s.io`): `get`, `list`, `watch`, `create`, and `delete`.
  The controller creates the Sandbox and Pod from the template.
  The server deletes claims that no work needs. It waits for foreground deletion before it acknowledges removal of copied evidence.

The server has no authority over Pods, Jobs, logs, events, or ResourceQuotas.
It cannot run a command inside a Pod.

## Sandbox prerequisites

The sandbox nodes need these items:

1. A network plugin that enforces ingress and egress NetworkPolicy.
2. A sandbox namespace with `pod-security.kubernetes.io/enforce=restricted`.
3. Encrypted node storage. Sandbox workspaces and temporary files use node disk.
   Volume deletion does not erase bytes from a disk. Encryption protects an offline disk, not a compromised running node.
4. A compatible runtime for each template.
   The default runtime needs [user namespace support](https://kubernetes.io/docs/concepts/workloads/pods/user-namespaces/) for `hostUsers: false`.
   A `gvisor` template omits `hostUsers` and uses gVisor's kernel boundary instead.
5. The pinned agent-sandbox controller and its extensions.

The [sandbox isolation gate](#sandbox-isolation-gate) checks items 1, 2 and 4 on your cluster.

## Sandbox templates

With `sandbox.enabled`, the chart installs a `SandboxTemplate` for each sandbox kind in the sandbox namespace.
Their names are `<release>-sandbox-review` and `<release>-sandbox-conversation`.
Each template has a `SandboxWarmPool` of the same name with no replicas.
The agent-sandbox controller creates a sandbox's Pod from the template when a `SandboxClaim` names the warm pool.

The release pins the controller in `k3s-agent-sandbox.yaml`. The one-host platform installs it.
On another cluster, you apply it yourself, or the `cluster` profile runs it in the release namespace.

Claims use `DeleteForeground` at expiry. The claim stays visible until its Sandbox and Pod are gone.

A template's Pod has one container, the agent image, and these settings:

- No ingress, and egress only to the gateway port of the server. The policies of the sandbox namespace set this.
  The template sets `networkPolicyManagement: Unmanaged`, because the controller's default policy allows the internet.
- No DNS. A claim gives the sandbox the gateway's address and the sandbox's credential as environment variables.
- No service account token, a non-root user, no capabilities, and `RuntimeDefault` seccomp.
- A read-only root filesystem. The sandbox writes only to two `emptyDir` volumes: `/workspace` and `/tmp`.
  `HOME` is `/tmp/home`.
  The template's ephemeral-storage limit bounds each volume, and the two volumes together.
- No init container, so that a sandbox starts fast.
- The RuntimeClass `sandbox.templates.runtimeClassName`, or the cluster's default runtime.
  On the default runtime, or another class except `gvisor`, the Pod sets `hostUsers: false`.
  A `gvisor` Pod keeps host users, because gVisor does not support user namespaces ([google/gvisor#13303](https://github.com/google/gvisor/issues/13303)). Its own kernel separates the sandbox from the node kernel.
- The resources of `sandbox.templates.<kind>.resources`.

The driver, the model sessions, commands, Git, and practice programs share the one container.
A command can read the sandbox token. The server checks the subject, current run epoch, lease, and turn state on every write.
The sandbox can reach only the gateway. It has no provider credential and no Kubernetes API credential.

### The agent image on the sandbox nodes

The agent image must be on a node before a sandbox starts there.
A pull of the image from GHCR takes about 7 seconds.
The whole start of a sandbox takes about 1 to 2 seconds.

The DaemonSet `<release>-agent-image` keeps the image on every sandbox node:

- It runs the image that the templates use, by the digest that the release pins.
- It runs on every node that `sandbox.runtime.nodeSelector` and `sandbox.runtime.tolerations` select, with the templates' RuntimeClass.
- A node that joins the cluster gets a Pod of the DaemonSet, which pulls the image.
- At a release change, the DaemonSet rolls and each node pulls the new image.
  `helm upgrade --wait=watcher` waits for the rollout, so the upgrade ends after every sandbox node has the image.
- The kubelet does not remove the image of a running container, so image garbage collection keeps it.
- Each Pod requests 1 millicore of CPU and 16 MiB of memory.

The scheduler can put a sandbox on a node that has just joined, before the node's DaemonSet Pod is ready.
That sandbox pulls the image itself and starts about 7 seconds later.
On a cluster that adds nodes on demand, add sandbox nodes before the demand, or accept a slower first sandbox on a new node.
A registry mirror must serve the agent image by the same digest.

### Sandbox sizes

Each template owns its requests and limits under `sandbox.templates.<kind>.resources`.
These are the defaults:

| Kind | CPU request / limit | Memory request / limit | Disk request / limit |
| --- | --- | --- | --- |
| `review` | 100m / 2 | 512Mi / 2Gi | 1Gi / 4Gi |
| `conversation` | 100m / 2 | 384Mi / 1Gi | 256Mi / 2Gi |

The review quota uses the review template's requests, the RuntimeClass overhead, and `sandbox.reviewMemory`.
The conversation quota uses the conversation template's requests, the RuntimeClass overhead, and the resolved turn count.
A CPU limit is a share of CPU time, not a reserved core, so each template requests a small part of it.

## Sandbox isolation gate

The sandbox isolation gate proves on your cluster that a sandbox reaches the gateway and nothing else.
Run it after each install and upgrade of a release with `sandbox.enabled`, and after a change of the sandbox nodes or the network plugin.
A gate proves enforcement only on the nodes where it runs and at that time.
On a new instance, run it before you connect a workspace, because no work starts before that.

The gate makes a copy of a template that only waits, and starts two sandboxes from the copy.
From inside one of them, it checks:

- The Pod settings of [Sandbox templates](#sandbox-templates), and a user namespace of its own or the gVisor kernel.
- No API credential: no service account directory, no projected token, and no Secret as a volume or a variable.
- A non-root user, `no_new_privs`, and no effective capabilities.
- It reaches the gateway.
- It does not reach the Kubernetes API, by the address of its Service or by any address behind that Service.
- It does not reach the cluster DNS server, the node's kubelet, or the other sandbox.
- It does not reach the cloud metadata address, the internet, or a public DNS server.
- Its container stops reaching the Kubernetes API within 10 seconds of its start. A network plugin can start a Pod a moment before the Pod's policies hold.

### Before you start

- The release is installed with `sandbox.enabled`, and the server is ready.
- A checkout of the Git tag of the installed release, for example `git clone --depth 1 --branch vX.Y.Z https://github.com/hephaestus-build/Hephaestus.git`.
- Node.js at the version that `package.json` of the checkout names. The gate needs no installed packages.
- `kubectl` with a context for the cluster.
- These rights:
  - In the sandbox namespace: `get`, `create` and `delete` on SandboxTemplates, SandboxWarmPools and SandboxClaims.
  - In the sandbox namespace: `list` and `watch` on SandboxClaims, `get` on Pods, and `create` on `pods/exec`.
  - In `default`: `get` on the Service `kubernetes`, and `list` on EndpointSlices.
  - `get` on the cluster DNS Service.

### Run the gate

1. Go to the checkout.
2. Run the gate for each template:

   ```bash
   for kind in review conversation; do
     node scripts/sandbox-isolation.ts --namespace hephaestus-sandboxes --template "hephaestus-sandbox-$kind" \
       --gateway "$(kubectl -n hephaestus get service hephaestus-sandbox-gateway -o jsonpath='{.spec.clusterIP}'):8081" \
       --forbid "the server's application port=$(kubectl -n hephaestus get service hephaestus-server -o jsonpath='{.spec.clusterIP}'):8080" \
       || break
   done
   ```

   For another release name or namespace, change `hephaestus` and `hephaestus-sandboxes` in the command.

These options change what the gate tries:

| Option | Effect |
| --- | --- |
| `--reach <name>=<host>:<port>` | One more address that a sandbox must reach. |
| `--forbid <name>=<host>:<port>` | One more address that a sandbox must not reach. |
| `--dns <namespace>/<name>` | The cluster DNS Service, if it is not `kube-system/kube-dns`. On RKE2, it is `kube-system/rke2-coredns-rke2-coredns`. |
| `--context <name>` | The `kubectl` context. |

### Expected output

For each template, the gate prints one JSON line of what the sandbox saw, then this line, and exits with status 0:

```text
The sandbox isolation gate passed for hephaestus-sandboxes/hephaestus-sandbox-review
```

The gate deletes its sandboxes, its warm pool and its template copy when it ends, also after a failure.

### If the gate fails

The gate exits with a non-zero status.
It prints `the sandbox isolation gate failed for <namespace>/<template>:`, then one line for each failure.
Do not connect a workspace until the gate passes.

| Failure line | Cause | Fix |
| --- | --- | --- |
| `reaches <target>`, `the cluster DNS resolver answers`, or `a public DNS resolver answers` | The network plugin does not enforce the NetworkPolicies of the sandbox namespace. | Use a network plugin that enforces egress policy, also to Service addresses. |
| `still reached the Kubernetes API 10 seconds after its start` | The network plugin applies a Pod's policies late. | Use a network plugin that applies policies before or soon after the container starts. |
| `cannot reach the gateway` | The server is not ready, the `--gateway` address is wrong, or the plugin blocks the allowed path. | Check that the server is ready and that the address is the `<release>-sandbox-gateway` Service on port 8081. |
| `no user namespace of its own` | The node runtime has no user namespace support. | Use nodes with user namespace support, or a gVisor RuntimeClass in `sandbox.templates.runtimeClassName`. |
| `a Kubernetes token is mounted`, `runs as`, `effective capabilities`, or a Pod setting | The template differs from the chart's template. | Upgrade the release again with the verified chart, so that the chart writes its templates. |
| `kubectl wait` times out on a `sandboxclaim` | The controller did not start the sandbox: it is not running, the quota is full, or the node cannot pull the agent image. | Check the controller's log and `kubectl -n <sandbox namespace> get pods,resourcequota`. |
| An error that names the DNS Service | The cluster DNS Service has another name. | Give it with `--dns`. |
