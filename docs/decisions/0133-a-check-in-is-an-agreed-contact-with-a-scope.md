# ADR 0133: A check-in is an agreed contact with a scope

**Status:** Proposed
**Date:** 2026-11-23
**Authors:** Felix T.J. Dietrich

**Builds on:** ADR 0131 (proposed in #4862, the main thread), [ADR 0132](0132-personal-records-are-written-by-the-persons-act-or-noted-from-their-own-words.md) (saves with Undo, goals)

**Relates to:**

- [ADR 0139](0139-the-contact-policy-decides-whether-hephaestus-may-interrupt-a-person-now.md): the contact policy decides each fire and its notification.
- ADR 0126 (proposed in #4863): personal-space records in Slack.
- ADR 0149 (proposed in #4868): delivery experiments.

## Context

A good human mentor comes back when they said they would.
On main, the only return is the weekly digest. It is one opt-in schedule, Monday from 09:00 to 21:00, written as a `DIGEST` run ([ADR 0082](0082-a-developer-has-one-heph-conversation-and-its-ledger-is-hephs-memory.md) decisions 19 and 21).
A person cannot agree on "check my drafts before review" or "ask me on Friday what I learned".

ADR 0120 (proposed in #4862) makes a digest a scheduled inbox item on the main thread.
Each scheduled return now needs rules: who creates it, when it fires, what happens after downtime or a time zone change, and when it stops.
A return that fires twice, fires in a burst after an outage, or goes quiet on its own costs trust.
The real constraint is that the person agreed to each return, and each agreed occurrence fires exactly once or is visibly skipped.

## Decision drivers

- Only the person's act creates a return. A model never schedules one on its own.
- Each occurrence fires once. Downtime, a restart or a time zone change never fires it twice.
- No model runs before an occurrence is due and allowed.
- An agreed return keeps its promise also when the model provider is down.
- Silence is never read as dislike. Hephaestus asks instead of guessing.
- Each check-in has its reverse: pause and resume, stop and resume.

## Considered options

1. **Keep the weekly digest as the only return.** People cannot agree on the moments that matter to them, such as a draft before review.
2. **Proactive messages that the model times.** The model decides when to come back. Timing is not checkable, it costs model calls when nothing changed, and the person never agreed to it.
3. **Scheduled tasks that pause when unanswered,** as ChatGPT tasks may pause when inactive. A check-in that the person still values goes quiet with no question. A mentor who goes silent on their own is not a mentor.
4. **A step-down after unanswered messages.** Inferred dislike makes Hephaestus timid, and the person cannot see why it changed.
5. **A check-in: an agreed contact with a scope, a stable occurrence identity and a deterministic pre-check (chosen).**

## Decision

Option 5.

1. **A check-in is an agreed contact with a scope.** The scope says what it is about, where it appears, when it fires and how it stops. The table is `check_in`.
2. **Only the person creates one.** The person uses the app, the rhythm choice at first sign-in, or a save with Undo ([ADR 0132](0132-personal-records-are-written-by-the-persons-act-or-noted-from-their-own-words.md)).
3. **Wake kinds are `AT`, `EVERY` and `ON_EVENT`.** The enum is open. `ON_PRACTICE` comes in 1.x.
4. **Each check-in has a time zone, an occurrence identity, Pause, an end and an expiry.**
   - The occurrence identity is the slot key. For `EVERY`, it is the local date without the zone.
   - A zone change applies from the next slot. One local date never fires twice.
   - Wall time follows the person's time zone through daylight-saving changes, by the `java.time` rules.
5. **An occurrence fires once.** It is a required `SCHEDULE_DUE` inbox item with a hard due time. Its key is the check-in and the slot.
   - Its deadline is the end of its grace (decision 8). Its fixed disposition is decision 9.
   - ADR 0120 (proposed in #4862) owns required items and how the sweep settles one, also a claimed one.
6. **No model runs before an occurrence is due and allowed.** The scheduler evaluates the contact policy and a deterministic pre-check first.
   - A valid empty result, Pause, lost authority and could not check are four separate dispositions.
   - A pre-check error or a failed capture is could not check. It is never a claim about the work.
   - If the contact policy allows it, such an occurrence shows a fixed "Could not check" app entry. The entry has the check-in's title and no notification.
7. **A check-in that writes words is shown by a background turn on the main thread.** The turn runs on the route of the check-in's place.
8. **The grace is 30 minutes after the due time.** Inside it, an occurrence runs as usual. A message that comes late says "This was due at 09:00".
9. **An occurrence with no disposition at the end of its grace ends as could not check** (decision 6). No model runs, and no notification goes.
   - The same holds for a slot that the scheduler finds after its grace, for example after an outage.
   - If one check-in missed several slots, only the latest gets the entry. Each earlier slot is recorded as skipped. Missed slots never fire in a burst.
10. **An event condition is pinned to a named item and condition,** such as "CI on #61". Its registration reads the current state first, so a condition that already held is not missed.
11. **Templates, and when Hephaestus offers each.**

    | Template | What it does | Offered |
    | --- | --- | --- |
    | Monday focus | Names what waits on the person this week. A pre-check skips the week when nothing waits. | At first sign-in, in the rhythm choice |
    | Check my drafts before review | An `ON_EVENT` check-in. Its turn requests the private check as a task. It is the only way that automatic draft checks start. | After a useful first check |
    | Weekly reflection | Names one change in the week's work and asks one question. It sends only when it has something useful to say. | Once, after the person's first useful interaction. Never at first sign-in. |
    | Goal follow-up | One next step about a named goal, when the active work changes materially. At most once a day for each goal. | Beside a goal |
    | Periodic review | Looks back over a quarter or a course term and asks what comes next. | Once, after the first month |

12. **The rhythm choice at first sign-in** offers "Monday focus", "Only when I ask" and "Choose later". The same screen offers notifications.
    - An offer creates nothing. The person's choice is the act that saves the check-in, with Undo ([ADR 0132](0132-personal-records-are-written-by-the-persons-act-or-noted-from-their-own-words.md) decision 11).
13. **The weekly reflection replaces the weekly digest for a person who turns it on.** It carries the digest's links to feedback already delivered. Turning it off restores the plain digest if the digest was on. Everyone else keeps the opt-in digest.
14. **Agreed check-ins stay** until the person stops them, their conditions fail, or access ends. Hephaestus never pauses, ends or slows a check-in on its own.
15. **After 3 unanswered messages from a check-in,** Hephaestus asks once, in place: "Keep this check-in?". Keep is the default. Any answer resets the count.
16. **"Less often" and "Stop this check-in" sit on every check-in message.** Pause has its reverse, Resume. Stop can be undone while the row exists.
17. **The purpose follows its record.** A closed goal ends its check-ins. A record in review sends them to `NEEDS_REVIEW`. Leaving a workspace ends its check-ins.
18. **A Slack place.** A check-in in a team workspace can take that team's Slack direct message as its place. It uses the Slack route and detail level of ADR 0123 (proposed in #4862). If the binding narrows, the place falls back to the app, with one notice.
    - It never takes a personal-space record to Slack. ADR 0126 (proposed in #4863) decision 8 owns that rule.
    - A draft-check check-in in a Slack place returns its result in the app. Slack gets only a fixed notice with an app link. ADR 0120 (proposed in #4862) owns the task routes.
19. **A delivery experiment never draws a check-in, and a scheduled turn never reads a held result.** ADR 0149 (proposed in #4868) decisions 8 and 9 own the hold and when it ends. The item card and the practice page still show a held result.

### What this changes in other ADRs

- ADR 0131 (proposed in #4862) supersedes ADR 0082. Its table lists how this ADR changes ADR 0082 decisions 19 and 21. The digest stays for a person who does not turn on the weekly reflection. For a person who does, decision 13 above applies.
- [ADR 0116](0116-a-push-is-one-web-push-message-from-the-instance.md) rule 7 gets an update on acceptance: the agreed check-in becomes a notification kind ([ADR 0139](0139-the-contact-policy-decides-whether-hephaestus-may-interrupt-a-person-now.md)).

## Consequences

- Good: Hephaestus comes back when the person agreed, and only then.
- Good: a due occurrence always ends with a message, a recorded skip or an app entry. This holds also during a model outage.
- Good: a restart, a time zone change or a daylight-saving change never fires one occurrence twice.
- Bad: moving east across the date line can skip one local date. A skip is accepted, because a duplicate costs more.
- Bad: a check-in that the person ignores keeps firing until they answer the question after 3 messages.
- Bad: the scheduler and the pre-check library are new code, with their own tests.
- Bad: the upgrade maps each digest subscription to a schedule and each `DIGEST` run to an inbox item.
- Bad: in a delivery experiment, a weekly reflection can leave out a result. The person may already have seen it on its card.

## Confirmation

Not enforced yet. The implementation adds these tests:

- `OncePerOccurrenceTest` fails when one slot of a check-in inserts two `SCHEDULE_DUE` items.
- `ZoneChangeSlotTest` fails when a zone change fires one local date twice.
- `DaylightSavingSlotTest` fails when a gap or an overlap moves a slot against the `java.time` rules.
- `NoModelBeforeDueTest` fails when a model call starts before a slot is due and allowed.
- `OverdueDispositionTest` fails when an occurrence has no disposition 30 minutes after its due time while the model breaker is open.
- `LateSlotTest` fails when a slot found after its grace runs a model or sends a notification.
- `NoBurstTest` fails when one check-in writes two overdue entries after an outage.
- `FirstSignInOfferTest` fails when the rhythm choice at first sign-in offers the weekly reflection.
- `UnansweredAsksOnceTest` fails when a check-in pauses, ends or slows after unanswered messages.
- `OnlyThePersonCreatesTest` fails when a turn creates a check-in without the person's act.
- `HeldResultOutOfScheduledTurnTest` fails when a turn that a `SCHEDULE_DUE` item started reads a held result.
- `CheckInNeverDrawnTest` fails when a check-in fire gets a draw.

## Revisit trigger

- Over four weeks, fewer than half of the people who chose the rhythm keep it after 3 fires.
- People ask for a check-in that fires on a practice condition. Then `ON_PRACTICE` moves forward from 1.x.
- More than 1 % of occurrences end by their time-based disposition.

## Sources

- Oracle, [`ZonedDateTime`](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/time/ZonedDateTime.html). In a gap, "the local date-time is adjusted to be later by the length of the gap". In an overlap, the earlier offset applies. Decision 4 follows these rules.
- OpenAI, [Scheduled tasks in ChatGPT](https://help.openai.com/en/articles/10291617-scheduled-tasks-in-chatgpt). "A task may pause if it is inactive." Decision 14 declines this: a check-in never pauses on its own, and decision 15 asks instead.
