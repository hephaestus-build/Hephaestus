# ADR 0061: A practice review seals its results, then feedback is composed

**Status:** Accepted
**Date:** 2026-10-26
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0029](0029-measurement-intervention-seam-and-channel-levels.md) § where composition runs. The seam between measurement and intervention stays.

## Context

A practice review measures work and gives feedback about it.
Before [#3230](https://github.com/hephaestus-build/Hephaestus/pull/3230), one sandbox Pod did both.
The runner measured every practice and admitted the results.
Then it composed the feedback in the same process, attempt, budget and fence.

This coupling had three costs:

- A composition timeout after full admission made a decided review Partial.
- A worker lost after admission needed a new sandbox to finish the feedback.
- A composition retry spent a review attempt, so feedback failures and measurement failures shared one retry limit for different reasons.

The two halves have different inputs, trust and failure modes.
Measurement runs untrusted practice scripts and model sessions with tools in a sandbox.
Composition runs no untrusted code. It reads sealed results and calls the governed model proxy.

## Decision drivers

- A result is a claim about work. Feedback is one presentation of that claim. A presentation failure must not change the claim.
- The sandbox runs only what needs isolation.
- Each failure keeps its own recorded reason (non-negotiable 1).
- No new execution machinery for 1.0.

## Considered options

1. **Keep composition in the sandbox.** Rejected. It keeps all three costs.
2. **Composition as its own review job kind with its own Pod, attempts and stop reason.** Rejected.
   Composition runs no untrusted code, so a Pod adds start time and a sandbox slot for proxy calls only.
   A second attempt counter and a job-level stop reason give the same fact two homes.
3. **Composition as a worker step of the same review job, after the sandbox ends.** Chosen.

## Decision

A practice review has three parts. Each part has one owner and one direction:

| Part | Owner | Produces | Model |
| --- | --- | --- | --- |
| Measure | the sandbox (practice scripts and review sessions) | candidates | optional |
| Admit and seal | the server and the worker | results and problems | none |
| Compose and deliver | the worker, then the server | feedback pieces per destination | required for composition |

Dependencies point one way. Feedback reads sealed results. It never writes a result.

- **Seal.** A review seals when each assigned practice has an admitted candidate, a withheld candidate or a runtime non-result.
  The seal writes the results and problems.
  The review status (Complete, Partial, Failed, Cancelled) comes from these three facts only.
- **Compose.** After the sandbox ends, the worker composes the feedback for each eligible destination through `ReviewModelGateway`.
  `WorkerFeedbackComposer` (`agent/composer/`) owns this step since #3230.
  It uses the attempt's model, price, budget and fence.
  The sandbox slot is free while the worker composes.
- **Retry.** A cause that another attempt would meet again withholds the destination at once with its reason.
  A passing cause hands the job on. The next attempt composes from the retained basis and starts no sandbox.
  Composition-only attempts share the job's attempt counter and retry limit.
- **Stop reasons.** A destination that is not composed records `NOT_COMPOSED` or a more specific feedback stop reason on its feedback row.
  `CompositionStop` names the cause. The cause goes to the counter `agent.job.composition.stopped`. The job has no second composition stop reason.
- **Skip.** When every sealed result has ended, the worker composes nothing.
  A newer push that replaced the version is the usual cause.
  This rule is not shipped yet. It follows [#3238](https://github.com/hephaestus-build/Hephaestus/pull/3238). Until then, delivery withholds feedback on ended results.

The [pipeline](../contributor/practice-review-pipeline.mdx#compose-feedback) owns the stage rules.

## Consequences

- A composition failure never fails an admitted review, and it never changes a result.
- Model-free results are first class. A practice can decide in code. Only composition needs a model.
- The runner has no composition stage. [#3238](https://github.com/hephaestus-build/Hephaestus/pull/3238) removes it and the sandbox routes `/internal/llm/prepare-feedback` and `/internal/llm/public-feedback-history`.
- Composition and measurement retries share one limit. This is acceptable because a composition-only attempt is cheap: it starts no sandbox.

## Revisit trigger

Revisit when composition needs tools or untrusted input that requires isolation, or when measured composition retries starve measurement retries.
