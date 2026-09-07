---
id: release-image-lock
title: Release image lock
description: Verify the image digests selected by a Hephaestus release.
---

# Release image lock

Each release publishes `release-vX.Y.Z.json` with its Sigstore bundle. The lock records the release,
source commit, provenance class, repository, multi-platform index digest, and supported platform child
digests for every deployed image. Compose uses the index digests; tags identify releases for humans.

Follow the [install guide](./install) to install or upgrade. Its verifier checks the signature, signer
identity, release identity, schema, and equality with the release evidence manifest before writing
`release-lock.env`.

## Independent verification

The certificate identity is the release's, not necessarily today's repository: a lock signed before
the `ls1intum` → `hephaestus-build` transfer names the old repository in its Fulcio certificate
forever. `security/release-identities.json` in the repository maps each version to its signing
identity and image namespace; the verifier and every workflow resolve it from there. For releases
before that map's `hephaestus-build` boundary, substitute
`https://github.com/ls1intum/Hephaestus/.github/workflows/release.yml@refs/heads/main` below and
`--owner ls1intum` in the attestation check.

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

## Rollback

Follow the [rollback procedure](./install#rollback). It selects an earlier published lock without
reconstructing metadata or resolving image tags. Production startup independently rejects a
non-digest `agent-pi` reference.

## Runtime identity handoff

`release-lock.env` includes `HEPHAESTUS_DEPLOYMENT_IDENTITY`: the release, source commit and image
index-digest references from the verified lock. Compose passes this projection to the server, worker
and webhook processes. It is operator-supplied deployment metadata, not a live observation of the
containers or a second signature verification.

Each process exposes its enabled roles and embedded build commit under `release` in `/actuator/info`.
When that commit matches the projection, it reports the lock's release version; a disagreement is
reported as a mismatch. Missing or invalid metadata is reported explicitly, not resolved from image
tags. The reported digest map describes the selected lock, not proof that every container has
restarted with those images.

The instance-admin API adds update-check state to this identity: `GET /admin/release` reads it and
`POST /admin/release/checks` requests a refresh. See [Running release and update checks](./install#running-release-and-update-checks)
for caching, offline operation and privacy, and [Upgrade procedure](./install#upgrade-procedure) for
post-deployment verification.
