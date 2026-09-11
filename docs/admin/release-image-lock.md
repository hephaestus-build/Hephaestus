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

Compose hands each server, worker and webhook container three lines of `release-lock.env`:
`IMAGE_TAG` (as `APP_VERSION`), `HEPHAESTUS_RELEASE_COMMIT` and `HEPHAESTUS_IMAGE_APPLICATION_SERVER`.
Every process reports them, with its enabled runtime roles, under `release` in `/actuator/info`. This
is operator-supplied deployment metadata from the verified lock, not an observation of the container
and not a second signature verification; a process without a lock reports the version it was given
and no commit or image. A value that is present but malformed refuses startup.

Compare `release` on **each** role's management listener after an upgrade — the administration
overview shows only the server it talks to. The instance-admin API adds update-check state to the
same identity: `GET /admin/release` reads it and `POST /admin/release/checks` asks GitHub now. See
[Update checks](./install#update-checks) for caching, privacy and offline operation.
