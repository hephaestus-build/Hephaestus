# ADR 0137: On request is a feedback mode

**Status:** Proposed
**Date:** 2026-11-23
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0086](0086-a-practice-review-stages-seals-then-composes-and-each-stage-records-why-it-stops.md) (the feedback modes that bound delivery)

**Builds on:** [ADR 0093](0093-members-see-activity-a-workspace-can-publish-its-public-work-and-private-practice-feedback-has-one-reader.md) (private practice feedback has one reader), ADR 0139 (proposed in #4865, the contact policy)

**Relates to:** ADR 0131 (proposed in #4862, results in the conversation), ADR 0133 (proposed in #4865, pre-checks)

## Context

On main, a practice's feedback mode is Off, Approve first or Automatic.
Approve first and Automatic deliver feedback to every destination that the practice allows, the work included.
Off delivers no feedback, and it also narrows measurement ([ADR 0086](0086-a-practice-review-stages-seals-then-composes-and-each-stage-records-why-it-stops.md), update of 2026-11-21 on pushes).
A push, an edit and a follow-up leave out an Off practice. It is measured only at a milestone or an outcome of its item.
When every practice that a push selects is Off, the push is withheld with `FEEDBACK_MODE_OFF`, before measurement.

Some practices fit a person who asks, not a comment that the team reads.
A course can want students to keep feedback to themselves.
A team can want a practice measured for the person's own learning, with no comment on the pull request and no message that Hephaestus starts.
Off gives the person nothing. Automatic puts it on the work.

"Where feedback may appear" exists in the product vocabulary as a set of destinations, but no per-practice setting holds it.
The workspace also has the setting "Reviews here start only on request", which decides when a review starts, not where feedback goes.
The real constraint is a mode that keeps a practice's results with the person until the person asks, and that every writer enforces in code.

## Decision drivers

- The person can ask about any measured practice and get its results.
- An On request result never appears where other people read, and never arrives unasked.
- Every writer reads one mode. No writer decides it alone.
- A course or a team sets where feedback may appear for each practice.
- Each setting has its reverse in both admin consoles.

## Considered options

1. **Off for these practices.** Observations are recorded, but the person never sees them in a useful form. The practice gives no learning.
2. **Approve first with an admin who never approves.** The copy on the work waits forever, and the admin becomes a gate on private learning. The person sees "Waiting for approval" for a result that will never post.
3. **Destinations only, with no new mode.** A practice with only the practice page as its destination still sends messages that Hephaestus starts. It also still counts in growth as if feedback had gone out.
4. **A personal setting for each person.** Each person turns a practice to "only when I ask". The team cannot keep a graded course consistent. The setting differs from one person to the next on the same work.
5. **On request as a fourth feedback mode, with per-practice destinations (chosen).**

## Decision

Option 5.

1. **`ON_REQUEST` joins `FeedbackMode`.** The modes are Off, On request, Approve first and Automatic. Both admin consoles show it.
2. **A review selects and measures an On request practice as it does an Automatic one.**
   - A push, an edit and a follow-up measure it. Off's narrower selection does not apply, so a person who asks gets the result for the newest head.
   - A push that selects an On request practice is never withheld with `FEEDBACK_MODE_OFF`.
   - On request differs from Automatic only after the seal, in where its results may go.
3. **An On request practice never appears on the work.** This holds also after Request review. The composer's guardrails and the delivery gate refuse it.
4. **It never appears in a message that Hephaestus starts.** The contact policy removes it. A check-in pre-check never reads its outcome.
5. **The person asks for it.** The practice page shows "Ask Hephaestus about this practice". A request is naming the practice in the conversation or selecting that control.
   - Before a request, no projection holds an On request result. The item card, the practice page and the change feed leave it out.
   - A request releases the results sealed up to then, in all three projections and in the conversation. A later seal waits for the next request.
   - The mode at the seal decides for each result. A mode change emits no feed change. A result held under On request was never sent, so no client holds it. A result sent before the change stays.
6. **A conversation summary never names it.** The summary validator drops an item that names an On request or Off practice.
7. **An On request seal writes no growth point.** Help on request never fades.
8. **"Where feedback may appear" is set for each practice:** on the work, on the practice page, in conversation. The workspace holds a default (`default_feedback_destinations`). A practice entry overrides it.
   - **Remove override** is the reverse, in both admin consoles. It deletes the practice's destinations, and the practice uses the workspace default again.
9. **Modes and destinations bound every copy.** They feed the eligible set of each place. The composer's first guardrail and the delivery gate enforce them. A copy that puts a problem outside the allowed destinations is refused.
10. **Exclusion of a Critical or Major problem** follows the workspace policy `allowSevereExclusion` of ADR 0121 (proposed in #4862) decision 8.
11. **Practice file format version 2** carries the mode and the destinations. Version 1 files still load, with the workspace default.
12. **On request is not "Reviews here start only on request".** That setting decides when a review starts. This mode decides where its feedback goes. The glossary keeps them apart.

### What this changes in other ADRs

- [ADR 0086](0086-a-practice-review-stages-seals-then-composes-and-each-stage-records-why-it-stops.md) stays. Its stages run for an On request practice. Delivery stops at the mode, with its own recorded reason. Its update of 2026-11-21 on pushes keeps Off's narrower selection for Off only. On request feedback reaches the person who asks, so a push measures it.
- ADR 0135 (proposed in #4867) streams item cards as typed changes. Decision 5 of this ADR decides which changes carry an On request result.
- ADR 0121 (proposed in #4862) decision 7, guardrail 1, reads this mode and these destinations.
- [ADR 0130](0130-a-practice-has-a-portable-identity-and-growth-counts-independent-pieces-of-work.md) owns growth points. This ADR adds that an On request seal writes none.
- The review glossary's Feedback mode row gains On request on acceptance.

## Consequences

- Good: a course can keep a practice's feedback with the student, and the practice still teaches.
- Good: one mode, read by every writer, keeps an On request result off the work and out of unasked messages.
- Good: per-practice destinations replace a workspace-wide choice.
- Bad: the model budget pays for reviews whose results nobody may read until they ask. A push measures an On request practice, as it does an Automatic one.
- Bad: a person who never asks never learns from an On request practice.
- Bad: two settings with "on request" in their names exist. The glossary and the consoles must keep them apart.
- Bad: the practice file format changes. The loader reads two versions.

## Confirmation

Not enforced yet. The implementation adds these tests:

- `OnRequestNeverOnWorkTest` fails when an On request practice appears in a copy on the work, also after Request review.
- `OnRequestNotStartedTest` fails when an On request outcome appears in a message that Hephaestus starts.
- `DestinationGuardrailTest` fails when a copy that puts a problem outside the allowed destinations is admitted.
- `OnRequestNoGrowthTest` fails when an On request seal writes a growth point.
- `PracticeFileVersionOneTest` fails when a version 1 practice file no longer loads.
- `OnRequestProjectionTest` fails when an item card, a practice page or a feed change carries an On request result before a request.
- `RemoveOverrideTest` fails when **Remove override** leaves the practice's own destinations, in either console.
- `OnRequestSelectionTest` fails when a push leaves out an On request practice, or withholds it with `FEEDBACK_MODE_OFF`.
- Stories in both admin consoles show the mode and the destinations, with their empty, loading and error states.

## Revisit trigger

- Most workspaces set every practice to On request. Then the default mode or the onboarding needs review.
- People ask for an On request practice to start a check-in.
- An admin needs On request for one person only.

## Sources

- Cursor, [Bugbot](https://cursor.com/docs/bugbot). Bugbot "runs automatically on each PR update or manually when triggered". Common practice uses "on request" for when a review runs. Decision 12 keeps that meaning in "Reviews here start only on request" and gives this mode its own name.
