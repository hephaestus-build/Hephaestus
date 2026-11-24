# ADR 0146: Pi is pinned to one exact version, upgrades pass the runtime contract tests, and Pi Durable stays declined

**Status:** Accepted
**Date:** 2026-11-24
**Authors:** Felix T.J. Dietrich

**Builds on:** [ADR 0145](0145-pi-runs-every-agent-loop-behind-the-pi-runtime-interface.md) (Pi runs every agent loop), [ADR 0089](0089-pinned-node-and-pnpm-install-the-repository-and-vite-plus-runs-every-command.md) (pinned installs), [ADR 0072](0072-few-dependencies-owned-slivers-kept-foundations.md) (owned slivers)

**Relates to:** [ADR 0140](0140-privacy-facts-go-to-a-journal-and-a-restore-reapplies-them.md) (database restore)

## Context

Every agent loop runs on Pi ([ADR 0145](0145-pi-runs-every-agent-loop-behind-the-pi-runtime-interface.md)).
A Pi change can change every review and every turn.

**The current pin.** Main runs Pi 1.6.0 since #4859.
`@earendil-works/pi-ai` and `@earendil-works/pi-coding-agent` are pinned to 1.6.0 in `docker/agents/pi/package.json` and in the repository's top-level `package.json`.
The image's Dockerfile pins `PI_VERSION`.
The pin check `gate:agent-runtime-pins` (`scripts/check-agent-runtime-pins.ts`, extended in #4860) fails when these pins differ.

Main carries one owned patch, `docker/agents/pi/patches/@earendil-works__pi-ai@1.6.0.patch`.
It adds provider failure facts and validated token usage. Pi 1.6.0 contains neither change.

`pnpm-workspace.yaml` installs a version only three days after its release (`minimumReleaseAge: 4320`).

Pi also offers Pi Durable, which persists the execution state of a run so that the run can resume after a crash.
On main, a lost attempt restarts from the server's rows.

## Decision drivers

- One exact Pi version in the repository, the image and the lock.
- An upgrade is mechanical and tested, not a rewrite.
- PostgreSQL stays the one store of truth ([ADR 0079](0079-one-agent-system.md)).
- A later need for Durable must not start from zero.

## Considered options

1. **Version ranges for Pi.** An install can pick a version that the image does not have, and a run changes without a commit.
2. **Fork Pi.** Every upgrade becomes a merge, and the fork drifts from upstream fixes.
3. **Adopt Pi Durable for turns now.** A turn commits all or nothing, so a lost turn repeats nothing (ADR 0120, proposed in #4862). Durable does not yet host the whole extension set.
4. **Pi Durable as the system of record.** A second store of truth, with Pi's schema migrations in Java.
5. **Decline Durable and design nothing.** A later need starts from zero.
6. **An exact pin and a stated cadence, with Durable declined (chosen).** The owned patch stays until upstream takes it. Durable keeps an extension point.

## Decision

Option 6.

1. **Pi is pinned to one exact version** in the image's `package.json`, the top-level `package.json`, the lock and the Dockerfile. Today that version is 1.6.0. `gate:agent-runtime-pins` holds them equal.
2. **Every upgrade passes the runtime contract tests,** on Pi and on the minimal second loop.
3. **Cadence.**
   - A minor version follows within 14 days after the release-age gate admits it.
   - A patch ships on need or for an advisory.
   - A major version reopens this ADR.
   - No Pi upgrade lands in the week before a release cut.
4. **Hooks over forks.** Main changes Pi only through public APIs and extensions. The one owned patch stays until upstream takes the diagnostics. Each upgrade rebases it.
5. **Proposing the patch upstream is a public step** that the maintainer approves first.
6. **Pi Durable stays declined for 1.0.**
   - A checkpoint would be a cache of one run. Discarding it is always safe.
   - Its commit would be fenced on the lease epoch, and a resume would be a claim.
   - A database restore would drop every checkpoint.
7. **What ships now.** Idempotency keys leave out the epoch. Each tool has a replay class: `SAFE`, `KEYED` or `UNSAFE`. Metrics count lost attempts by run kind and cause.

### What this changes in other ADRs

No ADR changes. This ADR states the Pi part of the pin rules of [ADR 0089](0089-pinned-node-and-pnpm-install-the-repository-and-vite-plus-runs-every-command.md).

## Consequences

- Good: the repository, the lock and the image always run the same Pi.
- Good: an upgrade is a tested, routine pull request.
- Good: the lost-attempt metrics give the Durable decision numbers.
- Bad: the owned patch must be rebased on every upgrade until upstream takes it.
- Bad: a crash loses the work of the attempt in progress, which repeats from the server's rows.
- Bad: an advisory in Pi waits for the three-day release-age gate, unless a maintainer overrides it.

## Confirmation

- `gate:agent-runtime-pins` fails when the Pi pins in the image, the top-level `package.json`, the lock or the Dockerfile differ. It runs in `vp run check`.
- `pi-provider-usage.spec.ts` (`vp run test:agents`) fails when the patched adapters stop recording usage and failure facts.
- The cadence and the week before a cut are not enforced. Review of the upgrade pull request catches them.

## Revisit trigger

- Lost turn epochs exceed 1 % of attempts, or lost review spend exceeds 3 %. Pi Durable must also host the whole extension set.
- Upstream takes the diagnostics. Then the owned patch goes.

## Sources

- pnpm, [Settings: minimumReleaseAge](https://pnpm.io/settings#minimumreleaseage). A version installs only after it has been published for the set time. This gives the three-day gate of decision 3.
- pnpm, [Settings: patchedDependencies](https://pnpm.io/settings#patcheddependencies). A patch applies to one exact version, so an upgrade must rebase it. This gives decision 4.
