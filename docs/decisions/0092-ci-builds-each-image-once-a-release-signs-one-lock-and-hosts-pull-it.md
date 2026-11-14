# ADR 0092: CI builds each image once, a release signs one lock that names it, and hosts pull that lock

**Status:** Accepted
**Date:** 2026-11-10
**Authors:** Felix T.J. Dietrich

**Supersedes:** [ADR 0034](0034-signed-release-image-lock.md), [ADR 0042](0042-hosts-pull-their-own-releases.md)

**Builds on:** [ADR 0091](0091-the-signed-helm-chart-is-the-only-supported-deployment-and-one-host-runs-it-on-k3s.md), [ADR 0089](0089-pinned-node-and-pnpm-install-the-repository-and-vite-plus-runs-every-command.md)

**Relates to:** [ADR 0031](0031-agent-image-follows-the-deployments-own-tag.md), [ADR 0070](0070-1-0-is-a-cutover-release.md)

## Context

ADR 0034 and ADR 0042 carried five updates between them.
Their Decision and Consequences sections still describe Docker Compose, a Compose validator, the Docker socket and Coolify previews.
At 1.0 the release ships one Helm chart, and the lock is `release-vX.Y.Z.chart.json`.
This ADR states how a release is built, signed and delivered at 1.0 as rules.
[Release management](../contributor/release-management.mdx) and [CI/CD § Build once](../contributor/ci-cd.mdx#build-once) own the detail.
[Pull-based deployment](../admin/pull-based-deployment.mdx) owns the host mechanics.

## Decision drivers

- Self-hosters can verify a release (AGENTS.md, non-negotiable 3). It has signed images, provenance and SBOMs. The vulnerability policy runs at build and release time.
- The artifact that CI tests is the artifact that ships (AGENTS.md, non-negotiable 4).
- Nothing outside a host holds a credential that can change the host.
- A valid but old instruction must not move a host back. A signature proves the author, not that the instruction is current.

## Considered options

1. **Rebuild at release time.** The release then ships bytes that no merge group tested. This breaks non-negotiable 4.
2. **Promote the CI digests, and sign one lock that names them (chosen).** The release builds nothing. It judges, signs and publishes what CI built.
3. **CI pushes each release to the hosts over SSH.** This was the path before ADR 0042. It puts host keys in CI and needs an SSH entry point that the internet can reach.
4. **A GitOps controller (Flux or Argo CD) follows a tag.** A mutable tag has no order, so an old state can be replayed. GHCR offers no tag immutability.
5. **Hosts pull a signed channel from a Git branch (chosen).** Git history gives the order, and the signing identity gives the approval.
6. **SLSA Build L3 through a separate builder repository.** Declined for 1.0. The image workflow already isolates signing from the build steps. The builder lives in this repository, so a reviewed pull request can change it.

## Decision

1. **CI builds each image once.** A merge group builds, scans, signs and attests each image for its commit. The push to `main` reuses that digest. The server JAR is packaged once, and every server suite tests its classes.
   Enforced by: the build-once and image-reuse cases in `scripts/ci-contract*.test.ts`.
2. **The release builds no image.** `release.yml` resolves the digests of the released commit and promotes them. A stable release that promotes a candidate keeps the digests of the candidate.
   Not enforced. `release.yml` has no build step, and review catches one.
3. **The vulnerability policy runs at build time and at release time.** `security/vulnerability-policy.json` judges each platform of each image where it is built, and again in the release evidence gate.
   1.0 target: the one-host platform images come under the same policy in [#4073](https://github.com/hephaestus-build/Hephaestus/pull/4073). These are k3s, Traefik, cert-manager and the agent-sandbox controller.
   Enforced by: the vulnerability policy cases in `ci-contract*.test.ts`.
4. **No release exists before its evidence passes.** Each image in `security/release-images.json` gets an SPDX and a CycloneDX SBOM, a licence report and a vulnerability report for each platform. Upstream images get the same evidence. The draft release exists only after this gate passes. Signed `SHA256SUMS` cover the evidence.
   Enforced by: the evidence gate cases in `ci-contract*.test.ts`, and `scripts/check-release-image-inventory.test.ts`.
5. **Each first-party image carries signed build provenance and a signed SBOM.** GitHub-hosted runners make native SLSA v1 provenance. The release attaches the SPDX SBOM to each platform digest as a Cosign attestation. The release gate verifies the signer workflow, the source commit and the hosted runner. The claim is Build L2 ([Release artifact provenance](../contributor/release-artifact-provenance.md)).
   Enforced by: `scripts/verify-release-evidence.ts --verify-signatures` in `release.yml`, and `scripts/verify-release-evidence.test.ts`.
6. **One lock names every application image.** `release-vX.Y.Z.chart.json` names the release, the source commit, the chart digest, and the index and platform digests of each application image. The default values of the chart pin exactly these digests. The signed k3s bundle pins the platform images.
   Enforced by: `scripts/release-image-lock.test.ts` ("refuses evidence that is not exactly represented by the lock"), `scripts/release-chart.test.ts` and `check-release-image-inventory.test.ts` ("rendered chart consumers reject unlocked images").
7. **One keyless identity signs the release.** `release.yml` on `refs/heads/main` signs the lock, the chart digest, the k3s bundle, the extension package and `SHA256SUMS` with Cosign keyless. No long-lived signing key exists. Each verifier pins that exact identity and the GitHub OIDC issuer. A published release must be immutable.
   Enforced by: `scripts/keyless-sign.test.ts`, `scripts/release-deployment-policy.test.ts` ("verification identity is the release's own") and the `isImmutable` check in `publish-release`.
8. **The verifiers of earlier releases decide.** Before publication, the verifiers of the nearest published release and the latest stable release check the draft. A planned break needs a reviewed entry in `security/accepted-verifier-breaks.json`.
   Enforced by: `scripts/check-previous-verifier.test.ts`.
9. **The agent image is a lock digest, never a channel tag.** Every runtime role refuses an agent image reference that is untagged or names a channel tag (`latest`, `stable`, `edge`, `main` or a version series). The chart sets `require-digest`, so the server also refuses a reference without a digest. The release refuses an agent image whose runtime-contract label differs from the server constant.
   Enforced by: `AgentImageReferenceGuardTest`, `AgentImagePinGuardTest`, `AgentImageContractSyncTest`, and the "Verify Node-only agent-pi sandbox" step of `release.yml`.

10. **Hosts pull. Nothing connects to a host.** Publication deploys nothing. Each environment has one channel file on the `deploy-state` branch, and **Release / Promote** signs it. The host verifies the signature before it parses the channel.
    Enforced by: `scripts/reconcile-deployment.test.ts` ("an unsigned channel or incomplete commit lock never reaches Helm").
11. **A host accepts only a descendant channel commit.** A lower release or an older commit also needs `allowRollback` on the new channel commit. The flag never authorizes a replay.
    Enforced by: the replay, downgrade and rewind cases in `reconcile-deployment.test.ts`.
12. **The approval is the identity of `promote.yml`.** Hosts pin that workflow identity. A GitHub environment with required reviewers gates the job. `promote.yml` runs only by `workflow_dispatch` and is never reusable.
    Not enforced for the trigger set. The header comment of `promote.yml` states the rule, and review catches a `workflow_call` trigger.
13. **Staging follows `main`, and Production runs releases.** A commit channel names a commit on `main` and the digests of its build. The promotion checks each digest against build provenance for that commit. The channel signature covers the digests.
    Enforced by: `scripts/resolve-promotion.test.ts` ("a commit outside the default branch is refused before any image is resolved") and `scripts/commit-image-lock.test.ts`.
    Production is enforced by: `scripts/resolve-promotion.test.ts` ("an environment that runs releases refuses a commit of the default branch").
14. **The apply is one `helm upgrade --install`.** A release channel applies the signed chart of that release. A commit channel applies the source chart of that commit with the verified pins. The lock images come last. The flags are `--rollback-on-failure --wait=watcher --wait-for-jobs`.
    Enforced by: the chart-host and commit-channel apply cases in `reconcile-deployment.test.ts`.
15. **A release never supplies its own verifier.** The host verifies with the tooling of the last release that it applied. It adopts the tooling of the next release only after it applies that release.
    Enforced by: `reconcile-deployment.test.ts` ("a tree is adopted as tooling only when its own unit runs through the tooling link").
16. **Rollback is a promotion.** It selects an earlier published release and its signed lock. Nothing rebuilds a lock or resolves a tag. The database comes back only from its backup ([Rollback](../admin/upgrade.mdx#rollback)).
    Enforced by: `reconcile-deployment.test.ts` ("moving deliberately backwards is still possible") and `scripts/prepare-release-chart.test.ts`.
17. **Feedback is observed.** The promotion polls the public webapp until it reports the version. Each host exports metrics, and an alert fires when a host stops reconciling.
    Enforced by: `deploy/chart/hephaestus/files/hephaestus-alerts.test.yml` (`HephaestusDeployReconcileStale`, `HephaestusDeployReconcileFailing`).

An operator without the reconciler verifies the chart with Cosign and installs it with Helm ([ADR 0091](0091-the-signed-helm-chart-is-the-only-supported-deployment-and-one-host-runs-it-on-k3s.md) § Upgrades).

## What changed and why

- **The lock is the chart lock** (ADR 0034 § Decision). The Compose validator and the Compose lock went with the Compose files ([ADR 0058](0058-the-helm-chart-is-the-only-supported-deployment-from-1-0.md) § Supported deployment). An operator needs Cosign and Helm. The reconciler needs the Node.js that ADR 0089 pins.
- **The platform pins are in the signed k3s bundle, not in the lock.** The 2026-09-03 update of ADR 0034 put the agent-sandbox controller in the lock. The chart does not install the controller, so the bundle that installs it pins it.
- **The reconciler holds Kubernetes credentials, not the Docker socket** (ADR 0042 § Consequences). Coolify previews are gone ([ADR 0059](0059-previews-run-on-the-staging-cluster.md)).
- **A failed apply rolls the workloads back.** ADR 0042 § Decision said "A failed apply stops". Helm returns the workloads, not the database. This is safe only because the previous release must run on the new schema. [ADR 0058](0058-the-helm-chart-is-the-only-supported-deployment-from-1-0.md) § Gates before v1.0.0 set that rule.
- **The agent image rule of ADR 0031 stays, and its mechanism changed.** The server-side label check went with the Docker backend. The release now checks the label. ADR 0031 deferred "resolve the digest in CI and flow it to the deployment" (option 5). A commit channel now does exactly that.

## Consequences

- Good: no deploy key, jump host or production secret is in CI. Secrets live only on the host that uses them.
- Good: the `deploy-state` history is the deployment audit log, and a rollback is one gated promotion.
- Good: an operator verifies one lock and one chart digest with one exact identity.
- Bad: the reconciler holds the Kubernetes credentials of the chart. A host compromise gives them to the attacker.
- Bad: an operator must stop the timer before a manual change to a host. Otherwise the next tick reverts the change.
- Bad: a change to what `promote.yml` writes must stay readable by the tooling of every release that a rollback can reach. No test checks the channel format across releases.
- Bad: a commit channel gets no SBOM, licence or release vulnerability gate. That is acceptable only for Staging.
- Bad: the claim stays at Build L2. A consumer that requires L3 cannot use the images as built.

## Confirmation

Each rule names its test or gate.
Rules 2 and 12 have no test. Review catches a build step in `release.yml` and a new trigger in `promote.yml`.

## Revisit trigger

- Cosign can verify the GitHub `environment` claim of a Fulcio certificate. Then the approval can bind to the environment, not to the workflow.
- A consumer asks for Build L3, or the image workflow moves to its own repository.
- A host misses a channel, and no staleness alert fired.

## Sources

- SLSA, [Security levels v1.0](https://slsa.dev/spec/v1.0/levels). L2 needs provenance "tied to that infrastructure through a digital signature". L3 must keep signing secrets from "the user-defined build steps". This sets the honest claim of rule 5 at L2, and it names the gap that option 6 declines.
- Sigstore, [Signing overview](https://docs.sigstore.dev/cosign/signing/overview/). Fulcio binds "an ephemeral key to an OpenID Connect identity", and Rekor logs each signing event. Rule 7 therefore has no key to store or rotate. The verifier pins an identity, not a key.
- GitHub Docs, [Artifact attestations](https://docs.github.com/en/actions/concepts/security/artifact-attestations). "Artifact attestations by itself provides SLSA v1.0 Build Level 2." Reusable workflows can isolate the build for L3. This is the evidence for rule 5 and the cost of option 6.

## Already priced and declined

Each line links the section that priced the option.

- [ADR 0034](0034-signed-release-image-lock.md#consequences): deploying a branch, channel or version tag.
- [ADR 0034](0034-signed-release-image-lock.md#consequences): a release-pin init container and a production pin-file import.
- [ADR 0034](0034-signed-release-image-lock.md#revisit-trigger): native Compose verification of signed image sets.
- [ADR 0042](0042-hosts-pull-their-own-releases.md#context): a CI-driven SSH push through a jump host.
- [ADR 0042](0042-hosts-pull-their-own-releases.md#decision): a channel as an OCI artifact under a mutable tag.
- [ADR 0042](0042-hosts-pull-their-own-releases.md#decision): approval checked from the Fulcio `environment` claim, which Cosign cannot verify.
- [ADR 0042](0042-hosts-pull-their-own-releases.md#decision): one environment that switches between the branch and releases by a setting.
- [ADR 0042](0042-hosts-pull-their-own-releases.md#decision): a release that also promotes Staging.
- [ADR 0042](0042-hosts-pull-their-own-releases.md#consequences): rootless Podman with Quadlet.
- [ADR 0042](0042-hosts-pull-their-own-releases.md#update--2026-10-25-helm-apply-adr-0058): a GitOps controller in place of the reconciler.
- [ADR 0031](0031-agent-image-follows-the-deployments-own-tag.md#considered-options): a branch tag as the agent image default.
- [ADR 0031](0031-agent-image-follows-the-deployments-own-tag.md#considered-options): no default, with an explicit reference everywhere.
- [ADR 0031](0031-agent-image-follows-the-deployments-own-tag.md#considered-options): a digest lookup at deploy time without a signed asset.
- [ADR 0031](0031-agent-image-follows-the-deployments-own-tag.md#considered-options): a probe container that runs the agent image at startup.
