# ADR 0058: The Helm chart is the only supported deployment from 1.0

**Status:** Accepted
**Date:** 2026-10-25
**Authors:** Felix T.J. Dietrich

**Supersedes:** [ADR 0041](0041-compose-1x-kubernetes-2.md) § Releases and support. The architecture of ADR 0041 stays.

**Amends:**

- [ADR 0042](0042-hosts-pull-their-own-releases.md): the apply step becomes `helm upgrade`. The trust model stays.
- [ADR 0035](0035-pull-request-previews-are-label-gated.md): the Coolify preview path goes. The preview policy stays.
- [ADR 0034](0034-signed-release-image-lock.md): the chart lock applies from 1.0, not from 2.0.

## Context

ADR 0041 made Docker Compose the only supported 1.x installation.
A Helm chart and the Kubernetes Job driver were to ship beside Compose during one 1.x milestone.
An update of ADR 0041 on 2026-10-25 made the chart a second supported 1.x install, beside Compose.
Version 2.0 was to remove Compose and to own the migration.
Thus, every 1.x release had to qualify two deployments, and the migration moved to the time with the most operators.

The Compose and preview path is large.
On 2026-10-25, `wc -l` over the tracked files of these paths counted these lines:

| Area | Paths | Lines |
| --- | --- | --- |
| The Compose stacks | `docker/compose.{app,core,proxy}.yaml`, `docker/.env.example`, `docker/self-host/**`, `docker/observability/compose.journald.yaml`, `docker/preview/**` | 3,454 |
| The preview scripts and their tests | `scripts/{coolify-preview,preview-controller,check-preview-stack,render-preview-comment,publish-preview-comments}*.ts` | 5,580 |
| The preview workflows | `.github/workflows/{deploy-preview,cleanup-preview,reconcile-previews}.yml` | 502 |
| The host checks and their tests | `scripts/{compose-host-routing,self-host-setup}.test.ts`, `scripts/{prepare-host-smoke-env,prepare-smoke-lock,sandbox-isolation-probe}*.ts` | 1,381 |

One maintainer qualifies each release.
A 1.x promise for Compose keeps these lines and a host smoke for each release until 2.0.

On Compose, the `application-worker` container mounts the Docker socket.
[ADR 0042](0042-hosts-pull-their-own-releases.md#consequences) records that write access to this socket is root-equivalent.
On Kubernetes, the worker creates Jobs with RBAC that is limited to its namespace.
Pod Security Admission rejects a Pod that violates the enforced level [K8].
NetworkPolicy limits sandbox egress to the gateway.

The install base is small now.
The maintainers know of two installations, and they operate both.
The release locks of v0.94.4 to v0.94.7 have 7 to 12 downloads each.
These downloads come from the two hosts, CI, and reviewers.
No issue names an external installation.

## Decision drivers

- One supported installation, upgrade path, and qualification for each release.
- No migration that waits for a time when more operators must do it.
- A sandbox boundary that the platform enforces, not one that depends on a root-equivalent socket.
- An easy single-host install: complete values, one command, and a test on a fresh VM.

## Considered options

1. **ADR 0041 as written.** Compose for 1.x, the chart beside it in a 1.x milestone, and Kubernetes only from 2.0.
   Rejected. Each 1.x release qualifies two products, and 2.0 needs the same migration for more operators.
2. **1.0 on Compose, with a preview chart.** Rejected. It promises a path that we plan to delete.
3. **The Helm chart only, from 1.0. One host runs k3s and the same Helm command.** Chosen.

For the application release on k3s, two mechanisms were considered:

1. **A k3s `HelmChart` resource in the auto-deploy folder** [K2]. Rejected for the application.
   The k3s Helm controller has no wait for Jobs and no rollback on failure. Use it only for platform add-ons.
2. **`helm upgrade --install` with the Helm 4 wait and rollback flags** [H1]. Chosen.

Comparable products also support one production path.
Mattermost writes that its container deployment is not for production environments [P4].
Langfuse lists Compose for "local use and testing" and runs production on Kubernetes [P3].
Airbyte deprecated Compose at 1.0 [P5].

Other products stay on Compose: Sentry [P6], Plausible [P7], and PostHog.
PostHog removed its Helm path because it spent "an outsized amount of time supporting the 3.5% of users" [P2].
Their workers do not run untrusted agents.
The lesson is the same in both groups: support one path.

## Decision

### Supported deployment

From 1.0.0, the Helm chart is the only supported deployment.
A cluster and a single host use the same `helm upgrade --install`.
A single host first installs k3s with its one-command installer [K1].
Hephaestus does not build an appliance or an operator.

Compose stays for local development only.
`server/compose.yaml` starts PostgreSQL, NATS, and Mailpit for `vp run dev` and for the end-to-end suites.
The Seeded Release Upgrade test uses `docker run` and stays.

The Docker sandbox driver stays as the driver for development and for the live suites.
It passes the same conformance suite as the Kubernetes Job driver.
A decision after 1.0 removes it or keeps it.

### Notice and removal

The [deprecation policy](../admin/compatibility-policy.mdx#deprecation-policy) requires notice one minor release before a removal.
The release notes of 0.95.0 announce the deprecation of the Compose deployment.
The Compose deployment stays supported for the rest of the 0.x releases.

In 1.0.0, the [public contract](../admin/compatibility-policy.mdx#the-public-contract) changes, as the [compatibility policy](../admin/compatibility-policy.mdx#supported-deployment) states.
From 1.0.0, a Compose file that is still in the repository is not supported, and it is not part of the contract.
Thus, its later deletion is not a removal under the deprecation policy, and it does not need a major release.

The deletion can follow 1.0, and its release notes say so. It removes these parts:

- The Compose production stack, the self-hosted stack, and the reference deployment, except the reconcile units.
- The Compose host checks in CI and in the release workflow.

Before 1.0.0, the admin docs no longer present Compose as an install.

### Upgrades

An operator verifies the signature of the chart digest with `cosign verify`.
Then the operator runs one command:

```bash
helm upgrade --install hephaestus oci://ghcr.io/hephaestus-build/charts/hephaestus@sha256:… \
  -n hephaestus --create-namespace -f site.yaml \
  --rollback-on-failure --wait=watcher --wait-for-jobs --timeout 20m
```

The release pins the image digests in the default values of the chart.
Thus, the signed chart is the one artifact to verify.
The host does not need Node.js for an upgrade.
A GitOps tool such as Flux, Argo CD, or Fleet can render the same chart.

The hosts that the maintainers operate keep the trust model of [ADR 0042](0042-hosts-pull-their-own-releases.md).
That model is the signed channel, the descent check, and the identity of `promote.yml`.
Only the apply step changes to the `helm upgrade` above.
A GitOps controller cannot replace the reconciler.
It reads a mutable tag, and ADR 0042 rejects the replay gap of a mutable tag.
The move of these hosts to the Helm apply can follow 1.0.

### Migration of a Compose install

An operator changes the substrate or the version, never both in one step.
First, the operator upgrades on Compose to the last 0.x release.
Then the operator moves to the chart at the same version, on a new host.
The old host stays unchanged, and it is the rollback.

| State | Migration |
| --- | --- |
| PostgreSQL | Restore the pgBackRest backup from the off-host repository into the bundled PostgreSQL 18. Recover to a time after the writers stopped. Without pgBackRest, use `pg_dump -Fc` and `pg_restore`. |
| JetStream | Follow ADR 0041: stop ingress, drain to zero pending messages, verify the outcomes, and start with empty streams. |
| Webhooks during the move | GitHub does not redeliver a failed delivery itself [G1]. The server asks GitHub to redeliver the failed App deliveries of the past hour. Redeliver older ones by hand, then sync each workspace. |
| Secrets and configuration | Map each known `.env` key into one Secret and `site.yaml`. Stop on an unknown key. Keep the encryption keys. A script can do this mapping, but the guide does not require it. |
| Cut-over | Move DNS. Run one practice review and one Heph turn. Until the new host admits writes, a rollback moves DNS back. |

The migration guide owns the procedure.
A rehearsal on staging with a rollback drill and a restore drill comes before the guide is published.

### Previews

The Coolify preview path tests a topology that the project no longer ships.
The project deletes it, and the deletion does not wait for the Compose files.
When the staging host runs k3s, previews can return as one namespace for each pull request.
[ADR 0035](0035-pull-request-previews-are-label-gated.md) keeps its label policy and its limits on agent execution.

### Gates before v1.0.0

The tag waits for all of these:

1. Chart CI passes on k3d at the minimum and the latest supported Kubernetes minor, on amd64 and arm64.
2. Chart CI also passes on kind with Calico and an external PostgreSQL.
   The published support names only the Kubernetes versions, architectures, and network plugins that chart CI tests.
3. A practice review completes on the chart, also across a `helm upgrade`.
4. The release publishes a signed chart with pinned images.
5. The one-host install has complete values: TLS, PostgreSQL certificates and their renewal, secrets, and an enabled worker.
6. The one-host install checks DNS and the firewall before it starts.
7. On a fresh VM, the one-host install passes a first install, a reboot, a certificate renewal, and an upgrade.
8. The k3s recipe passes on a clean runner on both architectures, and it runs a practice review.
9. The previous server, worker, and webhook server boot on the new schema, and the previous server claims a review. A failed migration recovers by the restore of the matched backup.
10. Staging runs one release cycle on the chart. The rollback and restore drills pass.
11. The backup page states the measured RTO and RPO. No webhook was lost in the move.
12. A restore after total host loss onto empty storage passes, with the keys from off-host storage.
13. After a restore, PostgreSQL and JetStream agree, and the provider replay completes.
14. Persistent volumes are kept as the chart declares, secrets stay in their custody, and the alerts are tested.
15. The migration guide is published and rehearsed.
16. No admin doc presents Compose as an install.
17. The fit test counts the measured peaks on the recommended host shape.
18. A person who did not write the docs installs on a fresh VM with only the docs.

## Consequences

- Each release from 1.0 qualifies one deployment. The Compose and preview lines go in one deletion.
- Kubernetes is an operator prerequisite from 1.0, not from 2.0. An operator needs Kubernetes skills during an incident. The runbook gives the `kubectl` step for each operation.
- The 1.0 tag can come later than a Compose 1.0 would. As in ADR 0041, the scope sets the date, not the reverse.
- Operators keep k3s up to date themselves. Hephaestus pins the platform add-ons (the Gateway API CRDs, Traefik, and cert-manager) in one platform manifest. The release smoke tests these pins, so a change upstream can block a release.
- k3s uses about 1.4 GiB of memory and 5 % of a core [K3]. The recommended single host is the `small` profile: 4 vCPU, 32 GiB of memory, and 150 GiB of disk.
- The `small` profile is provisional. A 16 GiB profile can qualify separately, with bounded concurrency and upgrades in a planned outage.
- An existing Compose install must migrate before it upgrades to 1.0. The release notes of 0.95.0 and the migration guide give notice.
- Until a later change adds a rolling update, the server role uses the `Recreate` strategy. Its outage for each upgrade is the same as on Compose today. Upgrades without an outage are a later goal.
- A failed `helm upgrade` returns the workloads to the previous release, not the database. After a successful migration, this is safe because the previous release runs on the new schema (gate 9). After a failed migration, the recovery is the restore of the matched backup, as [If migration fails](../admin/upgrade.mdx#if-migration-fails) describes.
- The sandbox boundary no longer depends on the Docker socket in a supported deployment.
- Previews are not available until they return on the staging k3s.

## Revisit trigger

Reopen this decision only on evidence of both of these:

- Operators who need Hephaestus cannot run k3s or another Kubernetes distribution.
- A maintainer staffs a second supported deployment, with its own release qualification.

If no recommended host shape passes the fit test, the tag waits. The requirement does not go back to Compose.

## Sources

Fetched 2026-10-25.

- **K1** [k3s quick start](https://docs.k3s.io/quick-start): `curl -sfL https://get.k3s.io | sh -` installs the service, `kubectl`, and the kubeconfig.
- **K2** [k3s packaged components](https://docs.k3s.io/installation/packaged-components) and [the k3s Helm controller](https://github.com/k3s-io/docs/blob/main/docs/add-ons/helm.md): k3s deploys each file in `/var/lib/rancher/k3s/server/manifests`.
- **K3** [k3s resource profiling](https://docs.k3s.io/reference/resource-profiling): k3s with one agent uses 5 % of a core and 1.4 GB.
- **K8** [Pod Security Admission](https://kubernetes.io/docs/concepts/security/pod-security-admission/): the `enforce` mode rejects a Pod that violates the level.
- **H1** [`helm upgrade`](https://helm.sh/docs/helm/helm_upgrade/): the Helm 4 flags `--rollback-on-failure`, `--wait`, and `--wait-for-jobs`.
- **G1** [GitHub failed webhook deliveries](https://docs.github.com/en/webhooks/using-webhooks/handling-failed-webhook-deliveries): GitHub does not redeliver a failed delivery automatically.
- **P2** [PostHog sunsets Helm support](https://posthog.com/blog/sunsetting-helm-support-posthog).
- **P3** [Langfuse self-hosting](https://langfuse.com/self-hosting).
- **P4** [Mattermost container deployment](https://docs.mattermost.com/deployment-guide/server/deploy-containers.html).
- **P5** [Airbyte deprecates Docker Compose](https://github.com/airbytehq/airbyte/discussions/40599) and [the migration from Compose](https://docs.airbyte.com/platform/deploying-airbyte/migrating-from-docker-compose).
- **P6** [Sentry self-hosted](https://develop.sentry.dev/self-hosted/): it installs with Docker Compose.
- **P7** [Plausible Community Edition](https://github.com/plausible/community-edition): it installs with Docker Compose.
