# ADR 0040: Vite+ is the command surface; pnpm stays the package manager

**Status:** Accepted
**Date:** 2026-09-03
**Authors:** Felix T.J. Dietrich
**Amends:** [ADR 0037](0037-node-24-and-pnpm-12-are-the-javascript-toolchain.md)

## Context

ADR 0037 made Node.js 24 and pnpm 12 the JavaScript toolchain, and the Vite+ task graph was
adopted on top of it with `pnpm run` kept as the front door. The repository then had two command
surfaces for one toolchain: every task body said `vp …` while every documented command said
`pnpm run …`, and the two drifted apart. Vite+ also has opinions of its own — it loads the task
graph before it runs anything, it caches a task only when the task runs its own command, and its
shell is not a POSIX shell on every platform — that a repository built on it has to decide about.

## Decision drivers

- One vocabulary for commands in CI, hooks, documents and agent instructions alike.
- A task graph that caches and parallelises the local gate instead of chaining scripts.
- Supply-chain posture unchanged: exact pins, SHA-pinned actions, hermetic image builds.
- The repository, not a tool, pins the runtime and the JDK.

## Considered options

1. Keep `pnpm run` as the front door and `vp` inside the scripts. Two vocabularies for one thing;
   the drift this decision removes.
2. Make `vp` the front door and let Vite+ provision Node and pnpm in CI as well. Loses the
   provenance the SHA-pinned setup actions give a hosted run.
3. Make `vp` the front door; keep pnpm as the package manager and the runtime and JDK pinned by
   the repository.

## Decision

Option 3.

- **`vp` is the only command surface.** `vp install`, `vp run <task>`, `vp run --filter
  <package> <script>`, `vp exec <bin>` and `vp -C <package> <command>` invoke work in tasks, hooks,
  workflows, documents and agent instructions, alongside Vite+'s own commands — `fmt`, `lint`,
  `check`, `build`, `dev`, `hooks`, `env`, `cache`. The toolchain gate rejects the package-manager
  lane wherever a command is written.
- **`vite.config.ts` is the one home of every command.** `package.json` keeps only `prepare`,
  which pnpm runs. A cached task owns its command and is a pure function of tracked files; a task
  that spawns outside the tree, reads the environment, runs tests or writes is uncached. The facts
  the graph relies on are proven by a gate against the pinned Vite+ rather than assumed: among them
  that a cached task is given a filtered environment, so a cached command names what it reads.
- **pnpm stays the package manager behind `vp install`.** `packageManager`,
  `pnpm-workspace.yaml`, the lockfile and its install policy are unchanged. CI provisions Node and
  pnpm through the pinned setup actions and installs without lifecycle scripts; image builds install
  the same way and never see `vp`. The tools Vite+ bundles are pinned to the versions it bundles
  and move only with it.
- **The repository pins the runtime and the JDK.** `devEngines.runtime` pins Node; how a machine
  provides it, Vite+'s managed mode or another version manager, is the contributor's choice.
  `.java-version` pins the JDK line for CI and for the caches that depend on it.
- **Git hooks run through the Vite+ dispatcher.** `prepare` enables it at `.vite-hooks/`, clearing
  a `core.hooksPath` left by an earlier hook manager first, because `vp hooks enable` keeps the one
  a clone already has. `vp staged` is not adopted: the pre-push gate is the contract, and a staged run
  keeps a backup stash that clashes with a multi-worktree checkout.

## Consequences

One command surface, one place that orders and caches work, and a policy gate that fails on the
first line that drifts back. The launcher is installed by a script the installer serves over HTTPS
and does not checksum; what it runs is the exact `vite-plus` in the lockfile, which is what the
supply-chain posture rests on. A CI job that calls `vp` installs dependencies first, because the
task graph is loaded from every workspace config. The launcher is a system tool like `node` and
`git`: on the PATH of every process that runs repository scripts. Vite+ is a pre-1.0 dependency,
so the exact pin and the SHA-pinned action are the only versions in play, and the runner and lint
contracts are what make a bump safe to take.

## Revisit trigger

Vite+ provisions the package manager and runtime from repository pins with the same verifiability
as the pinned setup actions, or a Vite+ release changes the install, task or hook contract in a way
the gates cannot express.

## Sources

- [Vite+ guide: run](https://viteplus.dev/guide/run)
- [Vite+ guide: install and package managers](https://viteplus.dev/guide/install)
- [Vite+ guide: commit hooks](https://viteplus.dev/guide/commit-hooks)
- [Vite+ guide: managed Node.js](https://viteplus.dev/guide/env)
- [GitHub Actions: cache access restrictions](https://docs.github.com/en/actions/writing-workflows/choosing-what-your-workflow-does/caching-dependencies-to-speed-up-workflows#restrictions-for-accessing-a-cache)
- [actions/setup-java: `java-version-file`](https://github.com/actions/setup-java#supported-version-syntax)
- [Renovate npm manager: `pnpm-workspace.overrides`](https://docs.renovatebot.com/modules/manager/npm/)
