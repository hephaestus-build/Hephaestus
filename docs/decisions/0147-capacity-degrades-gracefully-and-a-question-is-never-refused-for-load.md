# ADR 0147: Capacity degrades gracefully: the next free place goes to an interactive turn, and a question is never refused for load

**Status:** Accepted
**Date:** 2026-11-24
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0079](0079-one-agent-system.md) § Claim, priority and reap, and its revisit trigger on warm pools

**Builds on:** [ADR 0083](0083-each-purpose-uses-one-governed-model-and-every-call-is-counted.md) (one model for each purpose and tier, two purses), [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) (runs and leases)

**Relates to:**

- ADR 0120 (proposed in #4862, one live turn for each thread, required items)
- ADR 0122 (proposed in #4862, the copy deadline needs no sandbox)
- ADR 0126 (proposed in #4863, the payer of personal-space runs)
- [ADR 0116](0116-a-push-is-one-web-push-message-from-the-instance.md) (the push for an unread answer)

## Context

Hephaestus now runs background turns, composer turns and interactive turns on the same sandboxes.
`capacity.turns` bounds the conversation sandboxes.
If background turns fill every place, a person who asks a question waits behind them.

Main measured with 11 users at once: the first visible text took a p95 of 7.6 to 32.9 seconds.
The turn limit caused the wait, not the loop.
Until #4858, a warm sandbox also asked for its next run only every 2 seconds.

Instances differ widely.
One host with free local models has no money limit but little compute.
A cluster on a paid API has the two purses of ADR 0083 for each workspace.

The real constraint is that a waiting person gets the next place, with an honest wait, on every profile, without new knobs.

## Decision drivers

- A question is never refused for load.
- A person never waits in silence.
- Background work gives way to people. Agreed check-ins, requested results and copies on the work still meet their times.
- No new Helm value, CRD or Role in 1.0.
- A person never sees money.
- Load never changes the model that answers. The binding of ADR 0083 decides it.

## Considered options

1. **Refuse at the edge when full.** The person loses the moment, and clients retry in a storm.
2. **A separate concurrency limit for feedback.** One more Helm value and more sandboxes, for a split that one rule can make.
3. **A warm pool, or an always-on sandbox for each person.** An idle person then costs a Pod, not rows.
4. **Reserves, fair-share rules and payer pools in 1.0.** They need measurements that 1.0 does not have yet.
5. **Show queue position or a live estimate from shared load.** On a small instance either reveals that a colleague is asking.
6. **Fall back to the next ready model.** Admission would pick the first ready model of an ordered list. A person would then get an answer from a model that no admin bound to that purpose, against ADR 0083.
7. **One reserved place, an honest ladder, and existing limits (chosen).**

## Decision

Option 7.

1. **Capacity uses the existing run queue and provider limits.** Interactive work goes first.
2. **One reserved place.** A background turn is leased only while one place under `capacity.turns` stays free for an interactive turn. The check runs on every claim, warm subject claims included. The minimum of `capacity.turns` becomes 2.
3. **A circuit breaker on the LLM proxy** stops background claims while the model provider is down. Required items still get their dispositions with no model (ADR 0120, proposed in #4862).
4. **Sandbox idle timeouts.** A private thread's sandbox stays 15 minutes after an interactive turn and 30 seconds after a background turn. A shared thread's sandbox stays 30 seconds after its turn. Review and task sandboxes stay 1 minute.
5. **`next` long-polls.** A warm sandbox's `next` waits up to 25 seconds for its next run. It claims the run as soon as it is due. #4858 merged this. At most half of the gateway's connections wait at once.
6. **The degradation ladder is binding UX.**
   1. The message is committed, and a retry of the submit is safe.
   2. An honest wait: "Queued". On shared capacity, an estimate is static or comes from the requester's own turns only, never from live shared load.
   3. `LATE` shows when the wait passes a fixed bound. It needs no first estimate.
   4. An honest stop after 12 hours names what still works without a model.

   The ladder adds no push. When the answer arrives, ADR 0116 rule 7 decides the push: an answer that is still unread 30 seconds after its turn ends gets one.
7. **Only rate limits refuse,** with `429 rail-reached` and the reset time. Admission, model permits and the provider queue never refuse for capacity. Queue position is never shown.
8. **Two operating profiles, one code path.** The profile follows the price that the catalog stores. On models with no metered API cost, the purses of ADR 0083 never fill. Rate limits then become safety limits at about ten times the heaviest real use.
9. **Models and money stay as ADR 0083 decides.**
   - One binding names one model for each purpose and tier. The loosest ready binding within the person's AI choice serves. Nothing falls back to another model.
   - The shared-model budget and the provider cap stay two purses, never summed. Each pauses only the work that it funds.
   - Load makes work wait in the queue. It never switches the model. A paused purse also makes work wait, as ADR 0083 rules 15 and 16 say.
   - Every personal-space run spends from the instance-funded cap, as ADR 0126 (proposed in #4863) decides.
10. **Sizing.** `docs/admin/sizing.mdx` states `capacity.turns` for each profile from measurements.
    - The starting values are hypotheses: about 40 for 1,000 people and 2 for 20 people.
    - The capacity benchmark must confirm them before the sizing guide publishes them.
11. **1.x adds** reserves, payer pools and holds over several counters, when measurements show the need. A fallback to another model needs an amendment of ADR 0083.

### What this changes in other ADRs

- [ADR 0079](0079-one-agent-system.md) § Claim, priority and reap orders claims by band, then workspace, then age. It now also leases a background turn only while one place stays free. A warm sandbox's `next` long-polls.
- ADR 0079's revisit trigger on a `SandboxWarmPool` stays, but 1.0 adds no warm pool.
- [ADR 0083](0083-each-purpose-uses-one-governed-model-and-every-call-is-counted.md) and [ADR 0116](0116-a-push-is-one-web-push-message-from-the-instance.md) stay as they are.

## Consequences

- Good: a person's question always finds a place or an honest wait.
- Good: no new Helm value. Operators size one existing value.
- Good: the copy deadline holds under load, because the sweep needs no sandbox.
- Bad: one place stays idle when no person talks, so background work runs one place short.
- Bad: steady chat delays optional background messages.
- Bad: a large instance needs a much higher `capacity.turns` than the chart default of 8.
- Bad: while the bound model's provider is down, interactive turns wait. No other model answers in its place.

## Confirmation

Not enforced. The implementing issues add the checks below, and review of each implementing pull request catches a missing one. They are:

- A claim test fails when a background turn takes the last free place under `capacity.turns`, also on a warm subject claim.
- A ladder test with a controlled clock fails when a waiting turn misses a step.
- A test fails when a question is refused for load.
- A breaker test fails when a background claim starts while the breaker of the LLM proxy is open.
- A routing test fails when a waiting turn gets a model other than its binding names.
- `AgentNextWaitsIntegrationTest` (merged in #4858) fails when a due run does not wake a waiting `next`.

The capacity benchmark measures the service targets on the one-host and `cluster` profiles before the freeze.

## Revisit trigger

- The p95 of submission to first visible text exceeds 3 seconds warm or 6 seconds cold on a sized instance.
- Background turns wait more than 10 minutes at the p95 on a sized instance.

## Sources

- Google SRE book, [Handling Overload](https://sre.google/sre-book/handling-overload/). Serve by criticality and degrade, rather than fail every request when overloaded. This gives decisions 2 and 6.
- Google SRE book, [Addressing Cascading Failures](https://sre.google/sre-book/addressing-cascading-failures/). Refusals cause client retries that amplify load. This gives decision 7.
