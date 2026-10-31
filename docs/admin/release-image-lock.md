---
id: release-image-lock
title: Release image lock
description: Verify the image digests selected by a Hephaestus release.
---

# Release image lock

Each release publishes `release-vX.Y.Z.chart.json` with its Sigstore bundle.
This joint lock records the release, source commit, chart repository, chart version and chart digest.
It also records the provenance class, repository, multi-platform index digest and platform child digests of every application image.
The chart uses these image digests. Tags identify releases for people.

Before an install or upgrade, verify the lock as [Independent verification](#independent-verification) describes.
Then follow [Verify the release chart](./kubernetes#verify-the-release-chart).
That procedure authenticates the chart and checks its release version. It reads the lock only to find the chart digest.
The installed reconciler verifies the lock signature itself before it applies the chart that the lock names.

:::note[Chart-only release]
These instructions describe the chart-only release.
For a 0.95.x deployment, use that release's versioned instructions and signed assets.
The [0.x upgrade path](/admin/upgrade-to-1-0) requires 0.95.1 and its installed reconciler tooling before the cutover.
:::

## Release assets

The release builds one joint lock from its verified evidence and chart.
Every asset that names an image uses the same image digests.
The signing identity is `release.yml` on `main` in `hephaestus-build/Hephaestus`.

| Asset | Contents | Reader |
| --- | --- | --- |
| `release-vX.Y.Z.chart.json` and its `.sigstore.json` bundle | The joint lock of schema version 2. | [Chart verification](./kubernetes#verify-the-release-chart), the installer and the reconciler. |
| The chart at `ghcr.io/hephaestus-build/charts/hephaestus@sha256:…`, signed in the registry | Release metadata and the five chart images by digest. | Helm, after chart verification. |
| `k3s-install.sh`, `k3s-platform.yaml`, `k3s-certificates.yaml`, `k3s-config.yaml`, `k3s-kubelet.yaml`, `k3s-logind.conf`, `k3s-values.yaml`, each with its `.sigstore.json` bundle | The installer and the platform and host configuration. | [Install on one host](./kubernetes#install-on-one-host-k3s). |
| `release-vX.Y.Z.intoto.jsonl` | The release attestation bundle. Subjects include the first-party image digests, chart manifest and package, joint lock and platform assets. | [Independent verification](#independent-verification). |
| `manifest.json`, SBOMs, vulnerability and license reports, and signed `SHA256SUMS` | Per-platform release evidence. | [Operator verification](/contributor/release-management#operator-verification). |
| `host-smoke-k3s-amd64.json`, `host-smoke-k3s-arm64.json` | The native k3s install, repeated-install, upgrade and isolation qualification records. | Operators who check the hosts that the release passed on. |

A stable release that promotes a qualified candidate keeps its image digests.
The chart is packaged from the same source with stable release metadata.
The joint lock names the new chart digest.
Image build metadata retains the candidate version. The chart reports the stable release and source commit.

## Independent verification

Use the target release's joint lock:

```bash
VERSION=X.Y.Z
LOCK="release-v$VERSION.chart.json"
gh release download "v$VERSION" --repo hephaestus-build/Hephaestus \
  --pattern "$LOCK" --pattern "$LOCK.sigstore.json" \
  --pattern "release-v$VERSION.intoto.jsonl"
cosign verify-blob \
  --bundle "$LOCK.sigstore.json" \
  --certificate-identity \
    'https://github.com/hephaestus-build/Hephaestus/.github/workflows/release.yml@refs/heads/main' \
  --certificate-oidc-issuer 'https://token.actions.githubusercontent.com' \
  "$LOCK"
gh attestation verify "$LOCK" \
  --bundle "release-v$VERSION.intoto.jsonl" \
  --predicate-type https://in-toto.io/attestation/release/v0.1 \
  --repo hephaestus-build/Hephaestus
```

Continue with [Verify the release chart](./kubernetes#verify-the-release-chart) to authenticate and inspect the chart that the lock names.
Do not use the lock contents before signature verification succeeds.

## Rollback

Follow [Rollback](./upgrade#rollback).
Restore the database, secrets and signed release together when schema compatibility requires it.
Do not reconstruct an old lock or resolve image tags.

## Runtime identity handoff

The chart gives the server, worker and webhook Pods `APP_VERSION`, `HEPHAESTUS_RELEASE_COMMIT` and `HEPHAESTUS_IMAGE_APPLICATION_SERVER` from the verified release metadata.
Each process reports these values, with its runtime roles, under `release` in `/actuator/info`.
The chart's `deploymentEnvironment` value identifies the environment.
This is deployment metadata from the verified lock, not a second signature verification or an observation of the container.
Malformed identity values stop startup.

`/actuator/info` is unauthenticated, like the adjacent health probes.
After an upgrade, compare `release` on each role's private management listener.
The administration overview shows the server that it connects to.
The instance-admin API adds the update check: `GET /admin/release` reads it and `POST /admin/release/checks` asks GitHub now.
[Update checks](./upgrade#update-checks) describes cadence, privacy and offline operation.
