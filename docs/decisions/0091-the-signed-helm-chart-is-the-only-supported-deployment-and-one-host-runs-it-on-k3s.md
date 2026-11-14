# ADR 0091: The signed Helm chart is the only supported deployment, and one host runs it on k3s

**Status:** Accepted
**Date:** 2026-11-10
**Authors:** Felix T.J. Dietrich

**Supersedes:** [ADR 0041](0041-compose-1x-kubernetes-2.md), [ADR 0058](0058-the-helm-chart-is-the-only-supported-deployment-from-1-0.md), [ADR 0060](0060-the-kubernetes-floor-follows-upstream-support.md)

**Amends:** [ADR 0053](0053-workspace-addresses-are-a-presentation-origin.md): the issuer of the workspace wildcard certificate.

**Builds on:** [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md), [ADR 0080](0080-provider-events-go-to-a-postgresql-inbox.md), [ADR 0079](0079-one-agent-system.md), [ADR 0038](0038-postgresql-18-release-baseline.md), [ADR 0070](0070-1-0-is-a-cutover-release.md)

**Relates to:** [ADR 0059](0059-previews-run-on-the-staging-cluster.md), [ADR 0034](0034-signed-release-image-lock.md), [ADR 0042](0042-hosts-pull-their-own-releases.md)

## Context

Three ADRs described the deployment. Their titles disagreed.
ADR 0041 said that 1.x runs on Compose and that Kubernetes comes only with 2.0. It had seven updates.
ADR 0058 then made the chart the only deployment, from 1.0 on.

ADR 0041 also held about ten other decisions: evidence, the job folder, the gateway, the sandbox boundary and the roles.
ADR 0060 listed Kubernetes minors by number, so each upstream release made it stale.

This ADR states the deployment at 1.0 as rules. [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) and [ADR 0080](0080-provider-events-go-to-a-postgresql-inbox.md) own the runtime topology.
[Kubernetes](../admin/kubernetes.mdx) in the admin guide owns every procedure.

## Decision drivers

- One deployment to qualify for each release, with one maintainer.
- The platform enforces the sandbox boundary. No boundary depends on a root-equivalent Docker socket.
- One host installs with one command, and it runs the same chart as a cluster.
- Hephaestus runs on no Kubernetes minor that upstream no longer patches.

## Considered options

1. **Compose for 1.x, the chart beside it, Kubernetes only from 2.0** (ADR 0041). Each 1.x release qualifies two products. 2.0 then migrates more operators.
2. **1.0 on Compose, with a preview chart** (ADR 0058). It promises a path that we plan to delete.
3. **The signed chart only, from 1.0. One host runs k3s and the same Helm command (chosen).**

On k3s, a `HelmChart` resource in the auto-deploy folder could apply the application. Its controller has no wait for Jobs and no rollback on failure. It applies only the platform add-ons.

## Decision

1. **One supported deployment.** From 1.0, the signed Helm chart is the only supported deployment. A cluster and a single host use the same chart. Hephaestus ships no appliance and no operator.
   Enforced by: `release.yml` signs the chart. `release-chart.test.ts` "a release candidate verifies its signed chart at the candidate version". `kubernetes-chart.test.ts` "the chart ships no image, tag or release identity of its own".
2. **The operator verifies one artifact.** The operator checks the chart digest with `cosign verify`, then pulls the chart by that digest. The packaged values pin every image by digest. [ADR 0034](0034-signed-release-image-lock.md) owns the signing.
   Enforced by: `kubernetes-chart.test.ts` "a released chart's values.yaml pins the release, and changes nothing else". The installer runs the same `cosign verify`.
3. **Compose is for local development only.** `server/compose.yaml` starts PostgreSQL, NATS and Mailpit for `vp run dev`. No production Compose file exists.
   Enforced by: `check-configuration-reference.test.ts` "the deploy inputs include chart values and no host Compose keys". Review catches a new production Compose file.
4. **One host runs k3s through the installer.** `deploy/platform/k3s-install.sh`, from the signed k3s bundle of the release, checks the host first. Then it installs the pinned k3s and the platform, verifies the chart, and runs one `helm upgrade --install`. The qualified host is Ubuntu 24.04 with its 6.8 kernel, on `amd64` or `arm64`. Another host needs `--allow-unqualified-host`.
   Enforced by: `release-host-smoke.yml` on both architectures before publication. `k3s-platform.test.ts` "an unknown host requires explicit qualification override without skipping resource checks".
5. **The platform layer is the cluster administrator's.** It holds the Gateway API controller, a network plugin that enforces NetworkPolicy, and cert-manager. It also holds the agent-sandbox controller with its definitions, and the sandbox RuntimeClass. On k3s, `deploy/platform/` installs them and pins each version. The Gateway controller there is our pinned Traefik, not the one that k3s bundles. Thus a release can update it without a k3s patch.
   Enforced by: `platform-pins.yml` (`scan-platform-images.ts --check-pins`). `crd-schemas.test.ts` checks that the pinned definitions cover each kind that the chart renders.
6. **The chart installs only the release.** It renders the workloads, the migration Job, one `HTTPRoute`, the NetworkPolicies and the sandbox templates. At 1.0 the workloads are the server and the bundled PostgreSQL. The server also serves the web app. It creates no Namespace, Gateway, CRD or Secret, and no Helm hook. PriorityClasses are its only cluster-scoped objects.
   [#4079](https://github.com/hephaestus-build/Hephaestus/pull/4079) removed the worker. [#4100](https://github.com/hephaestus-build/Hephaestus/pull/4100) removed the webhook role. No pull request yet removes the web app container, as ADR 0100 decides.
   On a managed cluster, the `cluster` profile renders namespaced objects only. The cluster's admins apply every cluster-scoped object from `deploy/platform/hephaestus-cluster-admin.yaml`, and the agent-sandbox controller runs in the release namespace ([#4090](https://github.com/hephaestus-build/Hephaestus/pull/4090)).
   Enforced by: the role image and route assertions in `kubernetes-chart-render.test.ts`.
7. **cert-manager issues every certificate.** On k3s, a public ACME issuer gives the host certificate through HTTP-01 on the Gateway. Workspace subdomains need an operator's DNS-01 `ClusterIssuer` for the wildcard certificate.
   Enforced by: `k3s-platform.test.ts` "workspace certificates use DNS-01, and backup Jobs get their own client identity".
8. **The Kubernetes floor follows upstream support.** The chart supports the minors that the [Kubernetes project](https://kubernetes.io/releases/) maintains. `kubeVersion` in `deploy/chart/hephaestus/Chart.yaml` is the floor. When upstream ends a minor, the next release raises the floor. While the first managed cluster runs a maintained minor, the floor is that minor.
   Enforced by: Helm refuses a cluster below `kubeVersion`. `ci-contract.test.ts` pins which run tests the floor and which tests the latest minor. No test ties `kubeVersion` to the CI floor or to an upstream end-of-life date.
9. **CI installs and upgrades the chart on the floor and on the latest minor.** A kind cluster with three nodes, Calico and an external PostgreSQL runs on the floor. The `arm64` run on the floor runs each night. `.github/workflows/ci-kubernetes.yml` holds the matrix.
   Enforced by: the same `ci-contract.test.ts` test.
10. **Sandbox nodes need user namespaces**, as [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) rule 6 decides. On the one-host platform, the sandbox templates use gVisor, and gVisor Pods keep host users ([google/gvisor#13303](https://github.com/google/gvisor/issues/13303)). Sandbox nodes need encrypted storage.
    Enforced by: `KubernetesIsolationProbeTest#shouldRejectAProofWithoutUserNamespacesEvenWithAnIsolatingRuntime`. `kubernetes-chart-render.test.ts` "each sandbox kind has a cold-start template of one container, behind the namespace's own policies". Encrypted storage is not enforced. The admin guide states it.
11. **PostgreSQL is the state.** The chart bundles one PostgreSQL StatefulSet with pgBackRest, or it uses an external PostgreSQL. [ADR 0038](0038-postgresql-18-release-baseline.md) sets the major version. High availability is not a 1.0 promise.
    Enforced by: the kind run with an external PostgreSQL. The latest-minor run backs up to S3 and restores to a point in time.
12. **NATS goes**, as [ADR 0080](0080-provider-events-go-to-a-postgresql-inbox.md) decides. [#4100](https://github.com/hephaestus-build/Hephaestus/pull/4100) removed the webhook role. [#4150](https://github.com/hephaestus-build/Hephaestus/pull/4150) owns its removal from the chart and `server/compose.yaml`.
13. **One migration owner.** Each Helm revision runs one migration Job, not a hook, because a hook runs before the bundled PostgreSQL exists. `--wait-for-jobs` fails an upgrade whose migration fails. `values.schema.json` refuses unknown keys.
    Enforced by: the migration Job and values schema assertions in `kubernetes-chart-render.test.ts`.

Release signing, channels and host pulls stay with ADR 0034 and ADR 0042. Previews stay with ADR 0059.

## What changed, and why

- **The titles agree.** ADR 0041's title said the opposite of 1.0. ADR 0058 decided the chart only. This ADR keeps that decision.
- **The floor names a rule, not versions.** ADR 0060 listed three minors. Upstream maintains the three newest minors for about one year each ([releases](https://kubernetes.io/releases/)). `Chart.yaml` is the one home of the number.
- **gVisor keeps host users.** ADR 0060 said that every sandbox Pod sets `hostUsers: false`. The chart omits it for `gvisor`, and the one-host platform selects `gvisor`.
- **cert-manager, not Traefik, issues the wildcard.** ADR 0053 said that Traefik gets the certificate. The installer takes an operator's DNS-01 `ClusterIssuer`.
- **The Docker driver and the substrate-split rule are gone** (ADR 0068 decision D). No Docker backend exists (ADR 0081 rule 6).
- **The 19 gates of ADR 0058 are release qualification, not a decision.** [Release management](../contributor/release-management.mdx) owns the host qualification. [ADR 0092](0092-ci-builds-each-image-once-a-release-signs-one-lock-and-hosts-pull-it.md) consolidates the release ADRs.

Where the other parts of ADR 0041 went:

- § State and storage: PostgreSQL is rule 11 here, and NATS is in ADR 0080. "No object store, no Git server" is in ADR 0090.
- § One job folder, § Evidence admission and deletion, the debug-transcript update: ADR 0090.
- § Sandbox Gateway, § Bash and the sandbox boundary, § Roles: ADR 0081 rules 1, 5 and 6.
- § Kubernetes batch and interactive execution, the 2026-10-24 execution, workers and network parts: ADR 0079 and ADR 0081.
- The 2026-10-24 deployment, state, backups and migrations parts: rules 4 to 13 here, and the admin guide.
- The migration state table: [the 0.95.0 move guide](https://github.com/hephaestus-build/Hephaestus/blob/v0.95.0/docs/admin/compose-to-kubernetes.mdx).

## Consequences

- Good: each release qualifies one deployment. The Compose and Coolify paths are gone from the repository.
- Good: Pod Security admission, NetworkPolicy and user namespaces enforce the sandbox boundary, not a Docker socket.
- Good: the operator verifies one signed chart. The installer needs no Node.js and no checkout.
- Bad: Kubernetes is an operator prerequisite from 1.0. An operator needs `kubectl` during an incident.
- Bad: an operator upgrades the cluster about once a year to stay on a supported release.
- Bad: the qualified host is narrow. A different OS or kernel needs a rehearsal with the override.
- Bad: on a managed cluster, the agent-sandbox controller reads Pods, Services and NetworkPolicies in all namespaces. Its version cannot limit its reads to one namespace ([managed cluster](../admin/managed-cluster.mdx)).
- Bad: a one-host upgrade stops every role. The server uses `Recreate` until ADR 0081 rule 1 ships.
- Bad: the platform pins can block a release when an upstream add-on changes.

## Confirmation

Each rule names its test or gate. Three items are not enforced. No test ties `kubeVersion` to the CI floor or to upstream support. No test refuses a new production Compose file. No check proves encrypted node storage. Release review catches the first, and code review catches the second.

## Revisit trigger

- Operators who need Hephaestus cannot run any Kubernetes, and a maintainer staffs a second deployment with its own qualification.
- Upstream ends the floor minor. The next release then raises `kubeVersion`.
- gVisor supports user namespaces ([google/gvisor#13303](https://github.com/google/gvisor/issues/13303)). Then gVisor sandboxes set `hostUsers: false`.
- agent-sandbox ships a namespaced install. Then the chart can own the controller everywhere.

## Sources

- Kubernetes, [Releases](https://kubernetes.io/releases/): the project maintains the three most recent minor releases. Each gets about one year of patch support. This makes the floor a rule (rule 8), and it gives the yearly cluster upgrade.
- Helm, [Charts § kubeVersion and schema files](https://helm.sh/docs/topics/charts/): Helm will "fail if the cluster runs an unsupported Kubernetes version". It validates `values.schema.json` on install, upgrade, lint and template. Thus `kubeVersion` and the schema enforce rules 8 and 13 without our code.
- Helm, [Custom Resource Definitions](https://helm.sh/docs/chart_best_practices/custom_resource_definitions/): "There is no support at this time for upgrading or deleting CRDs using Helm." The separate method suits "cluster operators who have admin access". This keeps the agent-sandbox definitions in the platform layer (rules 5 and 6).

## Already priced and declined

Each line links the section that priced the option.

- [ADR 0041](0041-compose-1x-kubernetes-2.md#considered-options): Compose forever, and a permanent dual backend.
- [ADR 0041](0041-compose-1x-kubernetes-2.md#rejected-alternatives): a Compose floor after 2.0, a k3s appliance, and two full sandbox backends.
- [ADR 0041](0041-compose-1x-kubernetes-2.md#rejected-alternatives): a PostgreSQL ingest table. ADR 0080 reverses this rejection, because NATS goes.
- [ADR 0041](0041-compose-1x-kubernetes-2.md#rejected-alternatives): evidence copies in PostgreSQL, object storage, and a Git server. ADR 0090 prices them again.
- [ADR 0041](0041-compose-1x-kubernetes-2.md#rejected-alternatives): a local-directory store adapter, incremental context publication, tool catalogues or MCP for context, and a shell allowlist.
- [ADR 0041](0041-compose-1x-kubernetes-2.md#rejected-alternatives): bare Pods or remote exec for batch.
- [ADR 0041](0041-compose-1x-kubernetes-2.md#rejected-alternatives): agent-sandbox for all batch work. ADR 0079 reverses this rejection.
- [ADR 0041](0041-compose-1x-kubernetes-2.md#sandbox-gateway-and-protocol-v3): a Squid egress sidecar ([#1108](https://github.com/hephaestus-build/Hephaestus/issues/1108)).
- [ADR 0041](0041-compose-1x-kubernetes-2.md#roles-control-channel-and-previews): a customer-hosted remote worker ([#1318](https://github.com/hephaestus-build/Hephaestus/issues/1318)).
- [ADR 0041](0041-compose-1x-kubernetes-2.md#update--2026-10-24-the-kubernetes-runtime): Helm hooks for the migration, chart-testing (`ct`), and a Namespace from the chart.
- [ADR 0058](0058-the-helm-chart-is-the-only-supported-deployment-from-1-0.md#considered-options): ADR 0041 as written, and 1.0 on Compose with a preview chart.
- [ADR 0058](0058-the-helm-chart-is-the-only-supported-deployment-from-1-0.md#considered-options): a k3s `HelmChart` resource for the application.
- [ADR 0058](0058-the-helm-chart-is-the-only-supported-deployment-from-1-0.md#upgrades): a GitOps controller in place of the reconciler.
- [ADR 0058](0058-the-helm-chart-is-the-only-supported-deployment-from-1-0.md#update--2026-10-28-the-docker-driver-goes-adr-0068): an appliance or an operator.
- [ADR 0060](0060-the-kubernetes-floor-follows-upstream-support.md#decision): support for a minor that upstream no longer maintains.
