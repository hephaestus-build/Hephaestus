# ADR 0021: Separate observations from feedback

**Status:** Accepted (amended 2026-07-31 #1423, 2026-08-16 #1430 — rewritten in place; refined by [ADR 0022](0022-observation-presence-assessment-and-schema-cleanup.md) and [ADR 0029](0029-measurement-intervention-seam-and-channel-levels.md))  
**Date:** 2026-06-14  
**Authors:** Felix T.J. Dietrich

**Builds on:** [ADR 0020](0020-context-fabric-everything-is-an-integration.md) and
[ADR 0007](0007-sandbox-spi-shape.md)  
**Refined by:** [ADR 0022](0022-observation-presence-assessment-and-schema-cleanup.md) and
[ADR 0029](0029-measurement-intervention-seam-and-channel-levels.md)

## Context

Practice review has two different responsibilities:

1. determine what the available evidence supports; and
2. decide which intervention, if any, will help the developer.

Combining them makes advice look like evidence, encourages every observation to become a notification,
and leaves no durable record of what the developer actually received.

## Decision

An **observation** is an immutable, audience-neutral measurement of one practice on one artifact. It
records the outcome, the evidence that supports it, and an evidence rationale. It contains no advice.

**Feedback** is a durable intervention composed from one or more observations for one delivery channel.
It may be delivered, withheld, or superseded. We deliver feedback, never raw observations.

Measurement and composition run as separate turns in one agent session, with a hard capability seam:

- `report_observation` records measurements and is closed after Java admits them.
- `report_feedback` proposes feedback only after admitted observations, history, and deltas are staged.
  It cannot create observation evidence or override an observation outcome.

The server resolves every observation reference and inline anchor. Model-provided identifiers, citations,
and positions are never trusted without validation.

## Granularity and identity

One feedback row is one independently addressable pedagogical unit, not an entire provider comment.
`FeedbackObservation` records the many-to-many evidence binding. Delivery placement is separate because
one unit may appear in a summary and as an inline note, each with its own provider identifier.

Observation identity and feedback continuity solve different problems:

- observation fingerprints correlate equivalent measurements across reviews;
- feedback thread keys identify the habit or intervention that may be superseded;
- provider placement identifiers track the concrete delivered artifact.

These identities must not be substituted for one another.

## Selection and delivery

Composition is not one-observation-in, one-message-out. It groups related evidence, accounts for what was
already said, and may record a reason to withhold. A failure in composition must not turn the complete
observation set into user-facing output.

Channels have distinct contracts:

- **In context:** a concrete, actionable intervention about the current artifact, rendered at a validated
  provider location when appropriate.
- **In app:** private process feedback across work, with at most one unread item per feedback thread.
- **In conversation:** structured mentor notes grounded in the bound observations. The mentor agent uses
  those notes, the original evidence, and the live conversation to choose the wording and timing; the
  prepared artifact is not a script.

Subject and recipient remain separate identities. Feedback is developer-facing; this schema does not
create a facilitator or grading channel.

## Trust and audit boundaries

- Observation emission is reaction-blind so reception cannot rewrite measurement.
- Feedback persists its observation bindings, channel, lifecycle state, and suppression reason.
- Already delivered feedback is never rewritten. Supersession links a new unit to the unread unit it
  replaces.
- Provider adapters render admitted feedback; they do not decide pedagogical content.
- Conversation preparation preserves workspace authorization and consent when resolving evidence.

## Consequences

Detection quality can be evaluated without treating every measurement as a message. Delivery quality and
reception can be evaluated from the feedback ledger without reconstructing provider comments. New delivery
channels reuse observation bindings and lifecycle rules rather than adding advice to observations.

The additional composition stage costs another model call and can fail independently. The system therefore
records withholding and preparation failures explicitly, while keeping observations available for audit.

## Rejected alternatives

- **Store advice on observations.** Advice depends on audience, history, and channel; persisting it with the
  measurement couples two different acts.
- **Render every observation.** This optimizes output volume rather than usefulness and turns abstention or
  measurement noise into notification noise.
- **Persist one row per provider comment.** A blob cannot support per-unit evidence binding, placement,
  reaction, or supersession.
- **Let the composer invent citations or locations.** The server already owns the authoritative artifacts
  and must validate those references.
- **Generate a chat opener to read verbatim.** A later mentor agent has the live conversation and is better
  placed to choose whether to ask, explain, challenge, or defer.

## Authoritative contracts

This ADR owns the separation and its invariants, not field inventories. Current schemas live in:

- [Practice feedback schema](../contributor/practice-feedback-schema.md)
- [Practice review pipeline](../contributor/practice-review-pipeline.mdx)
- [ADR 0029](0029-measurement-intervention-seam-and-channel-levels.md)
- the generated [database schema](../contributor/database-schema.mdx) and OpenAPI specification
