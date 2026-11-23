# ADR 0122: A copy on the work that misses its deadline is withheld

**Status:** Proposed
**Date:** 2026-11-23
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0086](0086-a-practice-review-stages-seals-then-composes-and-each-stage-records-why-it-stops.md) decision 14. [ADR 0079](0079-one-agent-system.md) § Update — 2026-11-11: compose deadlines.

**Builds on:** [ADR 0120](0120-a-conversation-thread-has-one-inbox-and-runs-one-turn-at-a-time.md) (custody and the commit rule), [ADR 0121](0121-a-composer-on-a-shared-thread-writes-feedback-on-the-work-and-cannot-read-private-data.md) (the composer and the typed commands)

**Relates to:**

- [ADR 0029](0029-measurement-intervention-seam-and-channel-levels.md) (no measurement-text fallback)
- [ADR 0150](0150-an-outcome-renders-from-rows-as-an-item-card-or-a-citation-token.md) (the item card)
- [ADR 0151](0151-each-external-write-has-one-delivery-key-and-is-read-back-never-retried-blindly.md) (the delivery key and the first-write transaction)
- ADR 0139 (proposed in #4865: the contact policy)
- ADR 0149 (proposed in #4868: the delivery experiment)

## Context

With [ADR 0121](0121-a-composer-on-a-shared-thread-writes-feedback-on-the-work-and-cannot-read-private-data.md), a composer turn writes the copy on the work.
The turn waits in its thread's inbox, and the model provider can be down.
A copy that arrives hours late, or after a merge, describes work that the team already left.

Main posts no fallback text.
[ADR 0029](0029-measurement-intervention-seam-and-channel-levels.md) says: "There is no measurement-text fallback. Missing, invalid, or withheld composition produces no feedback."
ADR 0029 is superseded, but [ADR 0086](0086-a-practice-review-stages-seals-then-composes-and-each-stage-records-why-it-stops.md) keeps the rule. It lists "a fixed template" as already priced and declined.
On a missing model, main withholds the copy as `NOT_COMPOSED` with no body, and makes no model call.

Main bounds time with one clock for a whole review.
`hephaestus.agent.capacity.model-outage-window` is 2 hours from the review's first admission, or from its submission.
It bounds measurement and composition together.
After it, the review closes as Partial with `PROVIDER_UNAVAILABLE`.
If no attempt confirms every destination, the review closes as Partial with `COMPOSITION_UNCONFIRMED`.
That reports a delivery fact as a fact about the review.

## Decision drivers

- Feedback earns trust or it is not sent. A late copy on moved work is worse than no copy.
- A delivery fact is never reported as missing evidence or as a fact about the review.
- The person can still get the copy on the work, by an act that they confirm.

## Considered options

1. **A fixed fallback copy after a short deadline.** A fixed renderer writes the required problems from admitted data when the composer is late. A queue delay then chooses standard wording for good. ADR 0086 declines a fixed template.
2. **No deadline.** The copy posts when the composer finishes. After an outage it can post hours late, on an old version or on merged work.
3. **Keep main's outage window as the only bound.** It starts before the seal and mixes measurement with composition. An outage closes the review as Partial, which is a provider fact stated as a review fact.
4. **A seal deadline that withholds the copy (chosen).**

## Decision

Option 4.

1. **The seal starts the clock.** The seal inserts the feedback row for the work in state `PENDING`, with `deadline_at` 2 hours after the seal.
2. **A staged copy must meet its create predicate.** The composer's commit stages the copy only if the database time is before `deadline_at`. The item must be open, the head must be the reviewed version, and the generation must be current.
3. **Earlier withholding.** A merge or a close withholds every open proposal of the item as `ITEM_CLOSED`. A newer version or a newer eligible seal ends it as `REPLACED`.
4. **The sweep withholds a late copy.** It settles each unstarted Automatic proposal past `deadline_at` as `WITHHELD` with `DEADLINE_PASSED`.
   - This also happens while the executor is stopped. Its actions end `CANCELLED`.
   - The first-write transaction of [ADR 0151](0151-each-external-write-has-one-delivery-key-and-is-read-back-never-retried-blindly.md) checks the predicate again, so a queued send cannot post late.
5. **Approve first keeps its own clock.** Staging must happen before `deadline_at`. After that, main's approval expiry of one day applies to the approved text ([ADR 0069](0069-practices-run-when-ready-and-stay-current-by-their-sources.md)).
6. **No template and no late post.** Hephaestus posts no template comment, no "review failed" comment and no automatic late copy. The fixed renderer for graded work ([ADR 0121](0121-a-composer-on-a-shared-thread-writes-feedback-on-the-work-and-cannot-read-private-data.md) decision 10) is a workspace mode, not a fallback.
7. **The review stays Complete.** `DEADLINE_PASSED` is a delivery reason on the destination. Nothing is reported as missing evidence.
8. **The withholding is always recorded. A message about it is optional.**
   - The withholding transaction inserts a required `DELIVERY_WITHHELD` inbox event into the author's main thread.
   - The item card shows the withheld copy and its reason from rows.
   - A private message needs a Critical or Major problem in the eligible set and one of three reasons.
   - The reasons are `DEADLINE_PASSED`, a second guardrail refusal (ADR 0121 decision 7) and an expired approval.
   - `ITEM_CLOSED`, `REPLACED` and a withholding by feedback mode or destination never produce a message.
   - The message is unprompted contact. The contact policy of ADR 0139 (proposed in #4865) can block it, for example after a pause.
   - Without a message, the start policy starts no turn. The sweep ends the inbox event with its fixed disposition.
   - The message can carry the card controls of decision 9.
   - A copy that a silent draw withholds is the one exception. ADR 0149 (proposed in #4868) decision 7 decides its disposition, `STUDY_SILENCE`.
9. **Two direct controls on the item card.** Both need no conversation turn and use the preview and confirmation of ADR 0121.
   - **Request a new copy.** The proposal row returns to `PENDING`, with the next generation and a new deadline. The composer writes a fresh copy.
   - **Post these exact words.** Only in Automatic mode, and only for a composed body that was withheld before its write started. The preview shows the body. The confirmation takes the place of the deadline in the create predicate.

### Difference from main

| Fact | Main | This ADR |
| --- | --- | --- |
| Clock start | The review's first admission, or its submission | The seal |
| What the clock bounds | Measurement and composition together | Only the copy on the work |
| Review status after an outage | Partial, with `PROVIDER_UNAVAILABLE` or `COMPOSITION_UNCONFIRMED` | Complete. The destination carries `DEADLINE_PASSED`. |
| What the person gets | The withheld state only | The withheld state on the item card and two controls. A private message when decision 8 allows it. |
| Fallback text | None | None |

Example: a review waits 20 minutes for capacity and seals 25 minutes after submission. Main's clock starts at minute 20, and this ADR's clock starts at minute 25.

### What this changes in other ADRs

- [ADR 0086](0086-a-practice-review-stages-seals-then-composes-and-each-stage-records-why-it-stops.md) decision 14 changes. A composition stop no longer closes the review as Partial with `COMPOSITION_UNCONFIRMED`. The destination records its reason, and the review stays Complete.
- The model outage window keeps bounding measurement. It no longer bounds composition.
- [ADR 0079](0079-one-agent-system.md) § Update — 2026-11-11 gives each composition run a deadline. A composer turn keeps its turn bounds, but only `deadline_at` withholds the copy.

## Consequences

- Good: no copy appears on work that moved on or after a long outage.
- Good: the review result stays true when delivery fails. A provider outage is never a statement about the work.
- Good: the author can still put the copy on the work, with one confirmed act.
- Bad: during an outage of more than 2 hours, the team sees no copy unless the author acts.
- Bad: the 2-hour value is a choice, not a measurement.
- Bad: a pause or a mute can block the private message. The item card still shows the withheld copy.
- Bad: a silent draw of a delivery experiment withholds a copy with no `DELIVERY_WITHHELD` inbox event (ADR 0149).

## Confirmation

Not enforced yet. The implementation adds these tests:

- `ExpiredReadyWithStoppedExecutorTest` fails when a `READY` Automatic copy past its deadline is not withheld while the executor is stopped.
- `RequestNewCopyFromCardTest` and `PostExactWordsTest` fail when a control needs a turn or changes the body.
- `WithheldDispositionTest` fails when a withheld copy outside a silent draw writes no `DELIVERY_WITHHELD` inbox event.
  It also fails when a message follows `ITEM_CLOSED` or `REPLACED`.

## Revisit trigger

- More than 5 % of copies on the work end `DEADLINE_PASSED` over four weeks.
- Authors rarely use Request a new copy or Post these exact words after a withheld copy.

## Sources

- Kluger and DeNisi, [The effects of feedback interventions on performance](https://doi.org/10.1037/0033-2909.119.2.254), Psychological Bulletin 119(2), 1996. Feedback improved performance on average, but over one third of the feedback interventions decreased performance. Feedback can harm, so decision 6 posts no fallback text that nobody composed for this work.
