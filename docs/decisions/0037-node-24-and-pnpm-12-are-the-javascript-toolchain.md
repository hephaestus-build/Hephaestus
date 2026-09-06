# ADR 0037: Node.js 24 and pnpm 12 are the JavaScript toolchain

**Status:** Accepted (amended 2026-09-03 — see the update below)
**Date:** 2026-08-30
**Supersedes:** [ADR 0033](0033-bun-is-the-javascript-runtime-and-package-manager.md)
**Builds on:** [ADR 0036](0036-agent-runtime-runs-on-node-24.md), which moved the sandbox runtime first

## Context

Repository tooling already executes on Node.js 24, while dependency installation and script dispatch
used a second runtime. That split duplicated version setup, cache policy, commands, lockfile policy, and
contributor knowledge. The old lockfile cannot be imported by pnpm, so the change must re-resolve the
workspace atomically with the manifest pin.

## Decision

Use exactly Node.js 24.19.0 and pnpm 12.0.0. `packageManager` and `devEngines` are authoritative and
fail on drift. `pnpm-workspace.yaml` owns workspace membership, overrides, patches, the isolated linker,
public hoisting needed for shared JSX types, and the lifecycle allowlist. Only `@swc/core` and `esbuild`
may build.

Set `minimumReleaseAge: 4320` (three days). This native install policy replaces the bespoke lockfile
qualification workflow. CI uses `pnpm/setup` to install pnpm's self-contained binary and the pinned
Node.js runtime, without Corepack, and caches pnpm's store. Docker builds fetch into a cache-mounted
store and install offline from the frozen lockfile.
Repository policy rejects every active artifact, command, API, or setup action for the retired tool;
historical ADRs and changelog entries remain unchanged.

Do not suppress peer dependency warnings globally. Compatibility exceptions require a narrow,
reviewed rule; otherwise `pnpm peers check` keeps mismatches visible.

## Consequences

The repository has one JavaScript runtime, one package manager, one lockfile, and one command surface.
Fresh dependency resolution is reviewed once in this migration. Installs reject packages younger than
three days and reject unapproved lifecycle builds. Because pnpm 11 uses the same lockfile format, the
rollback line is an exact pnpm 11 pin rather than a partial toolchain rollback.

## Sources

- [pnpm workspace configuration](https://pnpm.io/settings)
- [pnpm 12 standalone installation](https://pnpm.io/installation#pnpm-12-using-a-standalone-script)
- [`pnpm fetch` for container builds](https://pnpm.io/cli/fetch)
- [`pnpm/setup` self-contained installer and cache contract](https://github.com/pnpm/setup/tree/v2.1.0)
- [pnpm 12 compatibility discussion](https://github.com/orgs/pnpm/discussions/11292)
- [Corepack distribution ends before Node.js 25](https://github.com/nodejs/corepack#default-installs)
- [Node.js release schedule](https://github.com/nodejs/Release#release-schedule)

## Update — 2026-09-03 (ADR 0040)

[ADR 0040](0040-vite-plus-is-the-command-surface.md) supersedes the "one command surface" of
§ Consequences: Vite+ (`vp`) is the command surface, and pnpm stays the package manager behind
`vp install`. The runtime pin, lockfile and install policy decided here are unchanged.
