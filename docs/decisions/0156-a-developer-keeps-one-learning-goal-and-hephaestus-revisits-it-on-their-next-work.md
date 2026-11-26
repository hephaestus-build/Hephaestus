# ADR 0156: A developer keeps one learning goal, and Hephaestus revisits it on their next work

**Status:** Accepted
**Date:** 2026-11-25
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0132](0132-personal-records-are-written-by-the-persons-act-or-noted-from-their-own-words.md) (goals and their retention), [ADR 0133](0133-a-check-in-is-an-agreed-contact-with-a-scope.md) (the first check-in), [ADR 0139](0139-the-contact-policy-decides-whether-hephaestus-may-interrupt-a-person-now.md) (no unprompted contact about a goal), [ADR 0130](0130-a-practice-has-a-portable-identity-and-growth-counts-independent-pieces-of-work.md) (growth and fading wait), [ADR 0121](0121-a-composer-on-a-shared-thread-writes-feedback-on-the-work-and-cannot-read-private-data.md) (the Our work page waits)

**Builds on:** [ADR 0121](0121-a-composer-on-a-shared-thread-writes-feedback-on-the-work-and-cannot-read-private-data.md) (the composer reads no private data), [ADR 0125](0125-hephaestus-is-never-a-performance-instrument.md) (never a performance instrument), [ADR 0128](0128-a-derived-record-keeps-its-basis-and-revocation-travels-by-generation.md) (refer, never copy; the cascade table)

**Relates to:** [ADR 0137](0137-on-request-is-a-feedback-mode.md) (On request release), [ADR 0150](0150-an-outcome-renders-from-rows-as-an-item-card-or-a-citation-token.md) (outcomes render from rows)

## Context

Hephaestus is an AI mentor that helps software developers grow in the engineering practices their team values. It gives feedback alongside the people who already mentor a developer.
A good mentor helps a developer pick one thing to grow in, watches the next attempt, and asks what they learned.
On main, Hephaestus reviews work and answers questions. The next step is to follow through on what the developer wants to learn.
ADRs 0130, 0132 and 0133 designed goals, noted records, growth marks and five check-in templates.
The issues that build them closed as not planned for 1.0, because each one adds policy, retention and erasure paths before one goal proves useful.

A goal shapes private guidance, and it belongs to the developer.
Free text that a model reads spreads: into answers, tool records, summaries and recalled answers. Each of them would need its own guard, its own erasure path and its own proof.
A practice from the catalog carries what coaching needs: its name and its criteria. Neither is personal data.
The real constraint is a loop that the developer owns completely, whose personal words stay with the developer, and whose claims about work come from admitted observations.

## Decision drivers

- The developer chooses what to grow in, and their act saves it.
- The developer's own words stay with the developer. Coaching uses the practice that they chose.
- Claims about work come from admitted observations. A reflection is the developer's own account, and its label says so.
- Help builds skill. A hint and a next attempt keep the learning with the developer. Answer-giving help can lower later unassisted performance ([Bastani et al., 2025](https://doi.org/10.1073/pnas.2422633122)).
- Hephaestus comes back at the time the developer agreed.
- The developer can correct, forget and erase all of it from the first save.

## Considered options

1. **Ship ADRs 0132 and 0133 as designed.** Six record kinds, noted records, five check-in templates with a scheduler, growth marks and the Our work page. Months of work, and each part needs its own proofs before one goal shows that it helps.
2. **Goals as `owned_record` rows with kind `GOAL`** (ADR 0132 decision 2). A goal needs a practice link and a revisit lifecycle that the other kinds do not have. One generic table gets nullable columns for each kind, and its erasure proofs cover kinds that do not exist yet.
3. **A goal that the model writes from the conversation.** Fast to start, but the words are not the developer's, and text that other people wrote can plant a goal.
4. **A free-text goal that the conversation's model reads.** Three review rounds found a new place where the words could leak each time: answers, tool records, summaries, recalled answers, Slack and exports. Each needs its own guard, and the guards do not converge. The conditions for a later release are in decision 16.
5. **A model turn that follows up at the check-in time.** It costs a model call when nothing changed, it needs the inbox of ADR 0120 (#4926), and its words can claim more than the rows say.
6. **A structured goal: one chosen practice and a private note, revisits that code writes from admitted observations, and one dated check-in in the app (chosen).**

## Decision

Option 6.

1. **A goal is one chosen practice.** It is one row in `goal`.
   - It holds one practice of the workspace, an optional private **note** of at most 280 characters, and an optional check-in date.
   - It belongs to one account in one workspace. A developer has at most one active goal in each workspace.
   - Its states are `ACTIVE` and `CLOSED`.
2. **The note and every reflection are owner-only text.**
   - Only the owner reads them, in their own app and in their own data export.
   - No model, prompt, tool, summary, review, Slack message or staff path reads them.
   - An architecture rule allows only the goal module and its owner export and erasure contributors to read them.
3. **A turn knows the chosen practice, as a fact.** A turn of the owner's private threads in the app gets one line in its situation: the name of the practice that the developer chose to grow in, and the status of the open revisit.
   - Both are catalog text and rows, not personal words. So an answer that uses them is ordinary conversation, under the conversation's rules.
   - The server renders the line from current rows for each turn. After Delete, Close or a disabled setting, the next turn does not get it.
   - A Slack turn gets the line after the person grants **Full** with the export notice ([ADR 0123](0123-slack-is-a-channel-into-the-one-conversation.md) decisions 4 and 5), when that grant ships.
4. **A revisit** is one row in `goal_revisit`. It is a moment when Hephaestus looks back at the goal with the developer.
   - Kind `WORK`: code writes it when a review of the goal's practice about the developer ends on a piece of work. The first review of that practice on that piece of work must start after **Save**. Mirrors of one item are one piece of work. No model writes it.
   - Kind `CHECK_IN`: the developer's answer to a due check-in writes it.
   - It stores IDs only, and renders from the rows of the owner's item card ([ADR 0150](0150-an-outcome-renders-from-rows-as-an-item-card-or-a-citation-token.md)).
   - It can hold one **reflection**: the developer's own account, at most 1,000 characters, shown as "Your account".
5. **States follow one table.** Code applies it in the transaction of each event. A test covers each cell.

   | State | Event | Next state |
   | --- | --- | --- |
   | No goal | **Save** with a practice | Goal `ACTIVE` |
   | Goal `ACTIVE` | **Close** | Goal `CLOSED`. The open revisit ends `SKIPPED`, and the check-in date clears. |
   | Goal `CLOSED` | **Reopen**, with no other active goal | Goal `ACTIVE` |
   | Any goal | **Delete**, the retention job, or the end of a leaving window | No goal. Its revisits go too. |
   | Goal `ACTIVE`, no open revisit | A review of the practice on a new piece of work ends, and the owner may read its rows | Revisit `OPEN` |
   | Revisit `OPEN`, no reflection | A review ends on a newer piece of work | The old revisit goes, and a new one is `OPEN`. |
   | Revisit `OPEN` | **Answer**, with a reflection | Revisit `ANSWERED` |
   | Revisit `OPEN` | **Skip** | Revisit `SKIPPED` |
   | Revisit, any state | **Forget** | Revisit `FORGOTTEN`: only the ID of the work stays, so that Hephaestus does not ask about that work again |
   | Revisit `OPEN` | A narrowing or an erasure hides the work | Hidden while hidden. It comes back when the work does. |
   | Check-in, no date | **Set date** | Scheduled for that date |
   | Check-in scheduled | The date comes, in the account's time zone, and no **Pause** holds | Due. The goal card asks "How is it going?". |
   | Check-in due | **Answer** or **Skip**, with the date that the card showed | A `CHECK_IN` revisit. Then the card offers a new date. |
   | Check-in due | **Answer** with a date that the goal no longer has | Refused as changed since shown. The card reloads. |
   | Any goal | The membership ends | Writes stop. The goal stays in the owner's export for the 30-day leaving window of [ADR 0126](0126-a-person-has-one-relationship-and-a-personal-space.md) decision 6. The retention job does not delete it during the window. |
   | Any goal | 90 days with no act of the owner | The card asks "Keep this goal?". **Keep** counts as an act. |
   | Any goal | 180 days with no act of the owner, outside a leaving window | No goal |

6. **The status of a `WORK` revisit renders from current rows, under the current practice version.** A revisit status is not an observation outcome, and it makes no claim about when feedback arrived.
   - "Newest" is the newest review of the practice on that piece of work that has ended. A queued or running review does not count.
   - "First" is the first ended review after **Save**.
   - A review that reused a current observation counts with that observation.
   - The rules apply in this order, and the first that matches decides:

   | Order | Revisit status | Rows |
   | --- | --- | --- |
   | 1 | You disagreed | The developer gave a **Disagreement** to the feedback of the newest observation. |
   | 2 | Marked incorrect | A workspace admin marked the newest observation incorrect. It never reads as the developer's act. |
   | 3 | Could not check | The newest review produced no observation: capture withheld the practice, admission withheld its candidate, or the review failed. The status never falls back to an older observation. |
   | 4 | Could not determine | The newest observation's outcome is Could not determine. |
   | 5 | Does not apply | The newest observation's outcome is Does not apply. |
   | 6 | Met on the first review | The newest observation is Met, and the first review produced a Met observation. |
   | 7 | Met on a later review | The newest observation is Met, and the first review did not produce a Met observation. |
   | 8 | Not met yet | The newest observation is Not met. The card links the feedback only if the developer can open it. |

   - The observation of an Off practice writes no revisit.
   - An On request observation writes its revisit only when the owner's request releases it ([ADR 0137](0137-on-request-is-a-feedback-mode.md)). The release starts no contact.
   - A `CHECK_IN` revisit has no status.
7. **Each act has its reverse or is an erasure.** Save has Delete. Close has Reopen. Edit changes the note or the check-in date and has Edit. Delete and Forget are erasures and ask for confirmation.
   - A new practice means a new goal. Edit keeps the practice.
   - The goal form's **Save** and the confirmed **Delete** replace the save with Undo of [ADR 0132](0132-personal-records-are-written-by-the-persons-act-or-noted-from-their-own-words.md) decision 11 for goals in this release.
   - Taking a goal to another workspace waits with portability ([ADR 0129](0129-learning-travels-as-the-persons-own-words-to-authorized-workspaces.md), #4886).
8. **Only the owner reads a goal.**
   - The API serves it only to the signed-in owner with a current membership, under `/me` paths with no person ID. A user view and an elevation get the same answer as no goal.
   - Every reference is checked against the goal's workspace and owner: the practice, the observation and the piece of work. Row-level security binds each row to its workspace and account from the first migration.
   - The end of the membership and a disabled setting fence every write, also a revisit that a late review would write.
   - No staff role reads it, and nothing tells staff whether it exists. The operator's person-data preview and export leave it out ([ADR 0125](0125-hephaestus-is-never-a-performance-instrument.md) decisions 1 and 7). Every person-data erasure deletes it. The owner's own export includes it.
9. **A goal shapes only private guidance.** Feedback on the work stays the same for every reader. The composer, the review agent and the practice-page copy of results in the eligible set read no goal ([ADR 0121](0121-a-composer-on-a-shared-thread-writes-feedback-on-the-work-and-cannot-read-private-data.md) decisions 5 and 11).
10. **The goal is enrolled before its first save.**
    - The person-data map lists `goal` and `goal_revisit`, with their export and erasure paths.
    - A restore reapplies every deletion before it serves data ([ADR 0140](0140-privacy-facts-go-to-a-journal-and-a-restore-reapplies-them.md)). Two fact kinds name their targets: a goal deletion names the goal and its revisits, and a Forget names one revisit.
    - A ready export that holds the goal is withdrawn in the transaction of a Delete, a Forget or the end of a leaving window. The owner can request a new one.
    - Goals stay off until both fact kinds exist.
11. **The cascade table of [ADR 0128](0128-a-derived-record-keeps-its-basis-and-revocation-travels-by-generation.md) decision 3 gets the dependent kind `GOAL_REVISIT`.**
    - A revisit holds a reference to an observation and no text of it, so its reference is a foreign key that clears when the observation goes, not a `basis_edge`.
    - `SOURCE_ERASURE` keeps it: an erasure that deletes the observation clears the link in its transaction, and the reflection stays ([ADR 0128](0128-a-derived-record-keeps-its-basis-and-revocation-travels-by-generation.md) decision 4). `ERASURE` deletes it. `NARROWING` hides it each time the server reads it. `REMOVAL` keeps it, because a revisit cites no conversation entry.
12. **Each answer about a practice has one order.**
    1. Answer the question.
    2. Give one hint and its reason, citing the exact place in the work.
    3. End with at most one question: an invitation to one next attempt, or to disagree.
13. **On the chosen practice, on the developer's own current work, Hephaestus gives the hint first.**
    - The first answer gives the hint and may show an example on other work.
    - When the developer asks for it after the hint, Hephaestus writes sample text for them to adapt.
    - With the help style **Just tell me** of [ADR 0132](0132-personal-records-are-written-by-the-persons-act-or-noted-from-their-own-words.md), it writes the sample on the first request.
14. **Each prompt has one place.**
    - The goal card asks the revisit question and the check-in question. A turn does not ask them again.
    - After a useful answer about a practice, a turn may offer a goal for that practice. The offer opens the goal form with the practice chosen. It saves nothing.
    - **Not now** dismisses an offer. Code allows one offer for each practice in 30 days.
    - A marker in the owner's own navigation shows an open revisit or a due check-in. Under **Pause** it does not show. It is not a notification.
15. **Rollout.**
    - A workspace setting, off by default, turns learning goals on. While it is off, no revisit is written, no turn gets the line of decision 3, and the goal card shows only Delete for an existing goal. Turning it on again restores the goals that still exist.
    - The pilot is the hephaestus-build workspace, with the practice `states-how-to-verify-the-change`, after the first release after 1.0.
    - Volunteers join by saving their own goal. The pilot asks each of them, in a voluntary interview, whether they saw, understood and used the card, and what changed in their next pull request.
    - **Met on the first review** on the next pull request is an observation that a volunteer may share, not a target. No staff view, metric or export counts the outcomes of a person.
16. **Free-text goals that a model reads are a later option.** They need all of these first:
    - A class on each conversation record that every reader checks: answers, tool calls and tool results, summaries, recall, Slack and exports.
    - Erasure that follows that class through every record that a turn derives.
    - A pilot result that shows the chosen practice alone is not enough.

### Deferred, with the reason

| Part | ADR and issue | Reason |
| --- | --- | --- |
| Growth marks, help modes and automatic fading | [ADR 0130](0130-a-practice-has-a-portable-identity-and-growth-counts-independent-pieces-of-work.md) decisions 6 to 11, #4956, #4985 | A count is a poor proxy for mastery. The developer asks for less help instead. |
| Weekly reflection, Monday focus and the other templates | [ADR 0133](0133-a-check-in-is-an-agreed-contact-with-a-scope.md) decisions 11 to 13, #4992, #5005 | Each adds a second timing channel before one check-in proves useful. |
| Scheduled check-ins, and check-ins outside the app: notifications, email and Slack | [ADR 0133](0133-a-check-in-is-an-agreed-contact-with-a-scope.md) decisions 4, 5, 8, 9 and 18, #4938 | The pilot first measures whether the in-app loop is used. |
| The goal follow-up and unprompted messages about a goal | [ADR 0139](0139-the-contact-policy-decides-whether-hephaestus-may-interrupt-a-person-now.md) decision 7 | Unsolicited contact stays off until the pilot shows the cost of an interruption. |
| The full Our work page | [ADR 0121](0121-a-composer-on-a-shared-thread-writes-feedback-on-the-work-and-cannot-read-private-data.md) decision 14, #4936 | The goal card covers one goal. |
| Noted records and the other record kinds | [ADR 0132](0132-personal-records-are-written-by-the-persons-act-or-noted-from-their-own-words.md) decisions 2 to 9, #4924 | Their origin proofs do not exist, and a planted note shapes every later turn. |

## Consequences

- Good: a developer chooses one practice, sees what the review found on their next pull request, and says what they learned. Every claim comes from rows.
- Good: the developer's own words never leave their app. Erasure, export and recovery cover one owner-only row and its revisits.
- Good: the review gives evidence about the work. The developer decides what it means for their goal, in their own reflection, and can take it to a person who mentors them.
- Good: no scheduler, inbox or notification is needed. The loop works on main's review pipeline and conversation.
- Bad: coaching knows the practice, not the developer's words for it. The note helps only the developer.
- Bad: earlier answers that mentioned the chosen practice keep the conversation retention after Delete, as any answer does.
- Bad: a developer who does not open the app does not see the check-in. The revisit trigger below decides when a notification is worth it.
- Bad: one active goal per workspace. A developer who wants two must close one first.

## Confirmation

Not enforced yet. The implementing pull request adds these checks, and its review catches a missing one:

- GoalOwnerOnlyRouteTest fails when another member, a workspace admin, an instance admin or a user view reads, changes or learns the existence of someone's goal. It covers the operator's person-data preview and export, and one owner in two workspaces.
- GoalNoteStaysWithOwnerTest seeds a marker in a note and a reflection. It fails when the marker appears in a turn's prompt, situation or tool result, a summary, a review, a Slack message or the operator's export.
- An architecture rule fails when a class outside the goal module and its owner export and erasure contributors reads the note or a reflection.
- ComposerReadsNoGoalTest fails when the composer's or the review's assembled context holds anything of a goal. An architecture rule fails when their packages depend on the goal module.
- GoalStateTableTest has one case for each row of decision 5, the stale check-in answer included.
- RevisitStatusTest has one case for each row of decision 6, also a queued review, a timeout, a reused observation, an Off practice and an On request release.
- GoalErasureTest fails in these cases:
  - account erasure, Delete or Forget leaves the note or a reflection in any Hephaestus store, a ready export included;
  - the end of the membership deletes the goal before its window ends, or keeps it after;
  - the retention job deletes a goal during a leaving window;
  - a restore serves a goal or a revisit that a fact deleted, or a Forget fact erases more than its revisit;
  - a source erasure leaves a revisit's link to the work.
- CascadeTableTest has the `GOAL_REVISIT` cells. `PersonalDataMapArchTest` lists both tables.
- NoModelWritesAGoalTest fails when a conversation tool can insert or change a goal row.

## Revisit trigger

- In the pilot interviews, volunteers say they missed a due check-in. Then add the agreed notification of [ADR 0133](0133-a-check-in-is-an-agreed-contact-with-a-scope.md).
- Volunteers say that coaching without their own words missed what they wanted. Then weigh decision 16.
- Pilot developers ask for a second active goal.
- A pilot developer reports that a revisit claimed more than its observation says.

## Sources

- Bastani et al., [Generative AI without guardrails can harm learning](https://doi.org/10.1073/pnas.2422633122), PNAS 2025. Students with an answer-giving tutor did better while assisted and worse afterwards. Decisions 12 and 13 follow from it.
- GDPR, [articles 5, 16 and 17](https://eur-lex.europa.eu/eli/reg/2016/679/oj/eng). Data minimization, rectification and erasure. Decisions 5, 7 and 10 follow from them.
