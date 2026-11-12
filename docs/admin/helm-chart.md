---
title: Helm chart reference
description: The clusters that the Helm chart supports, what it runs, its profiles and values, and its routes, network policy, storage and database roles.
page_type: reference
---

This page describes the Helm chart of a release: the clusters it supports, the objects it runs, and its values.
[Install on a Kubernetes cluster](./kubernetes) and [Install on one host](./k3s) give the procedures.
From 1.0, the chart is the supported deployment: [ADR 0058](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0058-the-helm-chart-is-the-only-supported-deployment-from-1-0.md) records the decision, and the [compatibility policy](./compatibility-policy#support-for-1x) gives the terms.

Each release publishes the chart as a signed OCI artifact, `oci://ghcr.io/hephaestus-build/charts/hephaestus`.
The chart source is in `deploy/chart/hephaestus/` of the repository.
It uses the standard Helm chart format: `Chart.yaml`, `values.yaml`, `values.schema.json`, and `templates/`.
It uses only stable Kubernetes APIs and the Gateway API `HTTPRoute` resource of version `v1`.

## Supported clusters

Hephaestus supports the chart on a cluster with these items:

- A [conformant](https://www.cncf.io/training/certification/software-conformance/) Kubernetes cluster at a supported minor version.
  The [Kubernetes project](https://kubernetes.io/releases/) must support that minor version when the Hephaestus release ships.
  Below 1.35, the chart refuses the cluster.
  Upgrade a cluster at an older minor, for example 1.30 or 1.33, before Hephaestus runs on it.
  [ADR 0060](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0060-the-kubernetes-floor-follows-upstream-support.md) gives the reason.
- A network plugin that enforces NetworkPolicy for egress and for ingress, also on traffic to a Service address.
  The server's isolation probe stops the server on a plugin that does not ([Sandbox prerequisites](./sandboxes#sandbox-prerequisites)).
- A Gateway API controller for `HTTPRoute` `v1` with the two features in [Gateway routes](#gateway-routes).
- A storage class for `ReadWriteOnce` volumes, for the bundled PostgreSQL and NATS.
- With the server enabled, the [agent-sandbox](https://github.com/kubernetes-sigs/agent-sandbox) controller with its extensions, at the version that the release pins ([Sandbox templates](./sandboxes#sandbox-templates)).

CI installs and upgrades the chart on Kubernetes 1.35 and on the latest minor version, 1.37, on `amd64`.
1.35 is the lowest version that the chart takes.
A pull request runs only the latest minor version.
When a change touches the chart or the sandbox runtime, the merge queue runs both before the commit reaches `main`.
A nightly run repeats them on `main`, and it also runs 1.35 on `arm64`.

One more run uses Kubernetes 1.35, three nodes, Calico for network policy, and an external PostgreSQL.
The merge queue and the nightly run include it.
Each run starts two servers with the `dev.yaml` profile, and each server passes its isolation probe on that cluster.
In the merge queue, the 1.35 run on `amd64` uses `small.yaml` instead, with one server, on a runner of 16 GiB.

Each upgrade must keep the PostgreSQL and NATS Pods: only a change to their own image or settings restarts them.
On the latest minor version, the `amd64` run also backs up to an S3 server and restores to a point in time.
[Backup and restore](./backup-restore#back-up-the-database) describes these steps.
[Install on one host (k3s)](./k3s) states what the release workflow tests before it publishes a release.
When a Kubernetes minor version reaches its end of life upstream, the next Hephaestus release can drop it.

## Clocks

Keep the clock of every host that runs a Hephaestus role or PostgreSQL synchronized by NTP.
Conversation turn leases and person erasure compare times that different hosts write.
If Slack does not answer when Hephaestus starts a streamed reply, the server looks for that reply in the thread by time.
Hephaestus assumes that Slack's clock and the server's clock differ by less than 2 seconds.
[Conversation runtime § Slack replies](/contributor/unified-pi-runtime#slack-replies) has the rule.

## What the chart runs

| Component | Kind | Role or image |
| --- | --- | --- |
| `server` | Deployment, 1 replica, or 0 while stopped | Server role: the API and provider webhook receiver. |
| `migration` | Job, one for each Helm revision | The server image in its migrate run mode. It applies the release's database migrations and exits. |
| `webapp` | Deployment | The SPA, from the non-root webapp image. |
| `postgresql` | StatefulSet, optional | The release PostgreSQL image, with `pg_partman` and pgBackRest. |
| `postgresql-backup` | CronJobs, optional | Full and differential pgBackRest backups ([Backup and restore](./backup-restore#back-up-the-database)). |
| `nats` | StatefulSet, optional | One NATS JetStream server. |
| `route` | HTTPRoute | `/`, `/api` and `/webhooks`. |
| `priority` | PriorityClasses, cluster-wide | The priorities of the role Pods and of the sandboxes ([Server § Priorities](./sandboxes#priorities)). |

The PriorityClasses are the only cluster-wide objects of the chart.
The account that installs the chart therefore needs permission to create PriorityClasses.
Their names contain the release's namespace and name, so two releases never share one.
On a shared cluster that does not allow this, use classes that exist ([Server § Priorities](./sandboxes#priorities)).

The migration Job is the only migration owner.
No role runs Liquibase: each new Pod waits for the schema before it starts, for up to 10 minutes, while the previous release serves.
The Job is a plain part of the release and not a Helm hook, because a hook would run before the bundled PostgreSQL exists.
[Runtime roles](./runtime-roles) describes the run mode and the wait.

The server has at most one replica and uses `Recreate`.
JetStream replay fences belong to one process, so two servers can process a replay and a live event for the same item at once.
A rolling update is refused until the provider-event inbox supplies ordering across replicas.
Two server versions never serve the schema at the same time.

The server also runs the sandboxes when `sandbox.enabled` is on.
[Database maintenance](./maintenance#database-maintenance) gives the order of the roles for a change of the database or of a schema that needs every role stopped.

## Profiles

Four values files in `profiles/` set capacity for one use.
All four use the same templates.
Each release chart contains them in `chart-$VERSION/hephaestus/profiles/` after you [verify the release chart](./kubernetes#verify-the-release-chart).
Give the profile as the first `-f` file, before your own values, as [Install](./kubernetes#install) shows.
If a change of profile changes a volume size, read [Change a volume size](./upgrade#change-a-volume-size) before you upgrade.

| Profile | Use | PostgreSQL and NATS |
| --- | --- | --- |
| `dev.yaml` | One workstation cluster | Bundled, small volumes |
| `preview.yaml` | One pull request preview | Bundled, small volumes |
| `small.yaml` | One small cluster, for example one k3s node | Bundled, bounded streams |
| `cluster.yaml` | A managed cluster of several nodes, without cluster-admin: [Install on a managed cluster without cluster-admin](./managed-cluster) | Bundled, or external |

`small.yaml` supports a node of 4 vCPU, at least 16 GiB of memory, and 150 GiB of disk.
A node of 32 GiB gives more headroom.
Its one server runs five reviews at a time. Its one conversation loop runs up to 32 resident conversation turns, also with backups on.

The server uses `Recreate` in the `small` profile.
The old server drains before its replacement starts, so reviews and conversations pause during each upgrade.
Measure the deployment outage against the 99.5% availability budget.

`conversationLoop.turns: auto` uses the CPU declaration in the chart's `turn-admission.json`.
It subtracts these requests from 3500m of allocatable CPU:

- The resident roles, and the backup when backups are on.
- The conversation loop, with one more replica for its rollout.
- The ten tools Pods.
- The review-tier reservation.

Each resident turn budgets 50m, with a ceiling of 32 for the installation. An explicit turn count remains the operator's override.
The declaration also supplies the fit test, which checks it against the kubelet reservation policy.

The conversation quota has ten tools-Pod slots for the installation, including a warm pool of up to two.
A turn needs a tools Pod only when it calls a tool.
When the quota is full, the tool call waits for room.
The metric `mentor_turn_tools_wait_seconds` records that wait from quota refusal through tools readiness, including Pod start.
Its `outcome` is `ready` or `abandoned`. Model calls still wait for their own provider grants.

The table counts schedulable Pod requests. The resident-turn CPU planning cost is checked separately in steady state.

The server requests its full memory limit of 3 GiB, because the buildpack sizes its heap from that limit.
PostgreSQL and its backup containers also request their full memory limits.
CPU remains burstable, so these Pods keep the Burstable quality-of-service class.
NATS also requests its full memory limit. The chart sets its `GOMEMLIMIT` to 80 % of that limit ([NATS memory](#nats-memory)).

The `small` profile bounds the server's Git cache at 20 GiB and its ephemeral-storage limit at 22 GiB.
Include those limits and the sandbox disk requests in the node's disk budget ([Sandbox sizes](./sandboxes#sandbox-sizes)).
Use `small.yaml` on a node of 16 GiB too.
Its server uses `Recreate`, so the old server stops before its replacement starts.
The server runs five reviews at a time, and the conversation loop the same resident turns as on a larger node.

A second server does not fit in 16 GiB beside the first.
Thus, the upgrade stops reviews and conversation turns for the drain, the new server's start and its isolation probe.

A chart test adds up what one node of 16 GiB holds, with backups on:

| State | Memory | CPU requests |
| --- | --- | --- |
| Steady: every role, the conversation loop, one backup, five reviews, eight active tools Pods and two pooled tools Pods | 11.0 GiB of 13.9 GiB | 1.70 of 3.5 |
| The same, with the isolation probe | 11.6 GiB | — |
| An upgrade. The old server is gone. The webapp and conversation loop surge, with the migration and stanza Jobs, the new server's probe, and the pooled tools Pods | 10.6 GiB of 13.9 GiB | 2.10 of 3.5 |

The table counts requests.
Its node budget uses the recorded Node capacity of 16 372 036 KiB, less the current platform reservations and hard eviction floor.
It does not qualify the peak memory of running work.
Sandbox Pods may use more than they request, up to their limits.
If every sandbox Pod used its limit at the same time, the node would be full.

The single-node `small` profile sets `conversationLoop.turns: auto` and `conversationLoop.replicas: 1`.
With its default values, the chart derives 32 resident conversation turns for the installation, also with backups on.

A staging capture measured a point-in-time restore of a database of 1.8 GB at 152 s.
The release served again 5 minutes 41 seconds after the restore started.
The peak during a review and a conversation turn is not measured yet.

For a cluster with several nodes, start from `cluster.yaml`, and set the servers, their reviews, their quota and the external services in your own values file.

[ADR 0035](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0035-pull-request-previews-are-label-gated.md) owns what a preview can run.
The preview profile sets only capacity.

### NATS memory

The bundled broker requests 1 GiB and is limited to 1 GiB.
The chart sets `GOMEMLIMIT` to 80 % of `nats.resources.limits.memory`: 819 MiB by default.
If you change the limit, `GOMEMLIMIT` follows it.
Set `nats.resources.requests.memory` to the same size. The chart refuses a request that differs from the limit.

At rest, the broker's heap is about 100 MiB.
Page cache over the stream files can fill the rest of the limit, and the kernel reclaims it under pressure.
A catch-up is different: the Go heap grows with it.

A test on NATS 2.14.8 caught up two streams of 4.6 GB each, one uncompressed and one S2.
The messages were 16 KB on average, with some deliveries of 25 MiB.
Four consumers read at the same time, while new deliveries arrived:

| Memory limit | `GOMEMLIMIT` | Peak heap | Result |
| --- | --- | --- | --- |
| 1 GiB | Not set | 993 MiB | The kernel stopped the broker |
| 3 GiB | Not set | 2,835 MiB | The broker continued, at its limit |
| 1 GiB | 820 MiB | 813 MiB | All four caught up, at about 150 MB/s each |
| 512 MiB | 410 MiB | 402 MiB | All four caught up, at about 165 MB/s each |

## Values

The schema refuses an unknown key, an image tag, and a value outside its bounds.
Each object name is `<release>-<component>`, with at most 52 characters.
A StatefulSet revision label adds up to 11 characters to its name, and a label value has at most 63.
If a name is longer, the chart keeps its start and adds 8 characters of a hash of the full name.
The same release always gets the same names.
The `app.kubernetes.io/instance` label always holds the full release name.

`config.auth.assuranceEnforced` controls the passkey and recent sign-in checks for administration.
Its default is `true`, as in the application.
The chart never changes it from the environment name.
To turn it off, for example on a staging instance, set it to `false` yourself.

These values set the server's settings of the same meaning ([Production setup](./production-setup)):

| Value | Setting | Default |
| --- | --- | --- |
| `config.researchOrganization` | `HEPHAESTUS_RESEARCH_ORGANIZATION` | Empty |
| `config.workspaceCreationPolicy` | `HEPHAESTUS_WORKSPACE_CREATIONPOLICY`: `ADMIN_ONLY` or `SELF_SERVICE` ([GitHub integration](./github-integration#who-creates-the-workspace-of-an-installation)) | `ADMIN_ONLY` |
| `config.publicActivity.enabled` | `HEPHAESTUS_PUBLIC_ACTIVITY_ENABLED` | `false` |
| `config.publicActivity.repositoryVisibilityMaxAge` | `HEPHAESTUS_PUBLIC_ACTIVITY_REPOSITORY_VISIBILITY_MAX_AGE` | `48h` |
| `config.llmDisplayCurrency` | `HEPHAESTUS_LLM_DISPLAY_CURRENCY`: empty or `EUR` | Empty |
| `config.auth.passkeys.instanceAdminRequired` | `HEPHAESTUS_AUTH_PASSKEYS_INSTANCE_ADMIN_REQUIRED` | `true` |
| `config.auth.passkeys.workspaceAdminRequired` | `HEPHAESTUS_AUTH_PASSKEYS_WORKSPACE_ADMIN_REQUIRED` | `false` |
| `config.auth.passkeys.rpId` | `HEPHAESTUS_AUTH_PASSKEYS_RP_ID` | Empty: the host |
| `config.auth.passkeys.allowedOrigins` | `HEPHAESTUS_AUTH_PASSKEYS_ALLOWED_ORIGINS` | Empty array: the host |
| `config.auth.passkeys.adminMaxAge` | `HEPHAESTUS_AUTH_PASSKEYS_ADMIN_MAX_AGE` | `6h` |
| `config.email.tls` | `starttls` sets `SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE` and `_REQUIRED`. `implicit` sets `SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_ENABLE`. `SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH` is on when `config.email.username` is set. | `starttls` |
| `config.sync.runOnStartup` | `SYNC_RUN_ON_STARTUP` | `true` |
| `config.sync.timeframeDays` | `SYNC_TIMEFRAME_DAYS` | `7` |
| `config.sync.syncCron` | `SYNC_CRON`, a Spring cron expression with six fields | `0 0 * * * *` |
| `config.sync.cooldownMinutes` | `SYNC_COOLDOWN_MINUTES` | `60` |

`config.auth.bootstrapAdmins`, `config.auth.browserExtensionIds`, `config.auth.passkeys.allowedOrigins`, and `config.outline.allowedOrigins` take arrays of strings.
Use `[]` for an empty list.
The chart joins the items in their given order for the application's environment binding.
An item cannot contain a comma.
For example:

```yaml
config:
  auth:
    bootstrapAdmins:
      - "github:@your-github-username"
    browserExtensionIds: []
    passkeys:
      allowedOrigins: []
  outline:
    allowedOrigins: []
```

`webapp.securityContact` sets the webapp's `SECURITY_CONTACT`: with a value, `/.well-known/security.txt` names it as the contact of this deployment ([Production setup](./production-setup)). Empty serves no file.

## Integration values

Each integration takes its settings from `site.yaml` and its secrets from the [Secret](./kubernetes#create-the-secret).
`values.schema.json` describes each value.
[Integration settings](./production-setup) explains each setting.

| Integration | Values | Secret keys |
| --- | --- | --- |
| GitHub login | `config.github.oauthClientId` | `GITHUB_OAUTH_CLIENT_SECRET` |
| GitHub App | `config.github.appId`, `config.github.appClientId`, `config.github.appInstallationUrl` | `GH_APP_PRIVATE_KEY`, `GH_APP_CLIENT_SECRET` |
| GitLab | `config.gitlab.*` | `GITLAB_OAUTH_CLIENT_SECRET` |
| Slack | `config.slack.*` | `HEPHAESTUS_INTEGRATION_SLACK_SIGNING_SECRET`, `HEPHAESTUS_INTEGRATION_SLACK_CLIENT_SECRET` |
| Outline | `config.outline.*` | `OUTLINE_OAUTH_CLIENT_SECRET` |
| Email | `config.email.*` | `SPRING_MAIL_PASSWORD` |
| Provider webhooks | None | `WEBHOOK_SECRET`, `WEBHOOK_ROUTING_SECRET` |

## Gateway routes

| Request path | Backend | Path change |
| --- | --- | --- |
| `/webhooks` and below | `server` | None |
| `/api` and below | `server` | `/api` is removed |
| All other paths | `webapp` | None. The webapp serves the SPA for an unknown path. |

The server serves the API without the `/api` prefix.
A standard `Ingress` cannot remove a path prefix, so the chart does not use one.

The Gateway controller must support the core `HTTPRoute` features and these two extended features:

- `URLRewrite` with `ReplacePrefixMatch`, for `/api`.
- `ResponseHeaderModifier`, for `Strict-Transport-Security` on each rule.

If the controller does not support one of them, the route does not work as described.
The chart cannot detect this. Confirm both features for the controller that you select.

The live streams of Hephaestus stay open for much longer than one request.
They do not need a route timeout. Instead, the server sends a heartbeat on each live stream about every 20 seconds.
The server skips a heartbeat while the previous one is still being sent, so two events can be about 40 seconds apart.
Thus, set each idle timeout between the browser and the server to at least 60 seconds.

The `/api` rule also sets `timeouts.request` and `timeouts.backendRequest` to `0s`, which asks the controller for no time limit.
A controller with the `HTTPRouteRequestTimeout` and `HTTPRouteBackendTimeout` features applies them.
The Traefik of the one-host platform does not have these features, so it does not apply the values.
A controller that limits the total time of a response, and does not apply the values, ends each live stream at that limit.
The other three rules set no timeout and no retry.

An idle timeout of at least 60 seconds does not replace the request and backend timeouts.
No Gateway controller or version is qualified yet.
A qualification must include these tests on the selected controller and its idle settings:

- A long Hephaestus live stream.
- A long WebSocket connection through the Gateway.
- A cancellation by the client during a stream.

## Network policy

Network policy is always on. No value turns it off.
Each pod of the release starts with no ingress and no egress.
The chart then opens only these paths:

| From | To | Port |
| --- | --- | --- |
| `networkPolicy.gateway` pods | `server`, `webapp` | 8080 |
| `server` | PostgreSQL | 5432, or the external port |
| `server` | NATS | 4222, or the external port |
| `server` | `networkPolicy.externalEgress` | 443 |
| `server`, if `config.email.host` is set | `networkPolicy.externalEgress` | `config.email.port` |
| `server` | The Kubernetes API, `networkPolicy.kubernetesApi` | Its port |
| `server`, if `networkPolicy.workerGatewayPorts` is set | `networkPolicy.gateway` pods | `networkPolicy.workerGatewayPorts` |
| `server`, for each item of `networkPolicy.modelEndpoints` | Its `cidr` | Its `ports` |
| Review sandboxes | `server` | 8081, the sandbox gateway |
| `conversation-loop` | `server` | 8081, the sandbox gateway |
| `conversation-loop` | Tools Pods | 7100, the tools port |
| PostgreSQL and the backup Jobs, with backups on | `postgresql.backup.s3`, or `networkPolicy.externalEgress` | `postgresql.backup.s3.port` |
| The backup Jobs, with backups on | PostgreSQL | 8432, the pgBackRest TLS server |
| All pods | `networkPolicy.dns` pods | 53 |
| `networkPolicy.metrics` pods | All application roles | 9090 |
| `server`, if `config.tracing.otlpEnabled` and `networkPolicy.traceCollector` are set | `networkPolicy.traceCollector` pods | The port of `config.tracing.otlpEndpoint` |

Sandboxes are in the sandbox namespace, where every pod also starts with no ingress and no egress.
A tools Pod admits only the ingress from the conversation loop, and gets no egress.

A model endpoint that this cluster's own Gateway serves resolves to a public address, but the connection ends at a Gateway Pod.
That private address is outside `networkPolicy.externalEgress`, so the server cannot reach the endpoint.
Set `networkPolicy.workerGatewayPorts` to the container ports of the Gateway Pods, for example `[8443, 8000]` for the Traefik of the one-host platform.

`networkPolicy.gateway` must name the namespace and at least one pod label of the Gateway data plane.
A namespace without labels would admit every pod in it, so the chart refuses it.
The same rule applies to `networkPolicy.metrics` and `networkPolicy.traceCollector` when you set their namespace.

The management listener binds to the pod address.
The kubelet probes it, and the metrics scraper can read it.
No Service and no route expose it.

The external egress rule excludes private, link-local, and shared address ranges by default.
An external database or broker in a private range needs `postgresql.external.cidr` or `nats.external.cidr`.

A rendered policy does not show enforcement.
Each network plugin needs its own positive and negative traffic tests before the chart is supported.

Network policy controls which pods can connect. It does not encrypt traffic.
The connections to PostgreSQL use TLS ([PostgreSQL TLS Secret](./kubernetes#postgresql-tls-secret)).
These internal channels send plain text:

- The connections from the server to the bundled NATS.
- The connection from the server to the server.
- The connections from sandboxes to the server gateway.

The bundled PostgreSQL still accepts a connection without TLS.
The roles never use one, and network policy admits only the roles.

On a cluster with several nodes, these channels cross the node network.
Use a network plugin that encrypts traffic between nodes, for example WireGuard in Calico or Cilium, when that network is not private to the cluster.

## Storage and retention

| Data | Bound | Setting |
| --- | --- | --- |
| PostgreSQL | Volume size | `postgresql.persistence.size` |
| JetStream | Broker `max_file` and stream storage budget | `nats.jetstream.maxFileBytes` |
| JetStream volume | `max_file` plus 25 % | Derived from `nats.jetstream.maxFileBytes` |
| Webhook streams | Age and size for each stream, and one start that may delete stored messages to apply a smaller bound | `webhook.stream.*` |
| Server Git mirrors | `emptyDir` size limit on the node | `server.gitCache.sizeLimit` |
| Server Git mirrors | `emptyDir` size limit on the node, per server | `server.gitCache.sizeLimit` |
| AI spend ledger | Retention | `config.llmUsageRetention` |
| Conversations, feedback and practice results | Retention | `config.feedbackRetention` |
| Temporary files | `emptyDir` size limit | Fixed in the templates |

One value sets both the broker `max_file` and the server's stream storage budget.
The server refuses to start when the stream bounds add up to more than that budget.
[Webhook ingestion operations](./webhook-ingestion-operations) gives the durability guarantee.

The `small.yaml` profile sets GitHub to 6 GiB and each GitLab, Slack and Outline stream to 512 MiB.
GitHub and GitLab keep deliveries for the shorter of seven days and what the byte bound holds. Slack and Outline keep them for 72 hours at most, because they carry personal content that an erasure request cannot reach in a stream. The byte bounds total 7.5 GiB.
These bounds count uncompressed bytes, so compression lowers the disk use but does not lengthen the retention.

The broker file budget is 8 GiB. A fresh bundled broker claims 10 GiB, including the 25 % storage allowance.

The server keeps an inactive durable consumer for 30 days.

A byte bound can remove messages before seven days. Measure peak traffic before you select these bounds.

For an existing installation, keep its current `nats.jetstream.maxFileBytes` in your site values before you apply this profile.
Do not shrink an existing PVC or change the StatefulSet claim template to adopt the smaller broker budget.
Do not lower the broker budget below its stored data.

Before you shorten stream limits, verify an off-host backup and test its restore.
Then use the [guarded stream-limit procedure](./webhook-ingestion-operations#changing-a-bound-on-a-stream-that-already-exists).
A higher limit cannot recover deleted messages.
The server updates expiry only on durables it uses. Review orphan durables separately before you change or delete them.

The Git mirrors are caches.
A restarted server or server clones them again, so they use node storage and no volume claim.
The node needs that much free ephemeral storage, and the container limits include it.

The same volume holds review checkouts and the archives that wait for their sandbox.
Above 80 percent of `gitCache.sizeLimit`, a role deletes the mirrors that a capture used longest ago.
It keeps a mirror that a capture used in the last 10 minutes.
Each one costs a full fetch at its next capture.
Thus, repositories that outgrow the volume slow captures before the kubelet evicts the Pod.
Mirrors are all it can delete: review checkouts that fill the volume alone still evict the Pod, and `HephaestusGitCacheNearlyFull` warns first.

The bundled NATS is one JetStream server, and every stream has one replica.
The application has no setting for more replicas.
A lost NATS volume loses each provider delivery that the broker accepted and the server had not yet processed.
That window is usually seconds, and it grows while the server is stopped or behind.
Use a storage class whose volumes survive the loss of a node, and back up the volume as [Backup and restore](./backup-restore) describes.

## Pod security

Each pod meets the Kubernetes Restricted Pod Security Standard:

- A non-root user, `RuntimeDefault` seccomp, and no privilege escalation.
- All capabilities dropped and a read-only root filesystem.
- No host path, host network, or container runtime socket.
- No service account token, except on the server. Its Role reaches only the sandbox namespace.

The webapp runs as user and group 101 and listens on port 8080.
Its only writable path is a 16 MiB volume at `/tmp/hephaestus-webapp`.
The image writes its generated files, its nginx PID file, and its temporary directories there.
`/tmp` itself and the assets stay read-only.

The bundled PostgreSQL runs as the image user 999 and owns its volume through `fsGroup`.
The volume is at `/var/lib/postgresql`, as [ADR 0038](https://github.com/hephaestus-build/Hephaestus/blob/main/docs/decisions/0038-postgresql-18-release-baseline.md) decides.
The image keeps its versioned data directory below it, and the chart does not set `PGDATA`.
The image's entrypoint starts PostgreSQL with `ssl=on` and the certificate and key from the TLS Secret, read-only at `/etc/postgresql/tls`.
No real volume driver has shown this configuration yet.
First initialization, restart, and restore need a start of the release image on a volume before this path counts as working.

The small profile sets explicit PostgreSQL memory parameters and per-role connection pools.
See [PostgreSQL memory](./sizing#postgresql-memory) and [Database connections](./sizing#database-connections) for the values, surge budget, and measurement gates.
It retains the larger PostgreSQL process limit until a loaded trial qualifies the lower ceiling.

Every container of the release has CPU, memory, and ephemeral storage requests and limits.
The schema refuses a zero value.
Sandbox containers have CPU, memory, and disk limits ([Sandbox sizes](./sandboxes#sandbox-sizes)).
They request the memory that their kind of sandbox was measured to use, below their limit ([Sandbox sizes](./sandboxes#sandbox-sizes)).
They request a small part of their CPU limit, because the CPU limit is a share of CPU time, not a reserved core ([Sizing](./sizing#review-sandbox-limits)).

## Database roles

Each database purpose has its own PostgreSQL role.
No Hephaestus process logs in as a superuser.

| Role | Logs in | Can | Secret key |
| --- | --- | --- | --- |
| The administrator | Only you, for administration, and the step of the migration Job that sets up the roles. The bundled PostgreSQL creates it at its first start. | Everything. | `DATABASE_ADMIN_USERNAME`, `DATABASE_ADMIN_PASSWORD`, with the bundled PostgreSQL only |
| `hephaestus_owner` | The migration Job | It owns the database and every schema object, and runs the migrations. It is not a superuser, and it cannot create roles or replicate. | `DATABASE_OWNER_PASSWORD` |
| `hephaestus_app` | The server and the server | It reads and writes the rows of the application tables. It owns nothing, and it cannot change the schema or read the role catalog. Row-level security applies to it. | `DATABASE_APP_PASSWORD` |
| `hephaestus_monitor` | The PostgreSQL exporter | It reads statistics only, as a member of `pg_monitor` ([Database metrics](./monitoring#turn-on-database-metrics)). | `DATABASE_MONITOR_PASSWORD` |

Neither `hephaestus_owner` nor `hephaestus_app` has the `BYPASSRLS` attribute.
As the owner of each table, `hephaestus_owner` is not held to the row-level security policies of its tables, unless a table sets `FORCE ROW LEVEL SECURITY`.
No table has a policy yet.

The chart file `files/database-roles.sql` sets up the owner and the application role.
No password reaches the PostgreSQL server.
The chart script `files/scram-sha-256.pl` computes the SCRAM-SHA-256 verifier of each password, with a new salt, as `psql`'s `\password` does.
`database-roles.sql` accepts only these verifiers.

A verifier still allows an offline guess of its password.
While a statement of the file runs, the members of `pg_monitor` can see its verifier in `pg_stat_activity`, but never a password.
Thus, the chart file `files/database-quiet.sql` runs first, in the same `psql` session.
It turns off each setting that would record a verifier:

- `pg_stat_statements.track_utility`
- `log_statement`, `log_min_duration_statement`, `log_min_duration_sample` and `log_transaction_sample_rate`
- `debug_print_parse`, `debug_print_rewritten` and `debug_print_plan`
- `log_min_error_statement`

`database-roles.sql` does not run without it.
Both files need `psql` 15 or later and PostgreSQL 16 or later, and they stop with a message on an older version.
`scram-sha-256.pl` needs Perl 5.10 or later with the module `Digest::SHA`.
Most distributions include that module with Perl. On RHEL and its minimal images, install the package `perl-Digest-SHA`.
Hephaestus itself needs PostgreSQL 18 ([Compatibility policy](./compatibility-policy#database-baseline-prerequisites)).

`database-roles.sql` is idempotent:

- It creates each role that does not exist and sets its password verifier.
  It reads both verifiers from the environment of `psql`, never from its command line.
- It stops if either role is a superuser, bypasses row-level security, creates roles or replicates.
- It makes `hephaestus_owner` the owner of the database.
- It removes the right of every other role to create objects in the schema `public`. A database from before PostgreSQL 15 gives that right to all roles.
- It moves each schema object of another role to `hephaestus_owner`. The objects of an extension stay with the role that created the extension.
- It updates the extensions `pg_partman`, `citext` and `btree_gist` to the versions that the PostgreSQL server has. The migrations never update an extension.
- It grants `hephaestus_app` the rows of each table, and each table that `hephaestus_owner` creates later.

The chart script `files/database-roles.sh` runs these files in the right order: it computes the verifiers, then runs `database-quiet.sql` and `database-roles.sql` in one `psql` session.
With the bundled PostgreSQL, the migration Job runs the script before each migration, as the administrator.
The chart gives the Job the files in a ConfigMap, not in its command, because Kubernetes rewrites `$$` and `$(NAME)` in a command.
Only that init container of the Job gets the administrator's password.
Thus, a changed `DATABASE_OWNER_PASSWORD` or `DATABASE_APP_PASSWORD` takes effect with the next upgrade.
A new PostgreSQL image with a new extension version also takes effect with that step.

The application role keeps the monthly partitions of `auth_event` through the function `maintain_auth_event_partitions()`.
That function runs as `hephaestus_owner`, for `auth_event` only.
No other role can run it.

If a key is missing from the Secret, `helm upgrade` stops and names it.
It also stops if the Secret holds `DATABASE_USERNAME` or `DATABASE_PASSWORD`, the single login before 1.0.

## Conversation loop

The chart runs the conversation loop as the Deployment `<release>-conversation-loop` in the release namespace.
It runs every conversation turn of the installation.
The chart renders it only with `sandbox.enabled=true`, and only when `conversationLoop.turns` resolves to more than 0.
[Runtime roles § The conversation loop](./runtime-roles#the-conversation-loop) tells how it gets its turns.

| Value | Default | Effect |
| --- | --- | --- |
| `conversationLoop.replicas` | `2`, and `1` in the single-node profile | The loop replicas. Each replica can run all the turns of the installation. |
| `conversationLoop.turns` | `8`, and `auto` in the single-node profile | The conversation turns that the whole installation runs at once ([Runtime roles § Conversation turn capacity](./runtime-roles#conversation-turn-capacity)). `0` turns conversations off. |
| `conversationLoop.resources` | Requests: 100m, 512 MiB and 256 MiB of disk. Limits: 2 CPU, 1 GiB and 512 MiB of disk. | The resources of the loop's container. |

The chart gives the loop Pod these settings:

- The loop program of the release's agent image, under Node's permission model.
- The PriorityClass of the roles.
- No service account token, the Restricted security settings and a read-only root file system.
- One `emptyDir` of 256 MiB at `/tmp/heph-loop`.
- The Secret key `HEPHAESTUS_CONVERSATION_LOOP_SECRET`. Only the server and the loop read it.
- A rolling update with one surge replica and none unavailable, and a grace period of 960 seconds.
- Preferred anti-affinity across nodes. On one node, the replicas share the node.
- A PodDisruptionBudget, as for the server: above one replica, at most one is unavailable.

The policy `<release>-conversation-loop` gives the loop no ingress.
It permits egress only to the gateway port of the server Pods and to TCP port 7100 of tools Pods in the sandbox namespace.

When a loop Pod fails, its turns stop renewing their leases.
Each turn starts again as a retry about 95 seconds after its last renewal, when a loop pulls it.
With one replica, as in the single-node profile, the restarted Pod pulls the retries.
The developer sees the answer start again.

## Known limits

- NATS JetStream runs as one server with one replica of each stream. A lost NATS volume loses the provider deliveries that the server had not processed ([Storage and retention](#storage-and-retention)).
- PostgreSQL runs as one instance. A lost node stops the release until PostgreSQL runs again. A lost volume needs a [restore](./backup-restore#restore).
- The server runs as one replica until the server processes provider events in order across replicas.
- The server records the isolation tier in its log and metrics, not with each review. A record for each review needs a schema change.
- The kubelet enforces sandbox disk limits by eviction after it measures the use, not when a process writes.
  A sandbox can pass its limit until the next measurement, for some seconds.
  Only file system project quotas (`LocalStorageCapacityIsolationFSQuotaMonitoring`) refuse the write itself.
- Use the [versioned deployment move](./compose-to-kubernetes) before upgrading an older installation.

## What CI does not show

A successful install in CI does not show that your cluster isolates sandboxes.
The server's isolation probe checks that on your cluster, at each start and on its schedule.
CI does not qualify your Gateway controller.
Check its two `HTTPRoute` features, its body limit and its idle timeout yourself, and rehearse a [restore](./backup-restore#restore) on your own cluster.
