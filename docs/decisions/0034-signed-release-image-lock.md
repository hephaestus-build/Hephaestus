# ADR 0034: Production consumes one signed release image lock

**Status:** Accepted (amended 2026-09-03 #1719 and 2026-09-05 — see the updates below)
**Date:** 2026-08-28
**Supersedes:** [ADR 0031](0031-agent-image-follows-the-deployments-own-tag.md)

## Context

Release evidence binds each supported platform image to an immutable digest, but tags and separate
agent pinning did not guarantee that staging and production consumed the complete verified set.
Upstream pins also need one reviewed dependency source without resolving tags during deployment.

## Decision

Each release publishes one JSON lock derived from the verified evidence manifest. It identifies the
release and source commit and records, for every first-party and reviewed upstream production image,
the repository, provenance class, index digest, and supported platform child digests.

The release workflow signs the lock with its keyless GitHub Actions identity and publishes it on an
immutable GitHub Release. Staging, production, install, upgrade, and rollback verify that identity and
validate the lock against the evidence manifest before rendering Compose. Compose accepts only index
digest references emitted by the validator. The same lock supplies the dynamically launched agent
image. `security/release-images.json` remains the reviewed source for upstream dependency updates;
the release lock is derived output.

Deployment executes the Compose files and validator from the source commit named by the signed lock.
A release fails if its inventory differs from the blessed Compose topology plus the dynamic agent.
Rollback selects an earlier published lock and never rebuilds metadata or resolves a tag.

## Consequences

- Production and staging no longer deploy branch, channel, or version tags.
- The former release-pin init container and production pin-file import are removed.
- Operators need GitHub CLI, Cosign, and the repository-pinned Bun version to prepare self-hosted
  deployment inputs.
- Preview and local development remain non-release paths and may use build-specific tags.

## Revisit trigger

Revisit when Compose natively verifies signed image collections with equivalent signer, release,
platform, and exact-topology policy.

## Update — 2026-09-03 (issue #1719)

[ADR 0041](0041-compose-1x-kubernetes-2.md) extends § Decision to a second inventory; it supersedes
nothing above. Agent image contract v2 to protocol v3 is a single release-lock upgrade: the server,
worker, webhook, and agent images cannot be rolled independently across that boundary. The 1.x lock
covers Compose. The 2.0 lock adds the Helm chart digest and the pinned
Agent Sandbox CRD and controller image digests; the chart, CRD, and images used for install, upgrade,
and rollback all come from that lock. ADR 0034's signer identity, signature validation, immutable
publication, and no-rebuild rollback rules apply unchanged. Compose and the Docker driver are absent
from the 2.0 inventory rather than retained as exclusions.

## Update — 2026-09-05 (ADR 0037)

Supersedes the operator toolchain in § Consequences.
[ADR 0037](0037-node-24-and-pnpm-12-are-the-javascript-toolchain.md) replaced Bun with the Node.js
version the repository pins, and `prepare-release-lock.ts` reaches for the GitHub CLI only inside
GitHub Actions — an operator's run fetches over HTTP. Preparing self-hosted deployment inputs
therefore needs Cosign and that Node.js version, and no GitHub CLI.
