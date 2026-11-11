# ADR 0055: Practice scripts use one result contract

**Status:** Accepted
**Date:** 2026-10-14
**Authors:** Hephaestus maintainers

## Context

Some practices can decide from exact captured records.
Others need model judgment within the same evidence boundary.
Both must reach one admission path before they can support feedback.

## Decision drivers

- A script failure never becomes a claim about the work.
- A script that a workspace admin wrote holds no credential.
- Every candidate passes the same admission as the built-in review.

## Considered options

1. **A positional function that returns leads only.** Rejected. The server then has to turn leads into results, so a second outcome path and a second admission path appear.
2. **Fall back to the built-in script when a custom script is invalid.** Rejected. The admin who wrote the script never sees the failure, and the result claims a method that did not run.
3. **Give the child process its own model credential.** Rejected. A script could read and send the credential, which breaks the third control of ADR 0036.
4. **One exported definition, one candidate result, and models only through the host.** Chosen.

## Decision

Use option 4.
Each assigned practice runs one custom script or the built-in script.
A custom script exports definePracticeScript with meta and run(ctx).
The script returns one candidate result for its assigned item.
A null script uses the built-in script. An invalid script never falls back.

The host owns identity, one job credential, deadline, governed model requests and trusted read receipts.
Custom child processes have no credentials and use typed host IPC.
Model access follows declared slots and the developer's AI ceiling.
The frozen method retains all bound slots and epoch independently of job retention.

Candidate admission is atomic. One refused citation or child withholds the complete candidate.
Problems own severity. The server caches the exact worst severity on the result.
A Not met result can keep open questions beside its proven problems.
The [runner guide](../contributor/practice-script-runner.mdx) owns the strict version 3 contract.

[ADR 0056](0056-one-practice-model-for-items-and-scans.md) owns durable model relationships.

## Consequences

A script failure creates no claim about the work.
[ADR 0056](0056-one-practice-model-for-items-and-scans.md) § Consequences owns the upgrade.

## Confirmation

`docker/agents/practice-scripts/lib/core/program-host.test.ts` fails if a script inherits the host environment, or if an invalid program or a stored script with metadata runs.
`docker/agents/practice-scripts/lib/core/contract.test.ts` checks the strict candidate contract.
Review catches a fallback from a custom script to the built-in script.

## Revisit trigger

Revisit when a shipped script requires a capability that its domain kit and governed host cannot express.

## Update — 2026-11-10: public scripts declare no models

This update supersedes "Model access follows declared slots" in the decision above for stored scripts.
A stored script keeps `meta` empty, prepares evidence, and calls `ctx.review`.
The host refuses a stored script with any metadata field as an invalid program before it binds a model.
The public toolkit has no direct-model entry, so a stored script makes no direct model call.
Review sessions follow the developer's AI ceiling.
Declared slots remain only for the toolkit's private test programs.
