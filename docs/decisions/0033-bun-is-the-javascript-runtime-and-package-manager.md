# ADR 0033: Bun is the JavaScript runtime and package manager

## Status

Superseded by [ADR 0037](0037-node-24-and-pnpm-12-are-the-javascript-toolchain.md).

## Context

A second JavaScript runtime provides no required repository capability and would duplicate version,
installation, and security policy.

## Decision

Bun runs all repository JavaScript and TypeScript tooling and manages the root, webapp, and
documentation workspaces. `package.json#packageManager` is the authoritative version; CI verifies
the duplicated Docker pins against it.

Installs use Bun's text lockfile, isolated linking, frozen mode in automation, a release-age
quarantine, and an explicit lifecycle-script trust list. Non-registry dependency specifiers are
rejected.

Bun reports incompatible peer ranges but has no strict-peer failure mode. Typecheck, build, and test
gates are therefore the compatibility verdict. Installed-tree qualification checks nested override
versions because a stable lockfile alone does not prove a stable installed tree.

The configuration follows Bun's documented
[isolated installs](https://bun.com/docs/pm/isolated-installs),
[lifecycle trust](https://bun.com/docs/pm/lifecycle), and
[frozen lockfiles](https://bun.com/docs/pm/lockfile). CI pins third-party actions by commit SHA in
accordance with GitHub's
[secure-use guidance](https://docs.github.com/en/actions/security-for-github-actions/security-guides/security-hardening-for-github-actions#using-third-party-actions).

## Consequences

Runtime and dependency updates are one toolchain change. Bun compatibility is a release gate, and
peer compatibility is decided by typecheck, build, and test rather than installation alone.

## Rollback

Revert this decision and its migration commit together, restoring the previous manifest,
workspace configuration, lockfile, setup action, and commands. Then run the complete quality gate,
builds, tests, and clean-install qualification before merging the rollback.
