# ADR 0160: A sealed review hands off to the mentor

**Status:** Accepted
**Date:** 2026-11-27
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0086](0086-a-practice-review-stages-seals-then-composes-and-each-stage-records-why-it-stops.md): its compose stage, decisions 11 to 14 and the private destinations of decision 16. [ADR 0121](0121-a-composer-on-a-shared-thread-writes-feedback-on-the-work-and-cannot-read-private-data.md): the cutover steps.

**Builds on:** [ADR 0158](0158-the-mentor-orders-commitments-per-person-and-answers-per-request.md) (the mentor runtime), [ADR 0151](0151-each-external-write-has-one-delivery-key-and-is-read-back-never-retried-blindly.md) (delivery identity and readback)

**Relates to:** [ADR 0139](0139-the-contact-policy-decides-whether-hephaestus-may-interrupt-a-person-now.md) (contact)

**Amended by:** [ADR 0165](0165-a-team-reviews-immutable-submitted-versions-within-its-own-scope.md) (proposed: a `TEAM` producer for reviews of team items, and team guidance in the `TEAM` audience)

## Context

ADR 0086 ends every practice review with a compose stage.
The seal continues the run as `REVIEW_COMPOSE`, which writes the copy on the work, a practice-page card and a conversation note.
That stage decides what to say, where to say it, and whether to say anything.
It runs once for each review and keeps nothing between reviews.

Practice Review stays the assessor. It decides what the work shows.
The mentor of ADR 0158 now owns what to say, whether and when to surface it, and the follow-through.
A person needs lasting advice about a practice, not a new card for each review.
A work comment must still reach authors who have no Hephaestus account.

## Decision drivers

- Exactly one producer writes the feedback of a sealed review, also while a flag changes.
- A work comment never reads private data (ADR 0121).
- Advice is private to its owner and never copies evidence.
- Silence is a valid outcome and is recorded with its reason.
- Every step of the cutover can roll back, and no review gets a second send.

## Considered options

1. **Keep the compose stage and add advice to it.** One run per review would still decide everything, with no memory of earlier advice and no follow-through.
2. **Run the mentor beside `REVIEW_COMPOSE`.** Two producers would write for one review and would need reconciliation in both commit orders.
3. **One producer chosen at seal, and the mentor decides (chosen).**

## Decision

Option 3.

1. **The seal hands off. It never composes.**
   - In the seal transaction, the server stores one producer on the review job: `MENTOR`, `WORK_PLACE` or `LEGACY`.
   - `MENTOR` applies when the workspace setting is MENTOR and the reviewed person is a linked, active, consenting account.
   - `WORK_PLACE` applies in every other case when the setting is MENTOR: an unlinked, inactive or non-consenting author. It also applies when an erasure or a write fence refuses the owner's mailbox insert. The reason is recorded.
   - `LEGACY` keeps `REVIEW_COMPOSE` during the transition only.
   - The choice is made once and never made again. A flag change affects only later seals. A server without the column takes `LEGACY`.
2. **A mentor review is a typed mailbox event.**
   - With `MENTOR`, the seal writes one `REVIEW_SEALED` reference for the reviewed account, with IDs only. It does not continue to `REVIEW_COMPOSE`.
   - The mentor's intake refuses a review whose producer is not `MENTOR`.
3. **The mentor decides three independent outcomes.** Any combination is valid, including silence.
   - **A fix on the work.** The mentor starts a bounded task: the work-place composer, built from the review's scoped handoff (ADR 0121). It reads public inputs only.
     - The result goes through the existing feedback gate, its approval and its delivery.
     - The delivery keeps its key and readback (ADR 0151).
     - The server, not the model, owns the audience and the approval policy.
     - A handoff that expires before the task starts records silence for this outcome.
   - **Advice on the Practice profile.** See § Advice on the Practice profile.
   - **Contact or silence.** The contact policy decides after authorization, and the default is silence. There are no more per-review conversation notes.
4. **`WORK_PLACE` reviews get only the fix on the work.** They have no advice, no contact and no account-owned rows. So `REVIEW_COMPOSE` can be removed without losing work comments for unlinked authors.
5. **What is retired, kept and reused.**

   | Item | Decision |
   | --- | --- |
   | `REVIEW_COMPOSE` as a producer | Retired after the drain |
   | The private practice-page and conversation sessions | Retired |
   | Old practice-page cards | Read-only history. No new card is written |
   | The scoped handoff and the work-place composer | Kept. The composer is the mentor's task on the work |
   | The feedback gate, approval, send queue, text policy, formatter, edit, retract and thread resolver | Kept |

6. **The transition rolls in five steps.**
   1. Expand: add the producer column, the mailbox kind and the advice table. The mentor's review handler stays dark.
   2. Shadow: on `LEGACY` seals, the handler may run with its effects held. It writes no advice and sends nothing.
   3. Flip one workspace to `MENTOR`. A composition already started finishes under its own keys, and its approvals stay decidable.
   4. Make `MENTOR` the default once the 1.0 gate passes the review journey.
   5. Contract after the drain horizon: remove the `REVIEW_COMPOSE` start and the private sessions.

### Advice on the Practice profile

The record is **advice**: lasting feedback for one developer about one practice.
Practice guidance keeps its meaning: the practice's own text, such as Why it matters.
The product says "Advice from Hephaestus". The code uses `PracticeAdvice`.

1. **One advice entry for each workspace, developer and practice.**
   - The entry is current or retired, and it has a list of revisions.
   - A revision is immutable. It holds the text, the review that caused it and its sources.
   - An update adds a revision. It never changes an earlier revision.
   - The mentor's review handler writes advice. It writes only for a review whose producer is MENTOR.
   - One review writes at most one revision to an entry. A retry with the same content returns the first result.
2. **A revision holds references to evidence, never copies.**
   - The sources of a revision are the observations that the model saw for it.
   - The model never sees earlier advice text. Each revision comes from current evidence only.
   - So the sources name every observation behind the text, and no revision names another revision.
   - A citation is a source that the text relies on. A revision cites at least one observation of its practice.
   - Every source belongs to the same developer and workspace as its entry. Foreign keys enforce this.
   - The citations pass the composition check of `FeedbackGate` for the practice page.
   - That check refuses when feedback is Off, an observation is marked incorrect, or a source changed.
   - Advice needs no admin approval. Approval applies only to feedback on the work.
3. **Update and retire never touch the evidence.**
   - Both operations write only advice rows. Observations, problems, evidence and feedback stay byte-identical.
   - Retire sets the state and a reason: Resolved or No longer applies.
   - The owner can dismiss advice and restore it. A dismissal is separate from the mentor's state.
   - After a dismissal, a new revision must cite a new item that was observed after the dismissal.
   - Each write names the latest revision that its writer saw. The server refuses a write against a newer revision.
4. **The server computes the recurrence claim from rows at read time.**
   - The read counts distinct items among the cited observations that are current and Not met.
   - The view names a count, such as "in 3 pull requests", only for two or more items.
   - One review can give advice. It can never give a pattern.
   - The write refuses text that counts work or uses a fixed list of recurrence words, such as "again".
5. **Advice lasts no longer than its evidence.**
   - Each transaction that deletes an observation first removes the text of each revision with that source.
   - This applies to erasure, source withdrawal and retention.
   - The update reads the sources present at delete time. It also reaches revisions written after an erasure was admitted.
   - A write that uses a deleted observation is refused.
   - Each delete path redacts in an earlier release than any code that can write advice.
   - A stale revision is never shown, exported with text, or given to the model as earlier advice.
   - The read also hides a revision when one of its sources is gone.
   - An older revision never replaces a hidden latest revision.
   - An erasure of the developer deletes their advice, also advice written after the request was previewed.
   - Advice never keeps an observation alive.
6. **Only the owner reads advice.**
   - The developer reads their advice on their own Practice profile. The endpoint takes no person parameter.
   - No admin view, View as user or other member shows advice text.
   - The admin's person-data page shows only a row count for each advice store.
   - The work-place composer, shared work and Slack DM prompts never receive advice.
   - Another person's data export never contains the developer's advice.
   - The developer's own data delivery contains their advice. Only the developer downloads it.
   - The audience is `OWNER`. `TEAM` is a reserved enum value. Every read refuses it, and no write accepts an audience.
7. **Old practice-page cards are read-only history.**
   - Existing cards stay visible with their responses and closure.
   - A review that the mentor handles writes no new card.

### What this changes in other ADRs

- ADR 0086: decisions 1 to 4 replace its compose stage. That is decisions 11 to 14, and the private destinations of decision 16. Staging, sealing and the recorded stop reasons stay.
- ADR 0121: the cutover's producer is `MENTOR` or `WORK_PLACE`, not `COMPOSER`. The work-place composer, its scope and its boundary stay.
- ADR 0061 is already superseded by ADR 0086.

## Consequences

- Good: one producer per review, chosen once, so a flag change never sends twice.
- Good: advice lasts across reviews, and the person sees one entry per practice.
- Good: unlinked authors still get the fix on their work.
- Bad: the cutover runs two producers side by side until the legacy reviews drain.
- Bad: the advice table needs its retention, erasure and export.

## Confirmation

Not enforced. The mentor's units RV1 to RV3 add these checks beside the code, and the review of each unit catches a missing one:

- A seal under `LEGACY` makes one `REVIEW_COMPOSE` run and no mailbox event. A seal under `MENTOR` makes one mailbox event and no `REVIEW_COMPOSE` run.
- A flag change between two seals of the same work gives each seal one producer. A replayed seal makes no second event.
- An unlinked author's seal records `WORK_PLACE` with its reason, and writes no account-owned row.
- A composition already started before the flip delivers once under its key. The mentor never acts on that review.
- An update and a retire leave every observation, problem, evidence and feedback row byte-identical.
- Advice from one review shows no recurrence claim. Two items show a count that the read computes from rows.
- An erased source removes the text of every revision with that source.
- A revision written after the erasure was admitted, from evidence that the erasure deletes, is also removed.
- The read for the mentor never returns advice text.
- Retention removes the text of a revision before it deletes the revision's sources.
- A write that cites another developer's observation or an erased observation is refused and writes nothing.
- A write against a newer revision is refused. A retry for the same review writes one revision.
- After an erased citation, the mentor can still revise or retire the entry.
- The developer's erasure deletes advice written after the preview.
- An admin, View as user and another member get no advice text. A `TEAM` read returns nothing.
- Another person's data export contains no advice canary.
- The work-place composer's provider request contains no advice canary.

## Revisit trigger

- A review gets two sends, or none, across a flag change.
- People ask for advice that a team can see.

## Sources

- OpenAI, [Tasks and memory](https://learn.chatgpt.com/docs/dots/tasks-and-memory). A request can start work that finishes later, and the assistant keeps relevant continuity. This gives decision 3's task and the lasting advice.

## Update — 2026-11-27: who counts as consenting for `MENTOR`

Decision 1 gives `MENTOR` to a consenting author. For 1.0 there is no separate mentor consent record. At seal, the author consents when all of these hold:

- the author resolves to a linked, active account
- practice feedback delivery is on for them
- their AI choice is not `NO_AI`
- the person-data write fence admits them, with no processing restriction and no erasure

This is the basis under which `REVIEW_COMPOSE` already writes private practice-page feedback, so private advice does not widen processing.
Contact keeps its own permission through the contact policy.
Personal context in Slack keeps its explicit grant (ADR 0161).
Neither follows from this rule.
If people need an explicit mentor opt-in, a later update adds it through a consent port.

## Update — 2026-11-27: the AI choice must be explicit

The update above admits an author whose AI choice is empty.
Practice feedback delivery is on by default, so a person who never chose anything counts as consenting.
That is not a choice the person made.

For `MENTOR`, the AI choice must now be explicit: it is set, and it is not `NO_AI`.
An author with no AI choice gets `WORK_PLACE`, the same as an author who chose `NO_AI`.
The other three conditions stay.

Staging uses the same rule.
Its synthetic accounts set their AI choice, so the staging proof checks the production rule.
