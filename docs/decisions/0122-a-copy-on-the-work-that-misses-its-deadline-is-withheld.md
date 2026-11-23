# ADR 0122: A copy on the work that misses its deadline is withheld

**Status:** Proposed
**Date:** 2026-11-23
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0086](0086-a-practice-review-stages-seals-then-composes-and-each-stage-records-why-it-stops.md) decision 14. [ADR 0079](0079-one-agent-system.md) § Update — 2026-11-11: compose deadlines. [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) decision 10.

**Builds on:** [ADR 0120](0120-a-conversation-thread-has-one-inbox-and-runs-one-turn-at-a-time.md) (custody and the commit rule), [ADR 0121](0121-a-composer-on-a-shared-thread-writes-feedback-on-the-work-and-cannot-read-private-data.md) (the composer and the typed commands)

**Relates to:** [ADR 0029](0029-measurement-intervention-seam-and-channel-levels.md) (no measurement-text fallback), [ADR 0123](0123-slack-is-a-channel-into-the-one-conversation.md) (the Slack delivery keys)

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

A provider write can also end with no answer.
GitHub and GitLab comments take no idempotency key, so a blind retry can post twice and notify everyone twice.

## Decision drivers

- Feedback earns trust or it is not sent. A late copy on moved work is worse than no copy.
- A delivery fact is never reported as missing evidence or as a fact about the review.
- The person can still get the feedback on the work, by an act that they confirm.
- No external write repeats, also after a crash, a restore or a timeout.

## Considered options

1. **A fixed fallback copy after a short deadline.** An earlier internal design rendered the required problems from admitted data after 5 minutes. A queue delay then chooses standard wording for good. Main never shipped it, and ADR 0086 declines a fixed template.
2. **No deadline.** The copy posts when the composer finishes. After an outage it can post hours late, on an old version or on merged work.
3. **Keep main's outage window as the only bound.** It starts before the seal and mixes measurement with composition. An outage closes the review as Partial, which is a provider fact stated as a review fact.
4. **Retry an uncertain write.** A timeout can hide a write that succeeded. A retry then posts a second copy that notifies everyone.
5. **A seal deadline that withholds the copy, one delivery key for each write, and readback, not a retry (chosen).**

## Decision

Option 5.

1. **The seal starts the clock.** The seal inserts the feedback row for the work in state `PENDING`, with `deadline_at` 2 hours after the seal.
2. **A staged copy must meet its create predicate.** The composer's commit stages the copy only if the database time is before `deadline_at`. The item must be open, the head must be the reviewed version, and the generation must be current.
3. **Earlier withholding.** A merge or a close withholds every open proposal of the item as `ITEM_CLOSED`. A newer version or a newer eligible seal ends it as `REPLACED`.
4. **The sweep withholds a late copy.** It settles each unstarted Automatic proposal past `deadline_at` as `WITHHELD` with `DEADLINE_PASSED`.
   - This also happens while the executor is stopped. Its actions end `CANCELLED`.
   - The first-write transaction checks the predicate again, so a queued send cannot post late.
5. **Approve first keeps its own clock.** Staging must happen before `deadline_at`. After that, main's approval expiry applies to the approved text.
6. **No template and no late post.** Hephaestus posts no template comment, no "review failed" comment and no automatic late copy. The fixed renderer for graded work ([ADR 0121](0121-a-composer-on-a-shared-thread-writes-feedback-on-the-work-and-cannot-read-private-data.md) decision 10) is a workspace mode, not a fallback.
7. **The review stays Complete.** `DEADLINE_PASSED` is a delivery reason on the destination. Nothing is reported as missing evidence.
8. **The person learns it privately.** The withholding transaction inserts a `DELIVERY_WITHHELD` item into the author's main thread.
   - If a Critical or Major problem was in the eligible set, a background turn tells the author.
   - The message can carry the card controls of decision 9.
9. **Two direct controls on the item card.** Both need no conversation turn and use the preview and confirmation of ADR 0121.
   - **Request a new copy.** The proposal row returns to `PENDING`, with the next generation and a new deadline. The composer writes a fresh copy.
   - **Post these exact words.** Only in Automatic mode, and only for a composed body that was withheld before its write started. The preview shows the body. The confirmation takes the place of the deadline in the create predicate.
10. **One delivery key for each external write.** Each operation has one `delivery_key` from source facts, unique in the workspace.
    - The external marker carries it. Internal row IDs never appear in a marker.
    - Replay, readback and restore all use this key.
    - A copy on the work keys on provider target, account, destination, source obligation, operation, generation and subeffect.
    - A reply keys on the native reply. A retract keys on the native copy. [ADR 0123](0123-slack-is-a-channel-into-the-one-conversation.md) gives the Slack keys.
11. **A started write is never retried blindly.**
    - One transaction checks the predicate and marks the action and its native subeffects started.
    - A failure before that transaction is retried. After it, only readback decides.
    - An ordering key moves past an uncertain write only after proof of presence or proof of non-acceptance.
    - An explicit reconciliation by the person or an operator also moves it. A refusal, such as a rate-limit answer, proves non-acceptance.
    - A timeout, an incomplete page or a missing marker proves nothing.
12. **Restore.** Egress stays held until restore reads the markers at each provider. Restore rebuilds the generations and the Slack coverage from them. An effect whose identity or absence it cannot prove is suppressed and listed.

### Difference from main

| Fact | Main | This ADR |
| --- | --- | --- |
| Clock start | The review's first admission, or its submission | The seal |
| What the clock bounds | Measurement and composition together | Only the copy on the work |
| Review status after an outage | Partial, with `PROVIDER_UNAVAILABLE` or `COMPOSITION_UNCONFIRMED` | Complete. The destination carries `DEADLINE_PASSED`. |
| What the person gets | The withheld state only | A private message and two controls, when a Critical or Major problem was withheld |
| Fallback text | None | None |

Example: a review waits 20 minutes for capacity and seals 25 minutes after submission. Main's clock starts at minute 20, and this ADR's clock starts at minute 25.

### What this changes in other ADRs

- [ADR 0086](0086-a-practice-review-stages-seals-then-composes-and-each-stage-records-why-it-stops.md) decision 14 changes. A composition stop no longer closes the review as Partial with `COMPOSITION_UNCONFIRMED`. The destination records its reason, and the review stays Complete.
- The model outage window keeps bounding measurement. It no longer bounds composition.
- [ADR 0079](0079-one-agent-system.md) § Update — 2026-11-11 gives each composition run a deadline. A composer turn keeps its turn bounds, but only `deadline_at` withholds the copy.
- [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) decision 10 stays. Each action keeps its internal idempotency key, and it gains the `delivery_key` of decision 10.

## Consequences

- Good: no copy appears on work that moved on or after a long outage.
- Good: the review result stays true when delivery fails. A provider outage is never a statement about the work.
- Good: the author can still put the feedback on the work, with one confirmed act.
- Good: no write repeats after a crash, a restore or a timeout.
- Bad: during an outage of more than 2 hours, the team sees no copy unless the author acts.
- Bad: an uncertain write blocks newer writes to the same target until readback or reconciliation.
- Bad: the 2-hour value is a choice, not a measurement.

## Confirmation

Not enforced yet. The implementation adds these tests:

- `ExpiredReadyWithStoppedExecutorTest` fails when a `READY` Automatic copy past its deadline is not withheld while the executor is stopped.
- `FirstWritePredicatePerOperationTest` fails when an operation uses another operation's predicate.
- `CrashBetweenStartAndCopyFenceTest` fails when a crash leaves the action or a subeffect started alone.
- `UncertainLaneWaitsForProofTest` fails when a newer generation passes an uncertain write without proof.
- `RestoreTwoGenerationsTest` fails when a restore reuses a marker that exists.
- `RequestNewCopyFromCardTest` and `PostExactWordsTest` fail when a control needs a turn or changes the body.

## Revisit trigger

- More than 5 % of copies on the work end `DEADLINE_PASSED` over four weeks.
- Authors rarely use Request a new copy or Post these exact words after a withheld copy.
- A provider adds an idempotency key for comments or reviews. Then a started write can retry with that key.

## Sources

- Brandur Leach, [Implementing Stripe-like idempotency keys in Postgres](https://brandur.org/idempotency-keys). A retry is safe only when the remote call "is an idempotent operation or it supports an idempotency key". Provider comments do not, so decision 11 reads back instead.
- Chris Richardson, [Transactional outbox](https://microservices.io/patterns/data/transactional-outbox.html). The message relay "might publish a message more than once". Decisions 10 and 11 give each write one key and read back, so a repeat never reaches the provider twice.
