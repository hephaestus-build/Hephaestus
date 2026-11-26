# ADR 0156: A developer keeps one learning goal, and Hephaestus revisits it on their next work

**Status:** Accepted
**Date:** 2026-11-25
**Authors:** Felix T.J. Dietrich

**Amends:** [ADR 0132](0132-personal-records-are-written-by-the-persons-act-or-noted-from-their-own-words.md) (goals and their retention), [ADR 0133](0133-a-check-in-is-an-agreed-contact-with-a-scope.md) (the first check-in), [ADR 0139](0139-the-contact-policy-decides-whether-hephaestus-may-interrupt-a-person-now.md) (no unprompted contact about a goal), [ADR 0130](0130-a-practice-has-a-portable-identity-and-growth-counts-independent-pieces-of-work.md) (growth and fading wait), [ADR 0121](0121-a-composer-on-a-shared-thread-writes-feedback-on-the-work-and-cannot-read-private-data.md) (the Our work page waits), [ADR 0123](0123-slack-is-a-channel-into-the-one-conversation.md) (no goal in a Slack turn)

**Builds on:** [ADR 0121](0121-a-composer-on-a-shared-thread-writes-feedback-on-the-work-and-cannot-read-private-data.md) (the composer reads no private data), [ADR 0125](0125-hephaestus-is-never-a-performance-instrument.md) (never a performance instrument), [ADR 0128](0128-a-derived-record-keeps-its-basis-and-revocation-travels-by-generation.md) (refer, never copy; the cascade table)

**Relates to:** [ADR 0137](0137-on-request-is-a-feedback-mode.md) (On request release and its visibility rule), [ADR 0150](0150-an-outcome-renders-from-rows-as-an-item-card-or-a-citation-token.md) (outcomes render from rows)

## Context

Hephaestus is an AI mentor that helps software developers grow in the engineering practices their team values. It gives feedback alongside the people who already mentor a developer.
A good mentor helps a developer pick one thing to grow in, watches the next attempt, and asks what they learned.
On main, Hephaestus reviews work and answers questions. The next step is to follow through on what the developer wants to learn.
ADRs 0130, 0132 and 0133 designed goals, noted records, growth marks and five check-in templates.
The issues that build them closed as not planned for 1.0, because each one adds policy, retention and erasure paths before one goal proves useful.

A goal belongs to the developer.
The practice name is catalog text, but the choice of a practice is personal: it says what this developer wants to get better at.
Data that a model reads spreads: into answers, tool records, summaries, recalled answers, Slack history and exports. Each of them would need its own guard, its own erasure path and its own proof.
The real constraint is a loop that the developer owns completely, whose facts stay with the developer, and whose claims about work come from admitted observations.

## Decision drivers

- The developer chooses what to grow in, and their act saves it.
- Everything about the goal stays with the developer: the choice, the note, the revisits and the reflections.
- Claims about work come from admitted observations. A reflection is the developer's own account, and its label says so.
- Help builds skill. A hint and a next attempt keep the learning with the developer. In a field experiment with high-school math students, an answer-giving tutor raised performance while assisted and lowered it afterwards ([Bastani et al., 2025](https://doi.org/10.1073/pnas.2422633122)).
- Hephaestus comes back at the time the developer agreed.
- The developer can correct, forget and erase all of it from the first save.

## Considered options

1. **Ship ADRs 0132 and 0133 as designed.** Six record kinds, noted records, five check-in templates with a scheduler, growth marks and the Our work page. Months of work, and each part needs its own proofs before one goal shows that it helps.
2. **Goals as `owned_record` rows with kind `GOAL`** (ADR 0132 decision 2). A goal needs a practice link and a revisit lifecycle that the other kinds do not have. One generic table gets nullable columns for each kind, and its erasure proofs cover kinds that do not exist yet.
3. **A goal that the model writes from the conversation.** Fast to start, but the words are not the developer's, and text that other people wrote can plant a goal.
4. **A free-text goal that the conversation's model reads.** Three review rounds found a new place where the words could leak each time: answers, tool records, summaries, recalled answers, Slack and exports. Each needs its own guard, and the guards do not converge.
5. **A turn that knows the chosen practice and the revisit status.** Both are rows, not words. But an answer that uses them carries the choice into conversation history. Slack replays that history, and the operator's audited export holds it. Every reader of the conversation would need an audience check first. Decision 16 keeps it as a later option.
6. **A model turn that follows up at the check-in time.** It costs a model call when nothing changed, it needs the inbox of ADR 0120 (#4926), and its words can claim more than the rows say.
7. **A structured goal that only its owner reads: one chosen practice and a private note, revisits that code writes from admitted observations, and one dated check-in, all on a goal card in the app (chosen).**

## Decision

Option 7.

1. **A goal is one chosen practice.** It is one row in `goal`.
   - It holds one of the workspace practices that is in use, never a draft. It also holds an optional private **note** of at most 280 characters and an optional check-in date.
   - It belongs to one account in one workspace. A developer has at most one active goal in each workspace.
   - Its states are `ACTIVE` and `CLOSED`.
2. **One visibility rule: only the owner reads a goal fact.**
   - A goal fact is anything that shows whether a person has a goal, or what it holds. That is the chosen practice, the note, the check-in date and its answers, each revisit with its status and reflection, and the journal facts of decision 10.
   - The goal module reads goal facts only for the owner: for the signed-in owner in their own app, and for the owner's own export.
   - Row-level security binds the owner account to each transaction that reads a goal row. Every other reader gets no row.
   - Every other destination reads nothing of a goal, not even whether one exists:
     - a turn in the app, and a turn in Slack at each detail level;
     - a summary, the composer, a review and a notification;
     - the operator's person-data preview, export and erasure receipt, and the person-data export of the privacy journal;
     - a log line and a metric.
   - An architecture rule allows only the goal module and its owner export and erasure contributors to depend on the goal rows.
   - The Confirmation has one test for each destination.
   - This is an application access rule. The instance operator keeps the custody of stored data and backups that [Privacy](../user/privacy.mdx) states.
3. **No turn knows the goal in this release.**
   - A turn in the app or in Slack gets no goal fact: not in its prompt, its situation or a tool. The **Full** Slack detail level of [ADR 0123](0123-slack-is-a-channel-into-the-one-conversation.md) adds none.
   - So no answer can carry a goal fact into Slack history, a summary or the operator's export.
   - The goal card carries the loop. The server renders it from the owner's rows. It is not a conversation entry, and no turn reads it.
   - What the developer writes about their goal in the conversation is an ordinary conversation entry, under the conversation's rules.
   - Coaching follows decisions 12 and 13 on every practice. It does not change for the chosen practice.
4. **A revisit** is one row in `goal_revisit`. It is a moment when Hephaestus looks back at the goal with the developer.
   - Kind `WORK`: code writes it when a review of the goal's practice about the developer ends on a piece of work, and decision 6 counts that review. The first review of that practice on that piece of work must start after **Save**. No model writes it.
   - A piece of work is one item that a review of the practice is about, of any item kind. Mirrors of one item are one piece of work. The item is its key.
   - Work is ordered by the time its item was created, then by the item ID. The order never depends on which review ends last.
   - Kind `CHECK_IN`: the developer's **Answer** or **Skip** of a due check-in writes it.
   - It stores IDs only. A `WORK` revisit references the item and the observation of the counted review. It renders from the rows of the owner's item card ([ADR 0150](0150-an-outcome-renders-from-rows-as-an-item-card-or-a-citation-token.md)).
   - It can hold one **reflection**: the developer's own account, at most 1,000 characters, shown as "Your account".
5. **States follow one table.** Code applies it in the transaction of each event. A test covers each row. An event that no row allows changes nothing. If it is an act of the owner, it is refused, and the card reloads.

   | State | Event | Next state |
   | --- | --- | --- |
   | No goal | **Save** with a practice in use | Goal `ACTIVE` |
   | Goal `ACTIVE` | **Edit** the note | The same state, with the new note |
   | Goal `ACTIVE` | **Close** | Goal `CLOSED`. The open revisit ends `SKIPPED`, and the check-in date clears. |
   | Goal `CLOSED` | **Reopen**, with no other active goal | Goal `ACTIVE`, with no check-in date |
   | Any goal | **Delete**, the retention job, or the end of a leaving window | No goal. Its revisits go too, and the owner's exports are withdrawn (decision 10). |
   | Goal `ACTIVE` | A counted review ends on a piece of work that has no revisit of this goal | If the work is newer than each work with a revisit of this goal: a new revisit is `OPEN`, and an open revisit on older work goes. Otherwise: no change. |
   | Any goal | A counted review ends on a piece of work that has a revisit of this goal, in any state | No change. Its status renders from rows (decision 6). |
   | Revisit `OPEN` | **Answer**, with a reflection | Revisit `ANSWERED` |
   | Revisit `OPEN` | **Skip** | Revisit `SKIPPED`, with no reflection |
   | `WORK` revisit, any state | **Forget** | Revisit `FORGOTTEN`: the reflection and the observation link go. Only the item key stays, so that Hephaestus does not ask about that work again. |
   | `CHECK_IN` revisit, any state | **Forget** | The revisit goes. It has no work to keep a key for. |
   | `WORK` revisit, any state | The owner can no longer read its work: a narrowing, a source erasure or the review's retention | Status **Not available**. The reflection stays. It comes back when the owner can read the work again. |
   | No check-in date | **Set date** | Scheduled for that date. The check-in version grows by one. |
   | Check-in scheduled or due | **Edit** moves or clears the date | Scheduled for the new date, or no date. The version grows by one. A moved check-in is due only on its new date. |
   | Check-in scheduled | The date comes, in the account's time zone | Due. The goal card asks "How is it going?", below the question of an open revisit. |
   | Check-in due | **Pause**, then **Resume** | Under Pause, the card does not ask and no marker shows. After Resume, the card asks again. |
   | Check-in due | **Answer** or **Skip**, with the check-in version that the card showed | A `CHECK_IN` revisit, `ANSWERED` with the reflection or `SKIPPED`. The date clears, and the card offers a new date. |
   | Any check-in | **Answer** or **Skip** with a version that the goal no longer has | Refused as changed since shown. The card reloads. |
   | Any goal | The membership ends | Writes stop. The goal stays in the owner's export for the 30-day leaving window of [ADR 0126](0126-a-person-has-one-relationship-and-a-personal-space.md) decision 6. The retention job does not delete it during the window. |
   | Any goal | The workspace setting turns off | Writes stop, except **Delete**, **Forget** and the owner's export. No revisit is written. The card shows the goal read-only. |
   | Any goal | The workspace setting turns on again | The goals that still exist come back. Reviews that ended while it was off write no revisit. |
   | Goal `ACTIVE` | Its practice leaves the workspace practices | No new revisit. The card says that the practice is no longer in use. |
   | Any goal | 90 days with no act of the owner | The card asks "Keep this goal?". **Keep** counts as an act. |
   | Any goal | 180 days with no act of the owner, outside a leaving window | No goal |

6. **The status of a `WORK` revisit renders from current rows at each read.** It is never stored. So **Remove mark**, or a Disagreement that the developer withdraws, changes it at the next read. A revisit status is not an observation outcome, and it makes no claim about when feedback arrived.
   - The status reads only what the owner's item card may show now: the owner's own reviews and observations, under current authority and the destination policy ([ADR 0137](0137-on-request-is-a-feedback-mode.md) decision 13). The goal card and the marker use the same read.
   - A review is **counted** when it has ended, and either its observation is one that the item card may show now, or it produced no observation. A queued or running review is not counted.
   - An ended review whose observation the card may not show yet is not counted until it may. An unreleased On request observation is one.
   - A review counts for the practice and the work through its review membership. So a review that reused a current observation counts with that observation.
   - "Newest" is the newest counted review. "First" is the first counted review after **Save**. Each can be of any practice version. The card names the version only when it is not the current one.
   - The rules apply in this order, and the first that matches decides:

   | Order | Revisit status | Rows |
   | --- | --- | --- |
   | 1 | Not available | The owner can no longer read the work. A source erasure cleared the observation link, or a narrowing or retention hides the work. |
   | 2 | You disagreed | The developer gave a **Disagreement** to the feedback of the newest observation. |
   | 3 | Marked incorrect | A workspace admin marked the newest observation incorrect. It never reads as the developer's act. |
   | 4 | Could not check | The newest counted review produced no observation. Capture withheld the practice, admission withheld its candidate, the review returned a runtime non-result, or it failed. The status never falls back to an older observation. |
   | 5 | Could not determine | The newest observation's outcome is Could not determine. |
   | 6 | Does not apply | The newest observation's outcome is Does not apply. |
   | 7 | Met on the first review | The newest observation is Met, and the first review produced a Met observation. |
   | 8 | Met on a later review | The newest observation is Met, and the first review did not produce a Met observation. |
   | 9 | Not met yet | The newest observation is Not met. The card links the feedback only if the developer can open it. |

   - Delivery that a destination policy or On request withholds is never **Could not check**. Its review is not counted.
   - An observation that a review admitted in feedback mode Off counts for no revisit.
   - An On request observation counts when the owner's request releases it. Then the table of decision 5 applies once, as for any counted review. The release starts no contact.
   - A `CHECK_IN` revisit has no status.
7. **Each act has its reverse or is an erasure.** Save has Delete. Close has Reopen. Edit changes the note or the check-in date and has Edit. Pause has Resume. Delete and Forget are erasures and ask for confirmation.
   - A new practice means a new goal. Edit keeps the practice.
   - An `ANSWERED` revisit shows its reflection on the goal card, and under **Past goals** after Close. A reflection has no Edit. **Forget** erases it.
   - The goal form's **Save** and the confirmed **Delete** replace the save with Undo of [ADR 0132](0132-personal-records-are-written-by-the-persons-act-or-noted-from-their-own-words.md) decision 11 for goals in this release.
   - Taking a goal to another workspace waits with portability ([ADR 0129](0129-learning-travels-as-the-persons-own-words-to-authorized-workspaces.md), #4886).
8. **The read point enforces the rule of decision 2.**
   - The API serves a goal only to the signed-in owner with a current membership, under `/me` paths with no person ID. A user view and an elevation get the same answer as no goal.
   - Every reference is checked against the goal's workspace and owner: the practice, the observation and the piece of work. Row-level security binds each row to its workspace and account from the first migration.
   - A disabled setting fences every write except **Delete** and **Forget**. The end of the membership fences every write. Both also fence a revisit that a late review would write.
   - The operator's person-data preview, export and erasure receipt select no goal row, export none and report no count ([ADR 0125](0125-hephaestus-is-never-a-performance-instrument.md) decisions 1 and 7). Every person-data erasure still deletes the goals.
   - The owner's export includes the goal. During a leaving window, it reads the goal by account under the owner binding, with no membership check. The `/me` API stays closed to a former member.
9. **A goal shapes only its goal card.** Feedback on the work stays the same for every reader. The composer, the review agent and the practice-page copy of results in the eligible set read no goal ([ADR 0121](0121-a-composer-on-a-shared-thread-writes-feedback-on-the-work-and-cannot-read-private-data.md) decisions 5 and 11).
10. **The goal is enrolled before its first save.**
    - The person-data map lists `goal` and `goal_revisit`, with their export and erasure paths.
    - A restore reapplies every deletion before it serves data ([ADR 0140](0140-privacy-facts-go-to-a-journal-and-a-restore-reapplies-them.md)). Two fact kinds name their targets:
      - A goal deletion names the goal and its revisits. Its replay deletes them.
      - A Forget names one revisit. Its replay applies the Forget row of decision 5: a `WORK` revisit keeps only its item key, and a `CHECK_IN` revisit goes.
    - Both fact kinds hold goal and revisit IDs only, never an account ID. So no person-data read of the journal finds them (decision 2). A replay finds the owner from the restored row.
    - Goals need no journal bucket. Without one, the manual recovery of [ADR 0140](0140-privacy-facts-go-to-a-journal-and-a-restore-reapplies-them.md) decision 10 covers both kinds, from their log events with kind and IDs.
    - Delete, Forget, the retention job and the end of a leaving window withdraw each export of the owner that is in flight or ready, in their own transaction. They take the export row lock that publication takes, so a bundle assembled before the erasure is never published. A withdrawn export keeps no payload. A replay withdraws the exports too. The owner can request a new one.
    - Goals stay off until both fact kinds exist.
11. **The cascade table of [ADR 0128](0128-a-derived-record-keeps-its-basis-and-revocation-travels-by-generation.md) decision 3 gets the dependent kind `GOAL_REVISIT`.**
    - A revisit holds a reference to an observation and no text of it, so its reference is a foreign key that clears when the observation goes, not a `basis_edge`.
    - `SOURCE_ERASURE` keeps it: an erasure that deletes the observation clears the link in its transaction. The reflection stays, and the status reads **Not available** ([ADR 0128](0128-a-derived-record-keeps-its-basis-and-revocation-travels-by-generation.md) decision 4). `ERASURE` deletes it. `NARROWING` makes its status **Not available** at each read while the narrowing holds. `REMOVAL` keeps it, because a revisit cites no conversation entry.
12. **Each answer about a practice has one order.**
    1. Answer the question.
    2. Give one hint and its reason. Cite the exact place in the work when the turn can read a source of it. Otherwise, say where to look.
    3. End with at most one question: an invitation to one next attempt, or to disagree.
13. **Sample text follows the developer's request.**
    - When the developer asks for sample text, Hephaestus writes it for them to adapt.
    - Before any sample text, the rule of [ADR 0124](0124-hephaestus-gives-practice-feedback-with-a-conversation-and-practices-are-the-unit.md) decision 9 holds: Hephaestus never writes what a person submits as their own work.
    - This release has no help-style setting. The developer's request in the turn decides. The help style of [ADR 0132](0132-personal-records-are-written-by-the-persons-act-or-noted-from-their-own-words.md) waits.
14. **Each prompt has one place.**
    - The goal card asks the revisit question and the check-in question. A turn does not ask them, because it does not know them.
    - The goal card holds the link to the feedback of a revisit. A turn cites no revisit.
    - After a useful answer about a practice, a turn may offer a goal for that practice. The offer opens the goal form with the practice chosen. It saves nothing.
    - The app shows the offer only while the owner has no active goal, from the owner's own rows. Slack does not show it.
    - **Not now** dismisses an offer. Code allows one offer for each practice in 30 days.
    - A marker in the owner's own navigation shows an open revisit or a due check-in. Under **Pause** it does not show. It is not a notification.
15. **Rollout.**
    - A workspace setting, off by default, turns learning goals on. While it is off, no revisit is written. The goal card shows an existing goal read-only, with Delete and Forget, and the owner's export holds it. Turning it on again restores the goals that still exist.
    - The pilot is planned for the first release after 1.0, in the hephaestus-build workspace, with the practice `states-how-to-verify-the-change`.
    - Volunteers join by saving their own goal. The pilot asks each of them, in a voluntary interview, whether they saw, understood and used the card, and what changed in their next pull request.
    - **Met on the first review** on the next pull request is an observation that a volunteer may share, not a target. No staff view, metric or export counts the outcomes of a person.
16. **A turn that knows the goal is a later option.**
    - First the chosen practice and the revisit status, then free text. A turn could then give the hint first on the chosen practice.
    - A Slack turn under **Full** could then read the chosen practice and the status. A note and a reflection stay outside Full until free text ships.
    - Both steps need all of these first:
      - A class on each conversation record that every reader checks: answers, tool calls and tool results, summaries, recall, Slack and exports.
      - Erasure that follows that class through every record that a turn derives.
      - A pilot result that shows the goal card alone is not enough.

### Deferred, with the reason

| Part | ADR and issue | Reason |
| --- | --- | --- |
| Growth marks, help modes and automatic fading | [ADR 0130](0130-a-practice-has-a-portable-identity-and-growth-counts-independent-pieces-of-work.md) decisions 6 to 11, #4956, #4985 | A count is a poor proxy for mastery. The developer asks for less help instead. |
| Weekly reflection, Monday focus and the other templates | [ADR 0133](0133-a-check-in-is-an-agreed-contact-with-a-scope.md) decisions 11 to 13, #4992, #5005 | Each adds a second timing channel before one check-in proves useful. |
| Scheduled check-ins, and check-ins outside the app: notifications, email and Slack | [ADR 0133](0133-a-check-in-is-an-agreed-contact-with-a-scope.md) decisions 4, 5, 8, 9 and 18, #4938 | The pilot first measures whether the in-app loop is used. |
| The goal follow-up and unprompted messages about a goal | [ADR 0139](0139-the-contact-policy-decides-whether-hephaestus-may-interrupt-a-person-now.md) decision 7 | Unsolicited contact stays off until the pilot shows the cost of an interruption. |
| The full Our work page | [ADR 0121](0121-a-composer-on-a-shared-thread-writes-feedback-on-the-work-and-cannot-read-private-data.md) decision 14, #4936 | The goal card covers one goal. |
| Noted records and the other record kinds | [ADR 0132](0132-personal-records-are-written-by-the-persons-act-or-noted-from-their-own-words.md) decisions 2 to 9, #4924 | Their origin proofs do not exist, and a planted note shapes every later turn. |
| A turn that knows the goal, and the help style | Decision 16 and [ADR 0132](0132-personal-records-are-written-by-the-persons-act-or-noted-from-their-own-words.md) | Every reader of the conversation needs an audience check first. |

## Consequences

- Good: a developer chooses one practice, sees what the review found on their next piece of work, and says what they learned. Every claim comes from rows.
- Good: nothing about the goal leaves the owner's app and export. One read point, one rule and one test for each destination prove it.
- Good: the review gives evidence about the work. The developer decides what it means for their goal, in their own reflection, and can take it to a person who mentors them.
- Good: no scheduler, inbox or notification is needed. The loop works on main's review pipeline and conversation.
- Bad: coaching does not know the goal in this release. A question about the chosen practice gets the same answer as any other question.
- Bad: what the developer writes about their goal in the conversation follows the conversation's rules, as any message does.
- Bad: a developer who does not open the app does not see the check-in. The revisit trigger below decides when a notification is worth it.
- Bad: one active goal per workspace. A developer who wants two must close one first.

## Confirmation

Not enforced yet. The implementing pull request adds these checks, and its review catches a missing one.

One test for each destination of decision 2. Each seeds a goal with a marker in its note and a reflection, an open revisit and a due check-in:

| Destination | Test | It fails when |
| --- | --- | --- |
| The owner's app and API | GoalOwnerOnlyRouteTest | another member, a workspace admin, an instance admin, a user view or an elevation reads, changes or learns the existence of someone's goal. It covers one owner in two workspaces. |
| A turn in the app | GoalStaysWithOwnerTest | a goal fact appears in a turn's prompt, situation, tool call, tool result or summary |
| A turn in Slack | GoalStaysWithOwnerTest | after an app turn, a Slack turn at Notice or at Full holds a goal fact in its prompt, its history or its render |
| The operator's person-data preview, export and erasure receipt | GoalStaysWithOwnerTest | any of them holds a goal row, a goal count or a goal fact kind |
| The privacy journal's person-data export | GoalStaysWithOwnerTest | a goal fact kind matches the account |
| The composer and the review | ComposerReadsNoGoalTest | their assembled context holds anything of a goal, or their packages depend on the goal module |
| Notifications, logs and metrics | GoalStaysWithOwnerTest | a notification, a log line or a metric label holds a goal fact |
| The owner's export | GoalExportTest | it leaves out the owner's goal, holds another account's goal, or reads no goal during a leaving window |
| Every other reader | An architecture rule | a class outside the goal module and its owner export and erasure contributors depends on the goal rows |

The other checks:

- GoalStateTableTest has one case for each row of decision 5, a refused event and a date that moved and moved back included.
- RevisitStatusTest has one case for each row of decision 6. It also covers a queued review, a timeout, a reused observation, an Off practice, an On request observation before and after release, two reviews that end in the reverse order of their work, an earlier practice version and **Remove mark**.
- GoalErasureTest fails in these cases:
  - account erasure, Delete or Forget leaves the note or a reflection in any Hephaestus store, an export included;
  - an export that was assembled before a Delete or a Forget is published after it;
  - the end of the membership deletes the goal before its window ends, or keeps it after;
  - the retention job deletes a goal during a leaving window;
  - a restore serves a goal or a revisit that a fact deleted, or a Forget fact erases more than its revisit, or keeps a `CHECK_IN` revisit;
  - a source erasure leaves a revisit's link to the work, or removes its reflection.
- CascadeTableTest has the `GOAL_REVISIT` cells. `PersonalDataMapArchTest` lists both tables.
- NoModelWritesAGoalTest fails when a conversation tool can insert or change a goal row.

## Revisit trigger

- In the pilot interviews, volunteers say they missed a due check-in. Then add the agreed notification of [ADR 0133](0133-a-check-in-is-an-agreed-contact-with-a-scope.md).
- Volunteers say that coaching that does not know their goal missed what they wanted. Then weigh decision 16.
- Pilot developers ask for a second active goal.
- A pilot developer reports that a revisit claimed more than its observation says.

## Sources

- Bastani et al., [Generative AI without guardrails can harm learning](https://doi.org/10.1073/pnas.2422633122), PNAS 2025. In a field experiment with high-school math students, students with an answer-giving tutor did better while assisted and worse afterwards. Decisions 12 and 13 follow from it.
- GDPR, [articles 5, 16 and 17](https://eur-lex.europa.eu/eli/reg/2016/679/oj/eng). Data minimization, rectification and erasure. Decisions 5, 7 and 10 follow from them.
