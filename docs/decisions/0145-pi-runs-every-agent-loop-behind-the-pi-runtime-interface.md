# ADR 0145: Pi runs every agent loop behind the Pi runtime interface

**Status:** Accepted
**Date:** 2026-11-24
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0079](0079-one-agent-system.md) § Update — 2026-11-10: the JVM composer is gone

**Builds on:** [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) (sandboxes reach only the server), [ADR 0079](0079-one-agent-system.md) (one sandbox for each subject, the gateway)

**Relates to:** ADR 0120 (proposed in #4862, steering and cancel), ADR 0121 (proposed in #4862, the composer's tools), [ADR 0146](0146-pi-is-pinned-and-upgraded-through-contract-tests-and-pi-durable-stays-declined.md) (Pi pinning)

## Context

Main runs reviews and conversation turns on Pi in the sandbox ([Unified Pi runtime](../contributor/unified-pi-runtime.mdx)).
Two other model loops remain.
The product feedback draft runs on `ModelSession` in the JVM.
The composer of `REVIEW_COMPOSE` has its own model client.
Each loop has its own retry rules, its own usage count and its own upgrade path.

The conversation agent and the composer need more from the loop. They need steering at the tool-result boundary, cancel through the LLM proxy, and tools chosen by place kind.
Three loops would each need these changes.

The real constraint is that the server keeps every permission, whatever loop runs.

## Decision drivers

- One provider layer and one upgrade surface.
- The server sees and decides the tool set of every run.
- A confused loop cannot cause an effect.
- An exit from Pi costs days, not a rewrite.

## Considered options

1. **Keep three loops.** Each change to steering, cancel or tools lands three times. Retry and usage rules differ between loops.
2. **Build our own loop.** A loop of a few hundred lines is possible. Compaction, provider adapters and tool streaming then become our code to keep.
3. **Another agent framework.** It replaces Pi's hooks with another framework's, and main already runs Pi.
4. **Pi with provider keys in the sandbox.** A prompt injection could then spend on any model, and the proxy no longer counts every call.
5. **Tool lists for each run kind in TypeScript.** The server cannot see or check the tool set of a run.
6. **Pi for every loop, behind a runtime interface that the server owns (chosen).**

## Decision

Option 6.

1. **Pi runs every agent loop** through one hermetic session factory in the sandbox.
   - Every discovery switch is off: no context files, skills, prompt templates or themes.
   - The shell environment holds only `PATH`, `HOME`, `LANG` and `TZ`.
   - No provider credentials enter the sandbox. Every model call goes through the server's LLM proxy.
2. **`ModelSession` and the composer's own model client go.** The product feedback draft becomes a report-draft run. Its cold start of up to 6 seconds is accepted, because the draft is asynchronous.
3. **The server keeps every permission.** A run descriptor gives each session its model, its prompt and its tools, each with a digest. It also gives the limits and the compaction mode. A descriptor that does not parse, or names an unknown tool, ends the run with no model call.
4. **Tools follow the place kind.**
   - A turn on a private thread keeps main's local tools: `read`, the shell tool and `load_context`.
   - A turn on a shared thread has server tools only, chosen by place kind. The composer's tools are in ADR 0121 (proposed in #4862).
   - A turn on a Slack channel thread has the one posting tool of ADR 0123 (proposed in #4862).
5. **Every server tool goes through one gateway route.** The route checks the descriptor, the lease, the authorization recheck and the contact policy again.
6. **Steering and cancel use the server.** After each tool batch, the runner asks the gateway for steering messages (ADR 0120, proposed in #4862). A cancel ends the call in flight through the LLM proxy.
7. **Pi is the only retry owner.** The proxy never repeats a model call whose response began.
8. **No sub-agents inside Pi.** Delegation is a new run, with its own lease, payer and audience.
9. **The Pi runtime interface is the run descriptor, the gateway tool route and the session event log.** Pi implements the loop and compaction behind it.
10. **The runtime contract tests also run against a minimal second loop** of a few hundred lines.
11. **Every attempt records its runtime fingerprint.** It holds the Pi version, the harness digest, the extension set and the digests of the prompt and the tools.

### What this changes in other ADRs

- [ADR 0079](0079-one-agent-system.md) § Update — 2026-11-10 says that `ModelSession` stays for product feedback drafting. `ModelSession` now goes, and a report-draft run takes its place.

## Consequences

- Good: one loop, one retry owner and one usage count for every model call.
- Good: the server sees every tool of every run, and a confused loop cannot widen it.
- Good: the second loop proves that an exit from Pi is possible.
- Bad: a product feedback draft waits for a sandbox, up to 6 seconds cold.
- Bad: the minimal second loop is code that only tests use.
- Bad: Pi's own features, such as sub-agents and discovery, stay unused.

## Confirmation

Not enforced. The implementing issues add the checks below, and review of each implementing pull request catches a missing one. They are:

- An architecture test fails when a class other than the session factory calls `createAgentSession`.
- An architecture test fails when a class other than `RunToolController` serves `/tools/`.
- An architecture test fails when a descriptor that is not for a work place lists a publication tool.
- The runtime contract tests fail on Pi or on the second loop when a run behaves differently through the interface.
- A test fails when a descriptor with an unknown tool reaches a model call.

## Revisit trigger

- A Pi upgrade fails the runtime contract tests twice in a row.
- A run kind needs a loop feature that Pi exposes only through private APIs.

## Sources

- Pi, [earendil-works/pi](https://github.com/earendil-works/pi). The SDK exposes sessions, extensions and hooks as public APIs. This gives decisions 1 and 9.
- Anthropic, [Building effective agents](https://www.anthropic.com/engineering/building-effective-agents). An agent is a model that uses tools in a loop, and simple composable patterns usually beat frameworks. This gives the second loop of decision 10.
