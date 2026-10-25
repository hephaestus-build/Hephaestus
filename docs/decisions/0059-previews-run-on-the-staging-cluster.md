# ADR 0059: Previews run on the staging cluster

**Status:** Accepted
**Date:** 2026-10-25
**Authors:** Felix T.J. Dietrich

**Supersedes:** the mechanism of [ADR 0035](0035-pull-request-previews-are-label-gated.md): the Coolify deployment, its admission and lifecycle workflows, and the updates of 2026-09-17, 2026-10-16, and 2026-10-18. ADR 0035 keeps its label policy.

**Amends:** [ADR 0058](0058-the-helm-chart-is-the-only-supported-deployment-from-1-0.md) § Previews. This decision names the mechanism that ADR 0058 leaves open.

## Context

ADR 0035 deployed each labelled pull request as a Compose stack on Coolify, on the staging VM.
The Coolify path is off, and [ADR 0058](0058-the-helm-chart-is-the-only-supported-deployment-from-1-0.md) deletes it.
The staging host moves to k3s and installs the same Helm chart as every other deployment.
A preview on that host must do five things:

1. List the open pull requests with the label `preview`.
2. Install the chart with the images of each head commit.
3. Give each preview its secrets.
4. Limit the number of previews.
5. Remove a preview when its pull request no longer qualifies.

[ADR 0042](0042-hosts-pull-their-own-releases.md) removed the SSH push path into the hosts.
The Coolify API call of ADR 0035 was the last path from CI to a host, and this decision removes it.
Thus, GitHub Actions does not run `helm` against the staging cluster.

## Decision drivers

- Nothing connects to the host, and GitHub holds no deploy secret.
- A pull request cannot change what its preview runs, except the application code.
- A preview never reads staging's data or staging's event stream.
- The number of previews and their resources have a limit that the cluster enforces.
- The state of the pull request decides the removal, not the absence of a resource from a list.
- One maintainer operates the result. Each extra component needs a reason.

## Considered options

| Option | Result |
| --- | --- |
| Argo CD ApplicationSet with the pull request generator [A1] | Rejected. It cannot copy a Secret into a new namespace without External Secrets or Kyverno, and it has no limit on the number of previews. A pull request generator can expose secrets when non-admins create ApplicationSets [A2]. |
| **Flux Operator `ResourceSet`** [F1, F2] | **Chosen.** It filters by label, gives the head commit, limits the count, copies a Secret, and removes what leaves the list. |
| `helm` from GitHub Actions | Rejected. It needs a reachable Kubernetes API and a kubeconfig in GitHub secrets. ADR 0042 removed this push path. |
| vcluster | Rejected. A control plane for each pull request is necessary only when a preview installs CRDs or cluster-scoped objects. The preview does not. |
| Okteto | Rejected. A second, commercial platform for one maintainer. |
| A pull request reconciler in the ADR 0042 timer | Rejected. It builds again what Flux Operator already does. |

Argo CD is the most common choice for this pattern, and it has the Apache-2.0 licence.
Flux Operator alone covers the list, the Secret copy, the limit, and the removal without a second tool.

## Decision

### The shape of a preview

A preview is the namespace `pr-<n>` on the staging k3s.
The namespace holds one release of the chart with the `preview` profile.
Its address is `https://pr-<n>.preview.hephaestus.build`.

### The reconciler

Flux Operator reconciles the previews from inside the staging cluster.
One `ResourceSetInputProvider` of type `GitHubPullRequest` lists the open pull requests with the label `preview` [F1].
One `ResourceSet` renders the objects of each preview from that list [F2].
Flux reads GitHub every 2 minutes with a read-only, fine-grained token for this repository.
The token is in the `flux-system` namespace, and no preview can read it.
The staging release itself keeps the Helm apply of ADR 0042.

Flux Operator has the GNU AGPL-3.0 licence [F3].
The project uses it unmodified on the staging cluster, and it keeps no modifications of it.
The project does not distribute it, and no Hephaestus release ships it.
The objects that it renders are plain Flux `HelmRelease` objects, so a move to another reconciler changes one file.

### What a preview runs

The chart and the preview values come from `main`.
Only the application images come from the head commit of the pull request.
Thus, a pull request changes the application code of its preview, and nothing else.

- The `application-server` and `webapp` images have the 40-hex commit tag of the head.
- NATS and PostgreSQL use the images that the chart pins.
- The preview has no worker and no webhook server. Thus, a preview runs no practice review and no Heph turn.

The chart schema refuses every image tag today, and its preview profile runs a webhook server.
A chart change for previews makes three changes.
Only the preview profile accepts a commit tag.
The preview profile has no webhook server, and its size is about 3.5 GiB.

### Data and sign-in

Each preview has its own PostgreSQL: the bundled StatefulSet of the chart, with a volume that Flux deletes with the namespace.
A synthetic demo seed fills it: a Liquibase changeset with the context `demo` [L1].
The migration Job runs that context only when the profile adds `demo` to the context filter.
Liquibase runs every context when the filter is empty.
Thus, the migrate run mode always sets a filter, and a test asserts that the default filter excludes `demo`.

Each namespace gets a cert-manager `Certificate` for the TLS of its PostgreSQL.

A separate GitHub OAuth App gives sign-in for all previews.
Its callback URL uses wildcard matching for the preview subdomains [G2].
Pull request code can read its secret, so the app grants sign-in and nothing else.
The bootstrap admins are the maintainers.
Workspace subdomains are off in previews.

### Isolation and limits

- **Namespace.** The `ResourceSet` owns the namespace, so the pull request cannot change its labels. It enforces the Restricted Pod Security level [K1].
- **Secrets.** One source Secret in the `previews` namespace holds the preview OAuth client, the keys, and the passwords. Flux copies it into each namespace [F2]. Its values protect only demo data, and no staging value enters it. Each database is separate, so a shared password reaches no other data.
- **Resources.** Each namespace has a `ResourceQuota` and a `LimitRange`. The chart's NetworkPolicies stay on, and the staging NetworkPolicies admit no preview Pod.
- **Route.** The staging Gateway has a listener for `*.preview.hephaestus.build` that admits only namespaces with the preview label. One DNS-only wildcard record points to it. cert-manager issues one wildcard certificate through DNS-01, with the zone-scoped token that [ADR 0053](0053-workspace-addresses-are-a-presentation-origin.md) already uses.
- **Origins.** ADR 0053 reserves the name `preview`, and it trusts only one label under the base domain. Thus, no staging CORS rule trusts a preview origin, and the `__Host-` cookies do not cross hosts.
- **Cap.** At most three previews run. A label workflow refuses the fourth label and names the pull requests that hold the slots. The `filter.limit: 3` of the input provider is the backstop.

### Lifecycle

1. An author adds the label `preview`. The label workflow refuses a fork pull request and checks the cap. Then it posts the address, or it removes the label and says why.
2. CI publishes the head images, as it does today for a labelled pull request.
3. Flux renders the namespace, the quota, the Secret, the certificate, and the `HelmRelease`. It retries until the images exist.
4. Each push changes the head commit. Flux upgrades the release in place.
5. When the pull request closes, merges, or loses the label, it leaves the list. Flux deletes the release, then the namespace with its volume [F2].
6. A daily workflow removes the label from drafts and from pull requests with no push for 7 days. It writes a comment that says why.

Staging alerts when a preview `HelmRelease` is not ready for 30 minutes.
The label workflow uses `pull_request_target` with only `GITHUB_TOKEN`, and it checks out no pull request code.

### What stays from ADR 0035

- The label `preview` is the opt-in. A person with push access adds it, and that is the whole authorization.
- The label is a switch, not a security boundary [GH1].
- Fork pull requests never get a preview.
- No workflow that holds a secret checks out or runs pull request code.
- At the cap, the newcomer is refused at once, and the refusal names the holders.
- A pull request can preview whatever branch it targets, so each labelled layer of a stack gets its own preview.
- A preview runs no agent, as the 2026-09-03 update of ADR 0035 requires for a preview without fixture workspaces.

### What changes from ADR 0035

- **Drafts.** A draft no longer keeps a preview. The daily workflow removes its label, because a draft holds one of three slots.
- **Rechecks.** The author-association check and the label and head rechecks before a deploy go. Flux reads the list of labelled pull requests at each poll. A pull request that loses the label leaves the list, and the next poll removes its preview.
- **Image verification.** The build attestation check goes. Flux pulls the commit tag, and nothing verifies the attestation before the pull. A check in the cluster needs an admission controller, such as the Sigstore policy controller. That is one more component.
- **The publish window.** ADR 0035 kept the previous preview live until CI published the new images. Flux upgrades at once and retries until the images exist. Thus, a preview can be unavailable while CI publishes.

## Consequences

- No preview reads staging's data. Reviewers see the demo seed, not real work. The copy, its silencing, and the `preview_seed` role go.
- A preview runs no practice review. A preview with agent execution needs its own decision, under the bounds of ADR 0041.
- The protected-path check and the Compose render check of ADR 0035 go. The pull request no longer supplies a stack file, so nothing is left for them to check.
- No check binds the commit tag to a build of this repository. Only this repository's workflows push these tags, and CI writes a commit tag once. A person with push access can change those workflows, but that person can already run CI with the repository secrets. This gap is wider than the verify-then-pull gap of ADR 0035.
- The Coolify preview scripts, workflows, and stack go: about 7,000 lines.
- The staging cluster runs one more controller, from one vendor.
- Three previews need about 10.5 GiB beside staging. The cap depends on the measured size of the staging host, and on a 16 GiB host it is one.

## Revisit trigger

- Flux Operator changes its licence or its API in a way that the project cannot accept. Then move to Argo CD with Kyverno for the Secret copy.
- Reviewers need practice reviews in a preview. Then a new decision places agent execution, with fixture workspaces only.
- A preview outlives its pull request. Then the removal needs a check from the cluster side.
- A preview image does not match its commit. Then add the admission check for the build attestation.

## Sources

Fetched 2026-10-25.

- **F1** [Flux Operator `ResourceSetInputProvider`](https://fluxoperator.dev/docs/crd/resourcesetinputprovider/) and [the pull request preview guide](https://fluxoperator.dev/docs/resourcesets/github-pull-requests/): the type `GitHubPullRequest`, `filter.labels`, `filter.limit`, and the inputs `id` and `sha`.
- **F2** [Flux Operator `ResourceSet`](https://fluxoperator.dev/docs/crd/resourceset/): the `fluxcd.controlplane.io/copyFrom` annotation, and the removal of objects that leave the inputs, Flux objects first.
- **F3** [Flux Operator licence](https://github.com/controlplaneio-fluxcd/flux-operator/blob/main/LICENSE): GNU AGPL v3.
- **A1** [Argo CD pull request generator](https://argo-cd.readthedocs.io/en/stable/operator-manual/applicationset/Generators-Pull-Request/).
- **A2** [Argo CD ApplicationSets in any namespace](https://argo-cd.readthedocs.io/en/stable/operator-manual/applicationset/Appset-Any-Namespace/): the warning about secrets and the `pullRequest` generator.
- **K1** [Pod Security Admission](https://kubernetes.io/docs/concepts/security/pod-security-admission/).
- **L1** [Liquibase contexts](https://docs.liquibase.com/concepts/changelogs/attributes/contexts.html).
- **G2** [GitHub OAuth redirect URLs](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps): wildcard matching of subdomains.
- **GH1** [GitHub Security Lab, preventing pwn requests](https://securitylab.github.com/resources/github-actions-preventing-pwn-requests/).
