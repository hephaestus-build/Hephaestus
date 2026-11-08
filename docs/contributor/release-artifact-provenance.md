---
title: Release artifact provenance
description: The SLSA Build assessment of each release artifact, its evidence, and the limits of that evidence.
page_type: reference
---

This assessment uses the [SLSA 1.2 Build track](https://slsa.dev/spec/v1.2/build-track-basics).
It describes the current release pipeline, not an independent certification of a particular release.
Verify the target release's actual subjects and attestations before relying on its evidence.

Build L1 requires build provenance.
Build L2 adds authentic provenance from a hosted build platform.
Build L3 adds stronger isolation and protection against forgery during the build.
A signature, checksum, SBOM, or release attestation alone does not establish a SLSA Build level.
[Build requirements](https://slsa.dev/spec/v1.2/build-requirements) owns the full criteria.

## Assessment by artifact

| Artifact | Build assessment | Evidence and limit |
| --- | --- | --- |
| `application-server` image index | Build L2 | The reusable image workflow runs on GitHub-hosted runners and publishes native GitHub build provenance for the index digest. The release gate verifies that provenance. |
| `webapp` image index | Build L2 | The same workflow and verification apply to this image's own index digest. |
| `agent-pi` image index | Build L2 | The same workflow and verification apply to this image's own index digest. |
| `postgres` image index | Build L2 | The same workflow and verification apply to this image's own index digest. |
| First-party platform manifests | No separate Build claim | The image index binds the child manifest digests. Each platform has its own signed SBOM and scan evidence. The native build attestation names the index. |
| Upstream image indexes and platform manifests | No Hephaestus Build claim | The release pins upstream digests and captures platform membership, inventories, licenses, and scans. It does not require upstream build provenance. |
| Helm chart package and OCI manifest | No Build claim | The release signs the chart digest and includes both subjects in a release attestation. That in-toto `release/v0.1` predicate is not SLSA build provenance. |
| k3s bundle | No Build claim | The release signs the bundle and includes its digest in the release attestation. The bundle has no separate build provenance. |
| Joint release lock | No Build claim | Its signature binds the selected chart, images, source commit, and release. It selects artifacts but does not describe their build process. |
| SBOMs, source inventories, scan reports, and policy evidence | No Build claim | Signed checksums bind the evidence files. Image SBOM attestations bind first-party platform subjects. Neither proves how those subjects were built. |
| Native host qualification reports | No Build claim | The release attaches amd64 and arm64 test records after signing the evidence checksums. Those checksums do not cover the host reports. |
| Source archives supplied by GitHub | No Build claim | GitHub creates these archives. The release pipeline does not generate or verify build provenance for them. |

The upstream set comes from `security/release-images.json`.
Reassess a new artifact or publisher when that inventory or the release workflow changes.

GitHub documents native artifact attestations as a [SLSA v1.0 Build L2 mechanism](https://docs.github.com/en/actions/concepts/security/artifact-attestations).
This assessment does not claim Build L3 for any artifact.
GitHub also describes a reusable-workflow path to Build L3.
This repository has no separate assessment that proves every Build L3 isolation requirement.
Thus, this assessment stops at Build L2 for the first-party image indexes.
The assessment does not extend to their base images, buildpack builder, or other dependencies.

## Verification scope

The release gate verifies each first-party image index against its full source commit, allowed build workflow, SLSA v1 predicate, and hosted-runner identity.
[Image build provenance](../admin/release-image-lock#image-build-provenance) gives the independent verification commands.
The expected subjects and source commit come from the authenticated joint lock.
Consumers verify their target release independently before relying on its provenance.

A reused image keeps the build provenance of its merge-group run.
The subsequent `main` run signs that same digest without rebuilding it.
The two statements can therefore name different workflow invocations.

Promotion preserves image digests and their build provenance.
A stable promotion repackages chart metadata and signs the resulting chart digest.
A valid release signature does not upgrade the Build assessment of that chart or any upstream image.

## Owning implementation

- `.github/workflows/reusable-docker-build.yml` builds, signs, and attests first-party image indexes.
- `.github/workflows/release.yml` promotes images and packages the chart, lock, k3s bundle, and evidence.
- `scripts/verify-release-evidence.ts` verifies image provenance and the per-platform evidence contract.
- [Supply-chain evidence](./release-management#supply-chain-evidence) owns the release evidence requirements.
