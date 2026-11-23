# ADR 0139: The contact policy decides whether Hephaestus may interrupt a person now

**Status:** Proposed
**Date:** 2026-11-23
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0116](0116-a-push-is-one-web-push-message-from-the-instance.md) rule 6 (what the client shows) and rule 7 (the kinds and quiet hours)

**Builds on:** [ADR 0131](0131-a-person-has-one-conversation-in-each-workspace-answers-stream-and-observations-render-as-cards.md) (messages from Hephaestus), [ADR 0133](0133-a-check-in-is-an-agreed-contact-with-a-scope.md) (agreed contact), [ADR 0116](0116-a-push-is-one-web-push-message-from-the-instance.md) (push)

## Context

Hephaestus now starts messages: results, check-ins and replies on the work.
Each one can also become a notification on a phone, an email or a Slack message.
Too many make Hephaestus noise, and people turn it off.
Too few make it feel absent, and a result that the person waits for arrives late.

An entry in the person's own conversation does not interrupt.
The person reads it when they look.
Only a notification interrupts.
An earlier internal design capped messages in the app, and the cap made Hephaestus feel absent while it protected nobody.

ADR 0120 (proposed in #4862) puts a start policy in code and lets gates hold items, never threads.
ADR 0123 (proposed in #4862) says that the contact policy chooses the app or Slack for unpinned background input at the claim.
This ADR is that policy.
The real constraint is one pure function that every boundary calls, so that a pause or a quiet hour holds everywhere.

## Decision drivers

- A result that the person asked for arrives at once, at any hour.
- An agreed contact arrives at its slot.
- An unprompted notification is rare and worth opening.
- Code decides contact. A model never does.
- The instance and the workspace can only make Hephaestus quieter. The person can make it quieter still.
- A contact control never cuts the person's own answer.

## Considered options

1. **A cap on messages in the app.** One or three messages a day in the conversation. A result waits for the next day, and Hephaestus feels absent. The conversation is not an interruption, so the cap protects nobody.
2. **The model decides when to reach out.** The decision is not checkable, it costs a model call, and a prompt in the work can push it.
3. **Batch every notification into fixed windows,** such as three a day. A requested result waits hours. The person asked for it and waits now.
4. **Device quiet hours only,** as [ADR 0116](0116-a-push-is-one-web-push-message-from-the-instance.md) rule 7 states. Email and Slack messages have no device schedule in Hephaestus. A person who sets quiet hours once expects them on every channel.
5. **Lower contact after unanswered messages.** Silence is read as dislike, and Hephaestus becomes timid without saying why.
6. **One pure contact policy that caps only notifications (chosen).**

## Decision

Option 6.

1. **The contact policy is a pure function with no model.** It answers whether Hephaestus may reach this person now, and on which channel. No tool writes its inputs.
2. **It decides three things.** These are message eligibility, notification eligibility and the delivery time.
3. **Contact kinds.**

   | Kind | Started by | Never blocked by |
   | --- | --- | --- |
   | `ANSWER` | The person's message | Initiative, caps, quiet hours, pause, mutes |
   | `REQUESTED_RESULT` | A check or task that the person asked for | Initiative, caps, quiet hours. Pause removes only its notification. |
   | `AGREED` | A check-in fire or a goal follow-up | Initiative caps |
   | `UNPROMPTED` | Observations, discussion and results outside an agreement | Nothing |

   Child work inherits the contact kind of its parent and cannot widen it.
4. **Feedback on the work is not contact.** It follows the work's own settings. A person's pause never blocks it.
5. **In the app, Hephaestus is present.** A result is available at once on its item card. The start policy of ADR 0120 decides in code whether an event starts a turn.
6. **An unprompted message needs a reason in code.** The reasons are a new problem, a material change on an agreed goal, or an awaited result. A copy on the work withheld with a Critical or Major problem also counts (ADR 0122, proposed in #4862). Else no message is written.
7. **Gates hold optional background items, never threads.**
   - Idle only holds them until 2 minutes after the person's last message.
   - Active hours hold them to the next working period.
   - A requested result and an agreed check-in are never held.
8. **Off the app, notifications are worth opening.**

   | Contact kind | Push rule |
   | --- | --- |
   | Requested result | At once, at any hour |
   | Agreed contact | At its slot, outside quiet hours, unless the person set the slot inside them on the card |
   | Unprompted update | Sometimes (default): at most 1 a day. Often: at most 3 a day, at least 3 hours apart. Never in quiet hours. |

9. **Quiet hours are kept by the server,** 22:00 to 08:00 by default. They delay only the notification. The message in the conversation never waits.
10. **No push while the person is active in that workspace.** Active means a client in the foreground with a live connection in the last 2 minutes.
11. **At most one notification for each committed message.** A background turn that takes several results writes one message, so they become one notification. Code decides the notification at commit, as an effect.
12. **The notification text is the notice line, never the message summary.** The push payload stays ids only. On arrival, the service worker fetches the notice line with the person's session. On any failure it shows the kind's fixed text.
    - The notice line is registry copy with parameters, such as "Check ready: 1 problem on PR #42". It is never model text.
    - The fetch never refreshes a credential.
    - "Hide content in notifications" keeps the fixed text.
13. **The channel.** For an unpinned background batch, the policy chooses `APP` or `SLACK_DM` among the starter's authorized routes at the claim. A starter that only the app may show gets `APP`.
14. **The person stays in control.**
    - A one-step pause stops every contact that is not an answer.
    - "Not now" mutes that topic in that workspace for a stated time.
    - "More like this" and "Fewer of these" sit on every message that Hephaestus starts. They tune the reason check and topic weights, never past rule 6 or the daily limit.
    - The person can mute a workspace, a practice or a card.
15. **Dedupe works on the problem and its version,** never on the practice across workspaces.
16. **Rules narrow, never widen.** The instance and the workspace may turn off messages that Hephaestus starts. The person's controls narrow further. A rule that cannot be evaluated removes the effect.
17. **Silence is never read as dislike.** No rule lowers contact because messages went unanswered. [ADR 0133](0133-a-check-in-is-an-agreed-contact-with-a-scope.md) owns the one question that Hephaestus asks instead.
18. **One order at each boundary.** The authorization recheck runs first, then routing and limits, then the contact policy.
19. **The policy runs at dispatch, at the effect commit and again at send.** A pause or a Not now stops a queued contact. A notification inside quiet hours waits.
20. **A contact change is not an authority change.** Pause, quiet hours, Not now, Less often, mute and stop never bump an authorization generation. They never stop a streaming answer or reset a cache.
21. **Silent Mode** stops web push too, as it stops email and provider writes.

### What this changes in other ADRs

- [ADR 0116](0116-a-push-is-one-web-push-message-from-the-instance.md) rule 6 keeps its payload: ids only, no text. Only what the client shows changes: it may fetch the notice line, and else shows the fixed text.
- ADR 0116 rule 7 changes. The server keeps quiet hours for unrequested notifications. New kinds join under rule 7: the requested result, the agreed check-in and the unprompted update.
- ADR 0116 rule 8 stays. The send check now also runs the contact policy.
- ADR 0120 (proposed in #4862) decision 4 stays. Its gates are the gates of rule 7 above.
- ADR 0123 (proposed in #4862) decision 3 points to this ADR for the route of unpinned background input.

## Consequences

- Good: a requested result arrives at once, and an agreed contact arrives at its slot.
- Good: one pause and one set of quiet hours hold on every channel.
- Good: the policy is a pure function, so a table test covers each kind and rule.
- Bad: a person who works at night must move quiet hours, or unprompted notifications wait until morning.
- Bad: the notice-line fetch is one more request on each push. A failed fetch shows only the fixed text.
- Bad: the reason check can miss an update that the person would want. "More like this" tunes it only within its three reasons.
- Bad: Hephaestus ignores device Focus settings it cannot read. A device rule can still hide what the server sends.

## Confirmation

Not enforced yet. The implementation adds these tests:

- `ContactPolicyTableTest` fails when a contact kind, rule and state give an effect that the table does not allow.
- `RequestedResultAnyHourTest` fails when quiet hours delay a requested result's notification.
- `UnpromptedSlotsTest` fails when Sometimes sends 2 unprompted notifications in a day, or Often sends 2 within 3 hours.
- `ActiveNoPushTest` fails when a push goes to a person who is active in that workspace.
- `ContactChangeNoBumpTest` fails when a pause bumps an authorization generation or stops a streaming answer.
- `SendRecheckTest` fails when a contact queued before a pause is sent after it.
- `AppOnlyStarterRouteTest` fails when an app-only starter is routed to Slack.

## Revisit trigger

- Over four weeks, more than a fifth of people who allowed notifications turn them off.
- People report that a result they waited for arrived late.
- Browsers expose a device quiet-hours state to web apps.

## Sources

- W3C, [Push API](https://www.w3.org/TR/push-api/). Push messages "are delivered to a Service Worker". The worker can use the message "to update local state or display a notification". So the service worker can fetch the notice line first.
- Slack, [Pause your Slack notifications](https://slack.com/help/articles/214908388-Pause-notifications-with-Do-Not-Disturb). A person can "set a notification schedule" for routine times. Slack keeps it on its server, so it holds on every device. Decision 9 keeps quiet hours on the server too.
