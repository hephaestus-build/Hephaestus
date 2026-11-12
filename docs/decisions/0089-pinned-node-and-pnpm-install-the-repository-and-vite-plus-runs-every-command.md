# ADR 0089: Pinned Node.js and pnpm install the repository, and Vite+ runs every command

**Status:** Accepted
**Date:** 2026-11-10
**Authors:** Felix T.J. Dietrich

**Supersedes:** [ADR 0037](0037-node-24-and-pnpm-12-are-the-javascript-toolchain.md), [ADR 0040](0040-vite-plus-is-the-command-surface.md)

**Relates to:** [ADR 0043](0043-gradle-java-build.md), [ADR 0072](0072-few-dependencies-owned-slivers-kept-foundations.md)

## Context

ADR 0037 chose the runtime, the package manager and the install policy. ADR 0040 made Vite+ (`vp`) the command surface on top of it.
Together they carried four updates. ADR 0037 named versions that went stale and a `pnpm peers check` that no task runs.
ADR 0040 changed its hook mechanism twice.

This ADR states the toolchain at 1.0 as rules. [Local verification](../contributor/local-verification.mdx) owns the commands.

## Decision drivers

- Every machine and every CI job resolves the same runtime, package manager and lockfile.
- A new or compromised package cannot reach an install before review.
- One vocabulary for commands in tasks, hooks, workflows, documents and agent instructions.
- Each version has one home, so a pin bump does not edit prose.

## Considered options

1. **`pnpm run` as the front door, `vp` inside the scripts.** Two vocabularies for one thing drift apart.
2. **`vp` as the front door, and Vite+ also provisions Node.js and pnpm in CI.** CI loses the provenance of the SHA-pinned setup actions.
3. **`vp` as the front door, pnpm as the package manager, and the repository pins the runtime and the JDK (chosen).**

## Decision

1. **`package.json` is the one home of the Node.js and pnpm versions.** `devEngines` pins exact releases with `onFail: error`. `packageManager` and `engines.node` restate them. No ADR or document repeats a version.
   Enforced by: `gate:toolchain` (`scripts/check-toolchain.ts`). It also holds the Node.js major line. `gate:agent-runtime-pins` holds the agent image's Node.js pin equal.
2. **The repository pins, and the machine provides.** A contributor provides the pinned Node.js through Vite+'s managed mode or another version manager. CI reads both pins from `package.json`, installs pnpm with `pnpm/setup`, and selects Node.js with `actions/setup-node`.
   Enforced by: `gate:toolchain` (the `setup-toolchain` checks) and the CI job "Clean install".
3. **pnpm is the only package manager.** `pnpm-lock.yaml` is the only lockfile.
   Enforced by: `gate:toolchain`, which rejects `package-lock.json`, `npm-shrinkwrap.json` and `yarn.lock`.
4. **`pnpm-workspace.yaml` holds the install policy.** It sets a minimum release age and fails when a release time is missing. It allows exotic sources only for direct dependencies. When lifecycle scripts run, `strictDepBuilds` fails a dependency build that `allowBuilds` does not list. Each agent image's workspace file restates the policy.
   Enforced by: `gate:toolchain` (the policy settings, the `allowBuilds` map and the agent image files).
5. **CI verifies every locked entry.** CI installs with `--frozen-lockfile --ignore-scripts --no-trust-lockfile` and the trust policy `no-downgrade`. A local install trusts the lockfile and checks only new resolutions.
   Enforced by: `gate:toolchain`.
6. **An image build installs with pnpm, not `vp`.** It uses a digest-pinned pnpm image and installs from the frozen lockfile without lifecycle scripts.
   Enforced by: `gate:toolchain` (the install lines of `webapp/Dockerfile` and `docker/agents/pi/Dockerfile`). Review catches a `vp` call.
7. **`vp` is the only command surface.** `vite.config.ts` holds every command. The root `package.json` keeps only `prepare`. No tracked file outside the image builds, vendored examples and the history runs `pnpm run`, `pnpm exec` or `pnpm --filter`.
   Enforced by: `gate:toolchain`.
8. **The task graph relies only on proven facts.** `gate:runner-contract` proves them against the pinned Vite+. The catalog pins each tool that Vite+ bundles at its bundled version. `vp run sync:toolchain-pins` writes them.
   Enforced by: `gate:runner-contract` (`scripts/check-runner-contract.ts`) and `gate:toolchain`.
9. **Git hooks run from the tracked `.vite-hooks`.** `prepare` points `core.hooksPath` at it. Each hook starts through `.vite-hooks/run`, which refuses without an install. Husky and `vp staged` are not used.
   Enforced by: `scripts/enable-hooks.test.ts`, `gate:toolchain` (no Husky, hook mode `100755`) and the hooks-path step of "Clean install". `vp staged` is not enforced. Review catches it.
10. **`.java-version` pins the JDK line.** Gradle reads it, and no workflow pins a JDK inline. [ADR 0043](0043-gradle-java-build.md) owns the Java build.
   Enforced by: `gate:toolchain`.
11. **Bun is not used.** No tracked file outside the history names it.
   Enforced by: `gate:toolchain`.
12. **Dependencies stay few,** as [ADR 0072](0072-few-dependencies-owned-slivers-kept-foundations.md) decides. Enforced by: `gate:dependency-baseline`.

## What changed and why

- **Versions leave the ADR.** ADR 0037 named versions that went stale. Rule 1 points to `package.json`.
- **The build allowlist is a map.** ADR 0037 allowed two builds. `allowBuilds` now also denies some builds by name, and `strictDepBuilds` fails the rest. pnpm replaced `onlyBuiltDependencies` with `allowBuilds`.
- **The install policy is stricter than pnpm's defaults.** pnpm skips the age check when a release time is missing. Rule 4 fails instead. Rule 5 adds the trust policy, which pnpm leaves off.
- **`pnpm peers check` goes.** No task runs it. The typecheck, build and test gates decide compatibility.
- **Hooks run from the tracked directory.** A generated dispatcher existed only after an install, and Git then silently ran no hook.

## Consequences

- Good: one runtime, one package manager, one lockfile and one command vocabulary. A gate fails on the first line that drifts.
- Good: a pin bump changes `package.json` and the lockfile only.
- Bad: a release younger than the minimum age waits. An urgent fix needs a reviewed exclusion.
- Bad: Vite+ is a dependency before 1.0. A bump can change the task or hook contract. The runner contract catches only the facts that it lists.
- Bad: `gate:toolchain` restates the `allowBuilds` map, so a new build edits two files.
- Bad: a contributor installs the `vp` launcher with `curl | bash`. The lockfile pins the `vite-plus` that it runs.

## Confirmation

Each rule names its gate. `gate:toolchain` runs in `vp run check` and in the CI job "Clean install". `scripts/enable-hooks.test.ts` runs in `test:tooling`, which `vp run check` includes.

## Revisit trigger

- Vite+ provisions Node.js and pnpm from the repository pins as verifiably as the SHA-pinned setup actions.
- A Vite+ release changes the install, task or hook contract in a way that the gates cannot express.
- A pnpm release changes a default that rule 4 or rule 5 sets.

## Sources

- pnpm, [Dependency resolution settings](https://pnpm.io/settings/dependency-resolution) and [Build settings](https://pnpm.io/settings/build). The age check is skipped by default when a release time is missing, and `trustPolicy` defaults to off. Rules 4 and 5 set both explicitly.
- npm, [`package.json` § devEngines](https://docs.npmjs.com/cli/v11/configuring-npm/package-json#devengines). `onFail` "can be `warn`, `error`, or `ignore`". Rule 1 uses `error` so that drift fails.
- pnpm, [`package.json`](https://pnpm.io/package_json). Its examples set `onFail: download`, and `pnpm install` saves the exact runtime in the lockfile. Rule 2 keeps `error`, so the machine provides the runtime.

## Already priced and declined

- [ADR 0033](0033-bun-is-the-javascript-runtime-and-package-manager.md#decision): Bun as the runtime and the package manager.
- [ADR 0037](0037-node-24-and-pnpm-12-are-the-javascript-toolchain.md#decision): Corepack, the bespoke lockfile qualification workflow, and a global suppression of peer warnings.
- [ADR 0040](0040-vite-plus-is-the-command-surface.md#considered-options): `pnpm run` as the front door, and Vite+ provisioning Node.js and pnpm in CI.
- [ADR 0040](0040-vite-plus-is-the-command-surface.md#decision): `vp staged` for the pre-push gate.
- [ADR 0040](0040-vite-plus-is-the-command-surface.md#update--2026-11-04-hooks-run-from-the-tracked-directory): the generated `.vite-hooks/_` dispatcher.
