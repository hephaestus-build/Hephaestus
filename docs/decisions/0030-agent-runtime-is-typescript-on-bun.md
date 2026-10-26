# ADR 0030: The agent runtime is TypeScript on Bun, with no Node in the sandbox

**Status:** Superseded by [ADR 0036](0036-agent-runtime-runs-on-node-24.md)
**Date:** 2026-08-22
**Authors:** Felix T.J. Dietrich
**Builds on:** [ADR 0007](0007-sandbox-spi-shape.md) (the sandbox SPI the runner is staged into), [ADR 0026](0026-per-purpose-agent-bindings-and-llm-governance.md) (the mentor and practice-review purposes this runtime serves)

## Context

Two runners execute inside the agent image: the one-shot practice-review runner and the long-lived
mentor runner, plus the sidecars they share. Both were JavaScript — 3,192 lines of `.mjs` across
seven modules, with 1,264 more in five spec files. The precompute tree beside them
(`docker/agents/precompute/` and the per-practice scripts) was already TypeScript on Bun and already
type-checked. The runner was the tree that had neither property.

Three forces made that gap expensive:

1. **The runner compiled against no types at all.** No `allowJs`, no `checkJs`, no `// @ts-check`.
   The Pi SDK ships 140 `.d.ts` files (8,097 lines) describing the API the runner drives, and the
   runner saw none of them, because the SDK was installed into the image and was not a dependency of
   the repository. Every SDK option name was a guess no tool could check.
2. **Nothing linted or formatted it.** Biome was scoped to `webapp/`, and `.editorconfig`'s
   TypeScript rule matched `*.{ts,tsx,js,jsx}` — not `.mjs`. The runner sat outside every style gate
   the repository owns.
3. **The image carried two runtimes.** `node:24-slim` ran the runner while Bun, installed
   separately, ran precompute. Every agent image pull paid for both.

The cost of (1) was not theoretical. Type-checking the tree for the first time surfaced defects both
test suites had been green through, including a mentor that never received its own system prompt —
live in a released image — and a practice-review result path that could not succeed at all.

## Decision drivers

- **One runtime in the sandbox.** Two interpreters is an unforced supply-chain and size cost.
- **No build step.** The runner is staged into the sandbox as a classpath resource and executed in
  place; anything requiring compilation would put a build artifact between the source we review and
  the code that runs.
- **Types must come from the SDK, not from us.** A hand-rolled shape of someone else's API is a
  second source of truth that drifts silently.
- **Pins must survive the change.** Whatever replaces `node:24-slim` has to keep an arch-explicit
  hash on the Bun binary and keep Renovate able to bump it.

## Decision

The agent runtime is TypeScript, executed directly by Bun. `PiRuntimeFactory` invokes `bun`, and the
image contains no Node and no npm: the base is `debian:bookworm-slim`, the Pi SDK is installed with
`bun install` against a hand-written `package.json`, and the build fails if a Node artefact survives.

Type-checking, linting and formatting cover the runner and the precompute trees as one project:
`tsconfig.node.json` (strict, plus `noUncheckedIndexedAccess`) with the Pi SDK as a devDependency
pinned to the version the image installs, the root `.oxlintrc.json` for lint, and the root
`.oxfmtrc.json` for formatting.

## Consequences

**Positive.**

- The runner is checked against the SDK's own types. The defects named above were found by turning
  the check on, not by a test.
- One runtime in the image instead of two, on a smaller base: 866 MB to 657 MB.
- No npm and no lifecycle scripts at install time removes a build-time code-execution path.
- The runner and the precompute trees share one lint, format and type-check surface, so a
  contributor moving between them re-learns nothing.

**Negative / accepted.**

- **The mentor's heap ceiling is gone, and its replacement is sixteen times wider.** The mentor ran
  under `--max-old-space-size=256` specifically so a leaking session would exhaust its own 256 MB
  heap rather than the host's memory. Bun has no equivalent, so the only bound left is the
  container's: `SANDBOX_MEMORY_BYTES`, default 4 GiB, shared with tmpfs and configured with swap
  equal to memory. The failure mode changes with it — V8 heap exhaustion raised a JavaScript error
  the runner could observe and report; a cgroup limit delivers SIGKILL, so a leaking mentor now dies
  without a diagnostic and takes the in-flight turn with it. `--smol`, added here, and `--expose-gc`,
  retained, make collection more eager and let the runner compact after a turn above a 64 MB
  watermark, but neither is a bound. Nothing detects this today: mentor memory over a long session
  is unmeasured.
- **Bun accepts unknown CLI flags silently**, including flags that are not V8 flags at all — there
  is no error and no warning. `--max-old-space-size` would therefore have read as a working heap cap
  while doing nothing, and `--no-warnings` is inert. Any flag added to a `PiRunnerProfile` must be
  confirmed against `bun --help`; `PiRuntimeFactoryTest` asserts the flag list, which catches a
  removal but not a typo.
- **The jemalloc `LD_PRELOAD` and `MALLOC_CONF` tuning is retired**, because Bun allocates through
  mimalloc and the tuning no longer reaches the allocator that matters. It was applied as a
  command-line prefix rather than as container environment, so this is a deliberate removal, not
  something the sandbox environment blocklist forced.
- **`rootDirs` and `allowImportingTsExtensions` are load-bearing**, not conveniences: the runner
  imports siblings as `./x.ts` because that is what Bun resolves, and the precompute trees are two
  directories that a runtime symlink merges into one. Removing either breaks the type-check against
  code that runs correctly.

## How this is enforced

- The image build fails if `node`, `nodejs`, `npm`, `npx`, `corepack`, `yarn` or `pnpm` resolves on
  `$PATH` or exists under the standard bin directories.
- The build imports the SDK by bare specifier through a workspace-style symlink, exactly as
  `PiRuntimeFactory` arranges it at runtime, and asserts koffi's prebuilt for the target architecture
  is present — the one blocked lifecycle script whose absence would matter.
- Renovate tracks both pins: the Bun release, and the `@earendil-works/pi-coding-agent` version,
  which must equal the repository's devDependency for the type-check to describe the code that runs.

## Considered options

1. **JSDoc + `checkJs`, staying on Node.** Gives identical diagnostics against the same SDK types
   with no runtime change, and would have been the right call if types were the only goal. Rejected
   because it leaves two runtimes in the image and pays the annotation cost in comments rather than
   in syntax.
2. **`oven/bun` as the base image.** Rejected because it makes the OS base a function of Bun's
   release cadence: the image installs git, jq, curl and unzip regardless, and those would move
   whenever Bun's base moved. Pinning `oven/bun` by manifest digest would be an equally strong pin —
   the objection is cadence coupling, not pinning strength. `debian:bookworm-slim` is the same
   Debian 12 `node:24-slim` was built on, so subtracting Node introduced no glibc drift.
3. **`trustedDependencies` for the three blocked lifecycle scripts.** Rejected on evidence: an A/B of
   npm with scripts running against bun with scripts blocked produced byte-identical trees for
   `@google/genai`, `protobufjs` and `koffi`, so trusting them would grant build-time code execution
   for zero bytes. Two of the three shell out to `node`, which no longer exists; `@google/genai`'s is
   an `echo`.

## Revisit trigger

- A mentor session is OOM-killed, or long-session memory is measured and found to approach
  `SANDBOX_MEMORY_BYTES` — either turns the accepted regression into a real one, and the answer is a
  runner-side watermark that ends the session before the kernel does.
- Bun gains a per-process heap ceiling, at which point the 256 MB bound becomes expressible again
  and should be restored.
- The Pi SDK stops working under Bun, or starts requiring a lifecycle script that genuinely produces
  files.

## Contract locations

- [Pi agent workspace ABI](../contributor/agent/workspace-abi.mdx)
- `docker/agents/pi/Dockerfile`, `tsconfig.node.json`, `.oxlintrc.json`, `.oxfmtrc.json`
- `PiRuntimeFactory`, `PiRunnerProfile`, `MentorRunnerProfile`, `PracticeRunnerProfile`
