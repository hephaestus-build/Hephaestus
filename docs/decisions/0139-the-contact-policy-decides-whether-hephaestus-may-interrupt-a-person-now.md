# ADR 0139: The contact policy decides whether Hephaestus may interrupt a person now

**Status:** Accepted
**Date:** 2026-11-24
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0116](0116-a-push-is-one-web-push-message-from-the-instance.md) rule 6 (what the client shows) and rule 7 (the kinds and quiet hours)

**Relates to:**

- ADR 0122 (proposed in #4862): the withheld copy on the work.
- ADR 0134 (proposed in #4863): the order at each boundary.
- ADR 0142 (proposed in #4868): the qualification for unprompted guidance.
- ADR 0149 (proposed in #4868): delivery experiments.

**Builds on:** ADR 0131 (proposed in #4862, messages from Hephaestus), [ADR 0133](0133-a-check-in-is-an-agreed-contact-with-a-scope.md) (agreed contact), [ADR 0116](0116-a-push-is-one-web-push-message-from-the-instance.md) (push)

## Context

Hephaestus now starts messages: results, check-ins and replies on the work.
Each one can also become a notification on a phone, an email or a Slack message.
Too many make Hephaestus noise, and people turn it off.
Too few make it feel absent, and a result that the person waits for arrives late.

An entry in the person's own conversation does not interrupt.
The person reads it when they look.
Only a notification interrupts.

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
3. **One table gives each contact kind and its notification.**

   | Kind | Started by | Notification | Under Pause | In quiet hours | Initiative level |
   | --- | --- | --- | --- | --- | --- |
   | `ANSWER` | The person's message | 30 seconds after the turn ends, if the answer is unread | No effect | No effect | No effect |
   | `REQUESTED_RESULT` | A check or task that the person asked for | At once | The message comes. Only its notification goes. | No effect | No effect |
   | `AGREED` | A check-in fire or a goal follow-up | At its slot | The occurrence is skipped, with its disposition | The notification waits, unless the person set the slot inside quiet hours | No effect |
   | `UNPROMPTED` | A reason of rule 7 | Under the daily limit | No message and no notification | The notification waits | It sets the daily limit |

   - The answer's notification is the `CONVERSATION_ANSWER` trigger of [ADR 0116](0116-a-push-is-one-web-push-message-from-the-instance.md) rule 7. This ADR does not replace it.
   - No kind pushes while the person is active in that workspace. Active means a client in the foreground with a live connection in the last 2 minutes.
   - Child work inherits the contact kind of its parent and cannot widen it.
4. **The initiative level caps only unprompted notifications.** Requested and agreed contact are exempt. Messages in the app are never capped.
   - **Only when agreed** allows no unprompted notification.
   - **Sometimes**, the default, allows at most 1 a day.
   - **Often** allows at most 3 a day, at least 3 hours apart.
   - **Lighter touch until a date** sets the level to Only when agreed until that date. The person sets it on a check-in message. It ends at the date, or with Undo.
5. **Feedback on the work is not contact.** It follows the work's own settings. A person's pause never blocks it. Decision 18 still records each decision to post it, as a delivery decision.
6. **In the app, Hephaestus is present.** A result is available at once on its item card. The start policy of ADR 0120 decides in code whether an event starts a turn.
7. **An unprompted message needs a reason in code.** Else no message is written.
   - The reasons are a new admitted problem, a material change on an agreed goal, or an awaited result.
   - A new admitted problem counts only when its practice version and model hold a current qualification. ADR 0142 (proposed in #4868) owns it.
   - The policy checks the qualification before any draw. A missing or stale qualification keeps the message silent.
   - A further reason is a withheld copy on the work with a Critical or Major problem. ADR 0122 (proposed in #4862) decision 8 names the withholding reasons.
   - ADR 0122 always records the withholding as a `DELIVERY_WITHHELD` inbox event, except for a silent draw. Only the message is contact, so a pause or a mute stops it.
   - An unprompted message never cites a result that a delivery experiment holds. ADR 0149 (proposed in #4868) decision 8 owns the hold.
8. **Gates hold optional background items, never threads.**
   - Idle only holds them until 2 minutes after the person's last message.
   - Active hours hold them to the next working period.
   - A requested result and an agreed check-in are never held.
9. **Quiet hours are kept by the server,** 22:00 to 08:00 by default. They delay only the notification. The message in the conversation never waits.
10. **At most one notification for each committed message.** A background turn that takes several results writes one message, so they become one notification. Code decides the notification at commit, as an effect.
    - On a private thread, a background batch holds items of one contact kind. ADR 0120 decision 7 takes compatible items up to the bound of the route. Items of another kind are not compatible.
      A composer turn on a shared thread has no contact kind (decision 5). This rule does not apply to it.
      So rule 3 applies to each message as a whole, at the commit and at send, on every channel.
    - Example: a Pause before the commit stops an unprompted batch. Code writes no message, and each item gets its disposition.
      A requested result is in its own batch, so it still arrives.
    - When items of several kinds wait, a claim takes the kinds in this order:
      1. `REQUESTED_RESULT`
      2. `AGREED`
      3. `UNPROMPTED`
    - The start policy of ADR 0120 decision 4 decides if a waiting item can start a turn now.
      When a turn ends and such an item waits, the next claim starts at once. It does not wait for a new event.
11. **The notification text is the notice line, never the message summary.** The push payload stays ids only. On arrival, the service worker fetches the notice line with the person's session. On any failure it shows the kind's fixed text.
    - The notice line is registry copy with parameters, such as "Check ready: 1 problem on PR #42". It is never model text.
    - The fetch never refreshes a credential.
    - "Hide content in notifications" keeps the fixed text.
12. **The channel.** For an unpinned background batch, the policy chooses `APP` or `SLACK_DM` at the claim. It chooses among the routes that every item of the batch may take. An item that only the app may show gets `APP`.
    - A Slack direct message that Hephaestus starts is its one interrupting effect. No push or email goes for the same message.
    - At its send, that Slack message follows its kind's notification rules: Pause, mute, quiet hours and the daily limit.
    - Its entry in the conversation commits either way. A held or skipped Slack message never holds or removes the entry.
    - An answer to the person's own Slack message is not started by Hephaestus. It posts at once.
    - Some task kinds keep their result in the app. Such a result reaches Slack only as a fixed notice with an app link. ADR 0120 (proposed in #4862) owns the task routes.
    - No escalation. A message that is unseen on one channel never goes out again on another channel.
13. **The person stays in control.**
    - A one-step pause stops every message and notification that Hephaestus starts. Rule 3 gives its effect on each kind.
    - "Not now" mutes that topic in that workspace for a stated time.
    - "More like this" and "Fewer of these" sit on every message that Hephaestus starts. They tune the reason check and topic weights, never past rule 7 or the daily limit.
    - The person can mute a workspace, a practice or a card.
14. **Dedupe works on the problem and its version,** never on the practice across workspaces.
15. **Rules narrow, never widen.** The instance and the workspace may turn off messages that Hephaestus starts. The person's controls narrow further. A rule that cannot be evaluated removes the effect.
16. **Silence is never read as dislike.** No rule lowers contact because messages went unanswered. [ADR 0133](0133-a-check-in-is-an-agreed-contact-with-a-scope.md) owns the one question that Hephaestus asks instead.
17. **The policy runs at dispatch, at the effect commit and again at send.** A pause or a Not now stops a queued contact. A notification inside quiet hours waits. At send, a notification whose message the person already saw is not sent.
    ADR 0134 (proposed in #4863) owns the order at each boundary. It also decides that a contact change is not an authority change.
18. **Every decision to reach a person is recorded as a delivery decision.** Code writes one `delivery_decision` row at each such decision, before any model runs.
    - A copy on the work: at the seal, in its transaction.
    - An unprompted message: at the start policy, when the inbox item is inserted.
    - An agreed check-in, a requested result and a stepping-back message: recorded too.
    - An answer to a message is not a decision to contact. It writes no row.
    - A row holds its kind: `COPY_ON_THE_WORK` or a contact kind of decision 3.
      It holds the practices, the item, the version and the severity.
      It also holds the policy's result and the assignment: `SPOKE`, `POLICY_SILENCE` or `STUDY_SILENCE`.
    - The realized action, the provider delivery and the exposure are separate columns. ADR 0149 (proposed in #4868) owns them and their final states.
    - The table is workspace-scoped and append-only. An update may only fill an empty outcome column. Account erasure deletes the person's rows, and every row expires after 400 days.
    - The table exists so that every silence has a reason that code wrote. No product or staff surface shows a person's rows.
    - A person who is not enrolled in a delivery experiment has rows with no probability, no draw and no exposure.
      No dataset exports such a row. ADR 0149 adds those columns for an enrolled person, and decides which datasets they enter.
19. **A delivery experiment draws only the decisions that ADR 0149 decision 2 names.** These are a copy on the work and an unprompted message.
    Among the contact kinds of decision 3, the experiment draws only unprompted contact. It never draws a decision that this policy keeps silent, and the policy records that decision.

### What this changes in other ADRs

- [ADR 0116](0116-a-push-is-one-web-push-message-from-the-instance.md) rule 6 keeps its payload: ids only, no text. Only what the client shows changes: it may fetch the notice line, and else shows the fixed text.
- ADR 0116 rule 7 changes. The server keeps quiet hours for unrequested notifications. New kinds join under rule 7: the requested result, the agreed check-in and the unprompted update.
- ADR 0120 decision 7 changes for background items on a private thread only. A compatible item also has the same contact kind.
  A claim takes requested results first. When a turn ends, the next claim starts at once if the start policy allows a turn now.
- ADR 0116 rule 7 keeps `CONVERSATION_ANSWER` and its trigger: 30 seconds after the turn ends, if the answer is unread.
- ADR 0116 rule 8 stays. The send check now also runs the contact policy.
- ADR 0120 (proposed in #4862) decision 4 stays. Its gates are the gates of rule 8 above.
- ADR 0123 (proposed in #4862) decision 3 points to this ADR for the route of unpinned background input.

## Consequences

- Good: a requested result arrives at once, and an agreed contact arrives at its slot.
- Good: one pause and one set of quiet hours hold on every channel.
- Good: the policy is a pure function, so a table test covers each kind and rule.
- Bad: a person who works at night must move quiet hours, or unprompted notifications wait until morning.
- Bad: a Slack message that Hephaestus starts can wait for quiet hours, while its entry is already in the app.
- Bad: the notice-line fetch is one more request on each push. A failed fetch shows only the fixed text.
- Bad: the reason check can miss an update that the person would want. "More like this" tunes it only within its three reasons.
- Bad: Hephaestus ignores device Focus settings it cannot read. A device rule can still hide what the server sends.
- Bad: one delivery decision row for each decision to reach a person, kept 400 days.

## Confirmation

Not enforced. The implementing issues add the checks below, and review of each implementing pull request catches a missing one. They are:

- ContactPolicyTableTest fails when a contact kind, rule and state give an effect that the table does not allow.
- RequestedResultAnyHourTest fails when quiet hours delay a requested result's notification.
- UnpromptedSlotsTest fails when Sometimes sends 2 unprompted notifications in a day, or Often sends 2 within 3 hours.
- ActiveNoPushTest fails when a push goes to a person who is active in that workspace.
- SendRecheckTest fails when a contact queued before a pause is sent after it.
- OneKindBatchTest fails when a background batch holds items of two contact kinds.
  It also fails when a claim takes another kind while a requested result is in the inbox.
  It also fails when the start policy allows a turn but the next claim waits for a new event.
- AppOnlyItemRouteTest fails when an item that only the app may show is routed to Slack.
- HeldResultNotCitedTest fails when an unprompted message cites a result that a delivery experiment holds.
- DeliveryDecisionTest fails when an unprompted decision has no row, or a model call starts before it.
- DeliveryDecisionWithoutDrawTest fails when the row of a person who is not enrolled holds a probability, a draw or an exposure.

## Revisit trigger

- Over four weeks, more than a fifth of people who allowed notifications turn them off.
- People report that a result they waited for arrived late.
- Browsers expose a device quiet-hours state to web apps.

## Sources

- W3C, [Push API](https://www.w3.org/TR/push-api/). Push messages "are delivered to a Service Worker". The worker can use the message "to update local state or display a notification". So the service worker can fetch the notice line first.
- Slack, [Pause your Slack notifications](https://slack.com/help/articles/214908388-Pause-notifications-with-Do-Not-Disturb). A person can "set a notification schedule" for routine times. Slack keeps it on its server, so it holds on every device. Decision 9 keeps quiet hours on the server too.
