---
id: release-image-lock
title: Release image lock
description: Verify the image digests selected by a Hephaestus release.
---

# Release image lock

Each release publishes `release-vX.Y.Z.json` with its Sigstore bundle. The lock records the release,
source commit, provenance class, repository, multi-platform index digest, and supported platform child
digests for every deployed image. Compose uses the index digests. Tags identify releases for people.

Follow the [install guide](./install) to install or upgrade. Its verifier checks the signature, signer
identity, release identity, schema, and equality with the release evidence manifest before writing
`release-lock.env`.

## Release assets

Each release builds one lock from its verified evidence.
Every asset that names an image gets its digests from that lock, so no two assets can name different images.
The release workflow signs each asset with the same keyless identity, `release.yml` on `main`.

| Asset | Contents | Reader |
| --- | --- | --- |
| `release-vX.Y.Z.json` and its `.sigstore.json` bundle | The lock of schema version 1: release, source commit, and every image by digest. | Compose hosts, through the [install guide](./install) and the [pull-based deployment](./pull-based-deployment). Releases keep this asset until the Compose path is removed after 1.0. |
| `release-vX.Y.Z.chart.json` and its `.sigstore.json` bundle | The joint lock of schema version 2: the schema 1 lock and the chart's OCI repository, version, and digest. | Kubernetes operators look up the chart digest in it, as [Verify the release chart](./kubernetes#verify-the-release-chart) describes. The release workflow verifies the chart from it. |
| The chart, `ghcr.io/hephaestus-build/charts/hephaestus@sha256:…`, with its signature in the registry | The Helm chart. Its values name the release version, the source commit, and the five images that it runs, by digest. | Kubernetes operators, after `cosign verify` on the digest ([Verify the release chart](./kubernetes#verify-the-release-chart)). |
| `k3s-install.sh`, `k3s-platform.yaml`, `k3s-certificates.yaml`, `k3s-config.yaml`, `k3s-kubelet.yaml`, `k3s-logind.conf`, `k3s-values.yaml`, each with its `.sigstore.json` bundle | The installer and the platform and host files of the one-host install. `k3s-platform.yaml` pins k3s, Traefik, and cert-manager. | Operators of [Install on one host (k3s)](./kubernetes#install-on-one-host-k3s). |
| `release-vX.Y.Z.intoto.jsonl` | The bundle of the release attestation. Its subjects are the first-party image digests, the chart manifest digest, the chart package, both locks, and the `k3s-*` assets. | [Independent verification](#independent-verification). |
| `manifest.json`, the SBOM, vulnerability and license files, and `SHA256SUMS` with its bundle | The release evidence. | The verifiers that [release management](/contributor/release-management#operator-verification) describes. |
| `host-smoke-amd64.json`, `host-smoke-arm64.json` | The supported-host smoke records. | Operators who check the hosts that the release passed on. |

When a stable release promotes a qualified candidate, both image digests and the chart digest stay unchanged.
The joint lock keeps the candidate's actual `chart.version` and package name.
Chart and image build metadata retain that candidate version.
The logical stable release identity comes from the signed stable lock, as [Verify the release chart](./kubernetes#verify-the-release-chart) describes.

## Independent verification

The certificate identity belongs to the release, not necessarily the current repository.
A lock signed before the `ls1intum` → `hephaestus-build` transfer permanently names the old repository in its Fulcio certificate.
`security/release-identities.json` in the repository maps each version to its signing identity and image namespace.
The verifier and every workflow use that map.

1. For releases before that map's `hephaestus-build` boundary, substitute `https://github.com/ls1intum/Hephaestus/.github/workflows/release.yml@refs/heads/main` below.
2. For those releases, substitute `--owner ls1intum` in the attestation check.
3. Run the verification commands:

   ```bash
   VERSION=vX.Y.Z
   gh release download "$VERSION" --repo hephaestus-build/Hephaestus \
     --pattern "release-$VERSION.json" \
     --pattern "release-$VERSION.json.sigstore.json"
   cosign verify-blob \
     --bundle "release-$VERSION.json.sigstore.json" \
     --certificate-identity \
       'https://github.com/hephaestus-build/Hephaestus/.github/workflows/release.yml@refs/heads/main' \
     --certificate-oidc-issuer 'https://token.actions.githubusercontent.com' \
     "release-$VERSION.json"
   gh attestation verify "release-$VERSION.json" --owner hephaestus-build
   ```

4. To check the release attestation without GitHub's attestation store, use the bundle that ships with the release:

   ```bash
   gh release download "$VERSION" --repo hephaestus-build/Hephaestus \
     --pattern "release-$VERSION.intoto.jsonl"
   gh attestation verify "release-$VERSION.json" \
     --bundle "release-$VERSION.intoto.jsonl" \
     --predicate-type https://in-toto.io/attestation/release/v0.1 \
     --repo hephaestus-build/Hephaestus
   ```

   Releases before this asset existed have no such file.

## Rollback

Follow the [rollback procedure](./upgrade#rollback). It selects an earlier published lock without
reconstructing metadata or resolving image tags. Production startup independently rejects a
non-digest `agent-pi` reference.

## Runtime identity handoff

Compose hands every server, worker and webhook container three lines of `release-lock.env`:
`IMAGE_TAG` (as `APP_VERSION`), `HEPHAESTUS_RELEASE_COMMIT` and `HEPHAESTUS_IMAGE_APPLICATION_SERVER`.
Each process reports them, with the runtime roles it booted, under `release` in `/actuator/info`.
`DEPLOYMENT_ENVIRONMENT` from `.env` adds where the process runs.
This is deployment metadata from the verified lock, not an observation of the container and not a
second signature verification. A process without a lock reports the version it was given and no
commit or image. If a value is present but malformed, the process refuses startup.

`/actuator/info` is unauthenticated, like the health probes beside it.
The version was always visible in the web application.
The published lock contains the commit and digest.
The role list shows the container topology that this guide documents.

After an upgrade, compare `release` on **each** role's management listener. The administration
overview shows only the server it talks to. The instance-admin API adds the update check to the same
identity: `GET /admin/release` reads it and `POST /admin/release/checks` asks GitHub now. See
[Update checks](./upgrade#update-checks) for cadence, privacy and offline operation.
