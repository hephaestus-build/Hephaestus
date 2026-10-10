# ADR 0036: Agent runtime runs on Node.js 24 with bounded resources

## Status

Accepted (amended 2026-09-17 — the image installs the SDK with pnpm; 2026-10-09 — each precompute
script runs in its own process without the child-process grant). Supersedes
[ADR 0030](0030-agent-runtime-is-typescript-on-bun.md) for the sandbox image. Repository tooling is
governed by [ADR 0037](0037-node-24-and-pnpm-12-are-the-javascript-toolchain.md), which superseded
[ADR 0033](0033-bun-is-the-javascript-runtime-and-package-manager.md).

## Context

ADR 0030 coupled TypeScript checking against the Pi SDK with execution on Bun. Type checking is
runtime-independent, while Bun removed the V8 heap ceiling and in-process out-of-memory diagnostic.
Its native-dependency rationale is obsolete because the Pi SDK no longer depends on `koffi`.

The runner tree executes on Node 24 with native type stripping. Precompute requires a mechanical port
from Bun APIs to Node's standard library.

The runtime must preserve four independent controls:

- V8 heap growth is bounded below the container memory limit.
- Mentor and practice runners can read staged inputs and the SDK but write only sessions and output.
- Application-managed precompute extensions receive no job or proxy credentials.
- Server and image drift is detected and reported before sandbox work.

## Decision

The digest-pinned agent image contains Node 24 and no Bun or package-manager CLI. npm installs the Pi
SDK with lifecycle scripts disabled and is removed with Corepack and its shims. The image build checks
the SDK import, direct filesystem denial, child-process denial, and Bun absence.

Mentor and practice runners use `--max-old-space-size=256` and Node permissions. They may read
`/workspace` and `/opt/pi-sdk`, and write only session state and collected output. The mentor also uses
`--expose-gc`. Repeated filesystem flags define distinct allow-list entries.

Precompute runs separately with a minimal environment, direct filesystem permissions, and
`--allow-child-process` for `grep`. Child programs are not confined by Node's filesystem permissions;
therefore the container's mounts, network policy, non-root user, and credential-free environment are
the effective controls for precompute. The mentor and practice runners receive no child-process grant.

Relative imports include `.ts`, staged workspaces declare `{"type":"module"}`, and TypeScript checking
uses NodeNext resolution with erasable syntax only. Infrastructure suites run with `node:test`.
The server/image runtime contract advances to v2 and records the Node and Pi versions in OCI labels.

## Consequences

V8 old-space exhaustion produces a JavaScript runtime diagnostic instead of relying on the container
limit as the first heap boundary. Mentor and practice writes are restricted in process. The server and
agent image must be upgraded together. Bun remains the repository package manager and tooling runtime.

Node permissions are defense in depth, not a hostile-code sandbox. In particular, the precompute child
process grant makes Docker isolation and environment minimization load-bearing controls.

## Sources

- [Node.js 24 command-line options](https://nodejs.org/docs/latest-v24.x/api/cli.html)
- [Node.js 24 permission model](https://nodejs.org/docs/latest-v24.x/api/permissions.html)
- [Node.js 24 TypeScript execution](https://nodejs.org/docs/latest-v24.x/api/typescript.html)
- [Node.js 24 test runner](https://nodejs.org/docs/latest-v24.x/api/test.html)
- [Official Node Docker image](https://github.com/nodejs/docker-node/blob/main/README.md)
- [npm `ignore-scripts` configuration](https://docs.npmjs.com/cli/v11/using-npm/config#ignore-scripts)
- [Docker resource constraints](https://docs.docker.com/engine/containers/resource_constraints/)

## Update — 2026-09-17: the image installs the SDK with pnpm

Supersedes the install sentence of § Decision; § Status carries what supersedes the last sentence
of § Consequences. `docker/agents/pi/Dockerfile` installs the Pi SDK in a `ghcr.io/pnpm/pnpm` build
stage with `pnpm install --prod --frozen-lockfile --ignore-scripts` from `docker/agents/pi/package.json`,
`pnpm-lock.yaml` and `pnpm-workspace.yaml`, then copies `/opt/pi-sdk` into the `node:<NODE_VERSION>-slim`
runtime stage, which removes npm, npx, Corepack, Yarn and pnpm and fails the build if any of them
survives on the filesystem; the shipped image carries Node and no package manager. The other build
checks, the runner flags and the runtime-contract label (`SandboxLayout.RUNTIME_CONTRACT_VERSION = 2`)
are as decided.

## Update — 2026-10-09: each precompute script runs in its own process

Supersedes the precompute paragraph of § Decision and the precompute sentence of § Consequences.

A precompute script could start a program through the stage's `--allow-child-process` grant. Node's
filesystem grants do not confine that program, so a script could read the container environment and
the review's job token in it. Thus, the third control of § Context did not hold for a script that a
workspace admin wrote.

The precompute runner keeps the grant and starts one Node process for each script. That process has
`--permission`, no child-process grant, no write grant, an empty environment, a bounded heap and its
own deadline. The library's `grep` sends a search to the runner, which starts `grep` with a fixed
argument list inside the same read grants. The runner holds the precompute credential of
[ADR 0054](0054-precompute-calls-models-through-the-runner.md), and a script's process holds no
credential. Node permissions stay defense in depth, and Docker isolation stays the outer boundary.

[Workspace ABI § Precompute validation and limits](../contributor/agent/workspace-abi.mdx#precompute-validation-and-limits)
owns the current grants and limits.
[What a script can reach](../contributor/practice-precompute.mdx#what-a-script-can-reach) owns the
threat model.
