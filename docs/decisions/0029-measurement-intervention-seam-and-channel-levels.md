# ADR 0029: Separate measurement from intervention and derive level from channel

**Status:** Accepted (amended 2026-09-17 — `basedOn` names this run's observations only)
**Date:** 2026-08-16
**Authors:** Felix T.J. Dietrich
**Builds on:** [ADR 0021](0021-observations-feedback-synthesis-seam.md), [ADR 0022](0022-observation-presence-assessment-and-schema-cleanup.md), [ADR 0007](0007-sandbox-spi-shape.md)

## Context

A practice review performs two different acts:

- **Measurement** records a falsifiable claim about an artifact and its evidence.
- **Intervention** decides whether, where, and how to give feedback using the admitted measurements,
  prior observations, prior feedback, and current delivery state.

Combining them biases measurement toward claims that are easy to turn into advice. It also prevents
feedback from accounting for recurrence, resolution, unread feedback, or deliberate silence.

The product has three feedback surfaces. They require different content because they serve different
feedback levels and audiences; they are not renderings of one message.

## Decision drivers

- Observation content must remain evidence-bound and free of advice.
- Feedback must bind to admitted observations and may not invent evidence, placement, or supersession.
- Silence must be an explicit outcome.
- Public feedback must not disclose longitudinal judgements about a person.
- Feedback text must be durable and attributable to the evidence it used.
- The model may write; Java retains authorization, admission, persistence, and delivery control.

## Decision

### Two phases in one agent session

A practice review uses one Pi session with a server admission boundary between its phases.

1. `report_observation` records `summary`, `outcome`, the required evidence branch, and
   `evidenceRationale`. It contains no advice or self-reported confidence.
2. The runner submits the normalized observations to the authenticated admission callback. Java
   validates, scopes, coerces, persists, and returns their durable projection and admission digest.
3. The runner closes `report_observation`, opens `report_feedback`, stages the admitted projection and
   history, and resumes the same session with `agent/feedback-composer.md`.
4. Delivery requires the feedback admission digest to match the digest stored during admission.

The capability gates are enforced by the runner, not only by the prompt. Callback failure aborts the
run. A retry with the same serialized payload reuses the admitted observations; a different payload
conflicts.

### Channel determines feedback level

| Channel      | Surface and audience                   | Level           | Content                                                            |
| ------------ | -------------------------------------- | --------------- | ------------------------------------------------------------------ |
| `IN_CONTEXT` | Work artifact; public to collaborators | Task            | One artifact-specific action, placed on the artifact               |
| `IN_APP`     | Recipient's private practice pages     | Process         | A pattern across work and one process change                       |
| `IN_CHAT`    | Private mentor conversation            | Self-regulation | Evidence-bound mentor notes and an in-conversation learning signal |

The level is derived from `FeedbackChannel`; it is not stored as a second source of truth.

`IN_CONTEXT` never makes a longitudinal claim about a person. `IN_APP` and `IN_CHAT` do not reproduce
code excerpts as feedback. The mentor brief is not dialogue: the mentor chooses the wording and move
from the brief, admitted evidence, and live conversation.

### Composition contract

Each `report_feedback` call contains `channel`, `practiceSlug`, `basedOn`, and `action`.

- `IN_CONTEXT`: `title`, `placement`, `nextStep`.
- `IN_APP`: `title`, `body`, `nextStep`.
- `IN_CHAT`: `title` and `notes.{situation, capability, evidenceSummary, inConversationSignal}`.
- `WITHHOLD`: `withholdReason` instead of deliverable content.

`basedOn` may name only an admitted observation of the same practice or the exact
`prior:<practiceSlug>` reference. A diff placement names an admitted observation and citation index;
Java resolves the coordinates. An artifact placement has no coordinates and must bind to a current
observation. `SUPERSEDE` may name only a staged prepared-feedback thread.

Composition carries no observation outcome, severity, confidence, or model-authored citation.

### Server-owned delivery

Java applies tier, origin, reaction, channel, placement, recipient, and lane-cap rules after
composition. Model output cannot authorize its own delivery.

Longitudinal feedback uses a stable thread key. Supersession replaces only a `PREPARED` row. Feedback
already read remains `DELIVERED`; a later row may link to it but cannot rewrite it. In-context provider
comments may be edited in place, and the ledger records that provider-visible transition.

There is no measurement-text fallback. Missing, invalid, or withheld composition produces no feedback
rather than converting evidence rationale into accidental advice. Lane preparation marks make empty
results and recovery distinguishable.

## Alternatives considered

- **Advice in `report_observation`: rejected.** It couples measurement to intervention and cannot use
  longitudinal context.
- **One composer call per channel: rejected.** It repeats context and prevents the model from resolving
  overlap between channels in one decision.
- **Deterministic Java templates: rejected.** Admission is deterministic, but audience-sensitive
  synthesis, supersession, and withholding are composition decisions.
- **A separate agent session: rejected.** Explicit admitted projections preserve the trust boundary;
  retaining the session also preserves useful artifact context and avoids reconstructing it.

## Consequences

- Observations remain immutable measurements; feedback becomes a separate durable record.
- Every delivered message is bound to admitted evidence and an explicit channel.
- The same practice may produce different interventions across channels without duplicating a generic
  guidance string.
- Same-session continuation incurs provider context cost; prompt caching may reduce but does not remove
  it.
- Composition failure reduces feedback quantity rather than weakening the measurement/intervention
  boundary.

## Constraints

- Cross-person aggregates require small-cell and complementary-cell protections before release.
- Current cooldowns do not provide a per-person cross-artifact rate limit.
- Reviewer-attributed observations remain withheld from longitudinal author feedback until recipient
  attribution is supported end to end.

## Revisit when

- composition becomes independently deployable;
- a surface does not fit task, process, or self-regulation feedback;
- a cross-person aggregate is proposed;
- reviewer feedback recipient attribution is implemented; or
- scheduled synthesis requires a recurrence identity that spans artifacts.

## Contract locations

- [Practice review pipeline](../contributor/practice-review-pipeline.mdx)
- [Practice review glossary](../contributor/practice-review-glossary.mdx)
- [Practice review data model](../contributor/practice-feedback-schema.md)
- `agent/feedback-composer.md`, `pi-runner.ts`, and `agent.handler.composition`

## Sources

- Hattie & Timperley, [*The Power of Feedback*](https://doi.org/10.3102/003465430298487), 2007.
- Sadler, [*Formative assessment and the design of instructional systems*](https://doi.org/10.1007/BF00117714), 1989.

## Update — 2026-09-17: `basedOn` names this run's observations only

Supersedes the `basedOn` rule in § Composition contract. `basedOn` names only admitted observations
from the current run, and at least one must belong to the unit's `practiceSlug`
(`validateFeedbackEvidence` in `server/application/src/main/resources/agent/pi-runner-composition.ts`;
`ComposedFeedbackUnit` in `agent.handler.composition`); there is no `prior:<practiceSlug>`
reference. Feedback that rests on the record rather than on this run is expressed through the
history staged for the composer, and the `IN_CHAT` `notes` object carries an optional `alreadySaid`
for it (`pi-runner.ts`). Everything else in that section stands.

## Update — 2026-10-05: the review on the work is written whole in its own session

This update supersedes the `IN_CONTEXT` line of § Composition contract and step 3 of § Two phases in one agent
session for `IN_CONTEXT`. It also supersedes the rejection of a separate session for `IN_CONTEXT`.

The earlier contract asked the model for a title, a placement and a next step per practice.
Java joined those fragments into the comment.
The result read as a template, and the server wrote sentences that no model wrote.

- The model writes one complete summary and complete line notes in one `report_review` call.
  Each body names in `basedOn` every observation that it speaks about.
  A `withheld` entry names exact `NOT_MET` observations with a composition reason.
- The review runs in a fresh session with only `report_review`.
  The session loads no context files, skills, prompt templates or extensions, and it has no file or execution tools.
  Its input is the decided observations that may appear on the work, earlier public feedback on the same work, and
  the public facts of each practice revision.
  An observation that cites or consulted a source outside the reviewed work stays out of the session
  (`PublicReviewEligibility`).
  Private history and observations with private or unknown recorded sources are excluded from public composition.
- Private composition (`IN_APP`, `IN_CHAT`) keeps the same session and contract as before.
- Java admits each body whole or refuses it whole.
  It never assembles or edits semantic claims; provider safety escaping follows the pipeline contract.
  The composition output carries `contractVersion` 2.
  When a review is owed, an output without that version or without a review is a delivery failure.
  This failure makes no claim about the work.

Everything else in this ADR stands. The contract lives in `agent/review-composer.md`, `pi-runner-composition.ts`,
`ComposedReview` and `ComposedReviewAdmission`.
