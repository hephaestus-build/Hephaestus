---
title: Sandboxes
description: The server, the sandbox namespace, its quotas and priorities, the sandbox prerequisites, and the sandbox templates and sizes.
page_type: reference
---

Each practice review and each conversation turn runs in a subject's sandbox Pod.
The server owns its SandboxClaim. Reviews and turns use the same claim, lease, and gateway.

## Server

The chart disables sandboxes by default. The `small` and `cluster` profiles enable `sandbox.enabled`.
Prepare the [sandbox prerequisites](#sandbox-prerequisites).
Set `sandbox.namespace` and `networkPolicy.kubernetesApi` ([Install](./kubernetes#install)).
The chart refuses enabled sandboxes without both settings.

The server also serves the API, syncs provider work, and delivers feedback.
`server.replicas` is capped at one. It uses `Recreate` until provider events have ordering across replicas.
A stopping server takes no new claims. It keeps its gateway available within the [review drain window](./runtime-roles#graceful-drain).
Sandbox calls use port 8081.

`sandbox.reviewMemory` sizes the review quota.
`sandbox.conversationTurns` sets turn capacity ([Conversation turn capacity](./runtime-roles#conversation-turn-capacity)).
The conversation quota uses the requests in `sandbox.templates.conversation.resources`.
It also counts the RuntimeClass overhead from `sandbox.templates.podOverhead`.

The operator creates the sandbox namespace for this release alone.
The chart writes these objects into it:

- One ResourceQuota per PriorityClass tier: reviews and conversations.
  Each quota counts Pods, CPU requests, and memory requests. The quota without a scope counts both tiers' disk requests.
  It counts actual sandbox requests. No tools Pod, isolation probe, or conversation-loop reservation remains.
  Set `sandbox.quota.enabled=false` to manage quotas yourself.
  `priorityClasses.reviews` and `priorityClasses.conversations` must differ.
- A deny-all ingress and egress policy, `<release>-sandbox-deny`.
- The policy `<release>-sandbox-gateway`. Sandbox Pods can reach only the server gateway on port 8081.
- The server's Role and RoleBinding. They grant only SandboxClaim authority, as listed below.

## Priorities

For memory eviction, the kubelet first ranks Pods whose usage exceeds their requests.
Within each request-usage group, it evicts Pods of a lower priority first.
Thus, priority alone does not protect a stateful Pod that exceeds its request.
The scheduler can also preempt Pods of a lower priority to place one of a higher priority.
The chart gives every Pod one of three priorities, highest first:

1. The roles, PostgreSQL, NATS, and the migration and backup Jobs. They serve everyone.
2. The conversation sandboxes. A person waits for the answer.
3. The review sandboxes. A review is an attempt that runs again. It never preempts another Pod.

A preempted or evicted review counts as an attempt that the server lost, and the review runs again.

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

The servers remove what no attempt and no server uses in the sandbox namespace.
Do not share it with another release or another application.

## When the sandbox quota is full

A sandbox Pod waits while its quota has no room.
The reaper takes back a sandbox that did not call the gateway within its start deadline ([Sandbox reaper](/contributor/agent-runs#sandbox-reaper)).
Its run stays queued. Dispatch can request a new sandbox later.
The server does not read Pod events or quotas to admit a run.

The server reaches the Kubernetes API only at `networkPolicy.kubernetesApi`.
Set the address and port that the network plugin sees after address translation.
The chart has no default: only the running cluster knows this endpoint.

The server's service account has only these sandbox-namespace permissions:

- SandboxClaims (`extensions.agents.x-k8s.io`): `get`, `list`, `watch`, `create`, and `delete`.
  The controller creates the Sandbox and Pod from the template.
  The server deletes claims that no work needs. It watches foreground deletion before it acknowledges removal of copied evidence.

The server has no authority over Pods, Jobs, logs, events, or ResourceQuotas.
It cannot run a command inside a Pod.

## Sandbox prerequisites

The sandbox nodes need these items:

1. A network plugin that enforces ingress and egress NetworkPolicy.
2. A sandbox namespace with `pod-security.kubernetes.io/enforce=restricted`.
3. Encrypted node storage. Sandbox workspaces and temporary files use node disk.
   Volume deletion does not erase bytes from a disk. Encryption protects an offline disk, not a compromised running node.
4. A compatible runtime for each template.
   The default runtime needs [user namespace support](https://kubernetes.io/docs/concepts/workloads/pods/user-namespaces/) and `hostUsers: false`.
   A `gvisor` template omits `hostUsers` and uses gVisor's kernel boundary instead.
5. The pinned agent-sandbox controller and its extensions.

Use `sandbox.templates.runtimeClassName` for the template runtime.
`sandbox.runtime.nodeSelector` and `sandbox.runtime.tolerations` select sandbox nodes.
Set `sandbox.templates.podOverhead` to the RuntimeClass's `overhead.podFixed`, as `cpuMillis` and `memoryMi`.
The template uses `RuntimeDefault` seccomp.

Run `node scripts/sandbox-isolation.ts` against each template before enabling work.
The gate below replaces the server's old startup and scheduled isolation probes.
The server no longer creates probe Pods or blocks its API on a probe.
Qualify every eligible sandbox node. A gate proves enforcement only where and when it runs.

## Sandbox templates

With the server enabled, the chart installs a `SandboxTemplate` for each sandbox kind in the sandbox namespace.
Their names are `<release>-sandbox-review` and `<release>-sandbox-conversation`.
Each template has a `SandboxWarmPool` of the same name with no replicas.
The agent-sandbox controller creates a sandbox's Pod from the template when a `SandboxClaim` names the warm pool.
The release pins the controller in `k3s-agent-sandbox.yaml`: the one-host platform installs it, and on another cluster you apply it yourself ([Install](./kubernetes#install)).

Claims use `DeleteForeground` at expiry. The claim stays visible until its Sandbox and Pod are gone.

A template's Pod has one container, the agent image, and these settings:

- No ingress, and egress only to the server's gateway port. The policies of the sandbox namespace set this.
  The template sets `networkPolicyManagement: Unmanaged`, because the controller's default policy allows the internet.
- No DNS. A claim gives the sandbox the gateway's address and the sandbox's credential as environment variables.
- No service account token, a non-root user, no capabilities, and `RuntimeDefault` or the configured seccomp profile.
- A read-only root filesystem. The sandbox writes only to two `emptyDir` volumes: `/workspace` and `/tmp`.
  `HOME` is `/tmp/home`.
  The size limit of each volume is the template's ephemeral-storage limit, and that limit also bounds the two volumes together.
- No init container, so that a sandbox starts fast.
- The RuntimeClass `sandbox.templates.runtimeClassName`, or the cluster's default runtime.
  On the default runtime, or another class except `gvisor`, the Pod sets `hostUsers: false`.
  A `gvisor` Pod keeps host users, because gVisor does not support user namespaces yet ([google/gvisor#13303](https://github.com/google/gvisor/issues/13303)). Its own kernel separates the sandbox from the node kernel.
- The resources of `sandbox.templates.<kind>.resources`.

### The agent image on the sandbox nodes

The agent image must be on a node before a sandbox starts there.
A pull of the image from GHCR takes about 7 seconds.
The whole start of a sandbox takes about 1 to 2 seconds.

The DaemonSet `<release>-agent-image` keeps the image on every sandbox node:

- It runs the image that the templates use, by the digest that the release pins.
- It runs on every node that `sandbox.runtime.nodeSelector` and `sandbox.runtime.tolerations` select.
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
There, a sandbox must also reach each server Pod on the gateway port.
It must not reach any server or server Pod on the application port 8080.
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

Each template owns its requests and limits under `sandbox.templates.<kind>.resources`.
The conversation quota uses the conversation template's requests and RuntimeClass overhead.
The review quota uses the review template and `sandbox.reviewMemory`.
No server probe, tools pool, or conversation-loop replica consumes quota.

Every sandbox has one container. The driver, Pi sessions, commands, Git, and practice programs share that container.

Its read-only root permits writes only under `/workspace` and `/tmp`.
The Pod's ephemeral-storage limit bounds both volumes together.
A command can read the sandbox token. The server checks the subject, current run epoch, lease, and turn state on every write.
The sandbox can reach only the gateway. It has no provider credential or Kubernetes API credential.
