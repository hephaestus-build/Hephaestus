# ADR 0065: Standing counts the newest observation of each item

**Status:** Accepted
**Date:** 2026-10-26
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0051](0051-practices-across-the-workspace-counts-developers.md) § Update — 2026-10-20, where a work change ends the live review.

## Context

Standing reads one live run for each practice, item and developer.
`PracticeStandingService.liveRunOf` finds it.
It follows the current claim back through the claims that it replaced.
A work change, a repair or a removal ends that chain.

A new item version ends the current results of the item as `WORK_CHANGED` (`ResultCurrentness`).
Only the practices that select the new event run again.
In the default catalog, 6 of 52 practices select only `scm.pull_request.opened` and `scm.pull_request.ready`.
Thus, the first push to a pull request ends their results, and no newer result replaces them.
The item drops out of the developer's standing for these practices, although no review decided it again.

Every reader of the live run sees the drop:

- the standing and the trend on the Practice profile and the practice page,
- the practice group standings and the bars of Practices across the workspace (ADR 0051),
- Heph's profile context (`PracticeProfileContentSource`).

## Decision drivers

- Standing describes decided work. A push that no practice reviewed again does not undo a decision.
- The practice author chooses which events run a practice. Standing must not punish that choice.
- The rule stays in the read. Results stay as recorded.

## Considered options

1. **Keep ending the chain at a work change (current).** Rejected. It drops decided work from standing for practices that do not run on a push.
2. **Run every practice on every push.** Rejected. It spends model cost against the author's choice, and [ADR 0062](0062-the-item-is-the-review-scope.md) reduces reviews of successive pushes.
3. **Count the newest observation, also when a work change ended it.** Chosen.

## Decision

Standing counts the newest live observation for each practice, item and developer.
An observation that a newer item version ended as `WORK_CHANGED` still counts.
It counts until a newer live observation of the same practice, item and developer replaces it.

The other end reasons do not change:

- `REPAIRED` and `REMOVED` still end the chain.
- An `OUTRANKED` result never held the claim.
- A requested or backfilled review still neither counts nor hides the live run before it.

Feedback does not change. An ended result gets no new feedback (`FeedbackGate`).

## Consequences

- A practice that runs only at open and ready keeps the item in standing after a push.
- Standing can count an observation about an earlier version of the work. This is correct, because it is the newest decision about the item.
- The change is in the read only. It needs no migration, and it applies to stored results at once.
- The slice that ships this changes `liveRunOf`, with tests that assert standing over stored results. It also updates the Standing term in the [practice feedback language](../contributor/practice-feedback-language.md#people-and-product-surfaces) and the user guide.

## Revisit trigger

Revisit when a practice must lose standing at a work change, for example a practice about the current state of a branch.
