# ADR 0157: The mentor core keeps what the person saves, and each answer shows what it used

**Status:** Accepted
**Date:** 2026-11-26
**Authors:** Felix T.J. Dietrich

**Supersedes:** the held proposal ADR 0156 in [#5222](https://github.com/hephaestus-build/Hephaestus/pull/5222). It never reached main.

**Amends:**

- [ADR 0130](0130-a-practice-has-a-portable-identity-and-growth-counts-independent-pieces-of-work.md) (help steps back only when the person accepts)
- [ADR 0132](0132-personal-records-are-written-by-the-persons-act-or-noted-from-their-own-words.md) (record kinds, the first save path, and how a record reaches a turn)
- [ADR 0133](0133-a-check-in-is-an-agreed-contact-with-a-scope.md) (a due occurrence is a fixed-text notice, and a closed goal pauses its check-ins)

**Builds on:**

- [ADR 0124](0124-hephaestus-gives-practice-feedback-with-a-conversation-and-practices-are-the-unit.md) decision 8 (the five parts of an AI mentor)
- [ADR 0127](0127-every-record-and-route-resolves-to-concrete-readers.md), [ADR 0128](0128-a-derived-record-keeps-its-basis-and-revocation-travels-by-generation.md) and [ADR 0134](0134-the-authorization-recheck-runs-at-three-boundaries-with-two-authorization-generations.md) (readers, basis and fences)
- [ADR 0131](0131-a-person-has-one-conversation-in-each-workspace-and-its-answers-stream.md) decision 16 (**What Hephaestus used**)

**Relates to:** [ADR 0139](0139-the-contact-policy-decides-whether-hephaestus-may-interrupt-a-person-now.md) (contact policy), [ADR 0147](0147-capacity-degrades-gracefully-and-a-question-is-never-refused-for-load.md) (capacity), [ADR 0150](0150-an-outcome-renders-from-rows-as-an-item-card-or-a-citation-token.md) (outcomes render from rows)

## Context

ADR 0124 decision 8 names five parts that make Hephaestus an AI mentor.
They are goals in the person's own words, a memory that the person sees and controls, agreed check-ins, help that fades on evidence, and referral to people.
ADRs 0126 to 0139 designed them. The 1.0 issues that built them closed as not planned.

The held ADR 0156 narrowed the loop to one chosen practice on an owner-only card that no model reads.
Each earlier revision of it let a model read a private fact, and a review found a new copy path each time: answers, tool records, summaries, recall, Slack history and exports.
The cause is one gap: an output does not carry the readers, the current authority and the basis of its inputs.
The privacy track (#5141, #5256, the route-bound context) closes that gap once.

A mentor is also an experience, not only a schema.
Walkthroughs on staging saw answers that took more than a minute, and turns that ended with no useful output.
No ADR says what a person sees in the first conversation, when an answer cannot check something, or how to disagree.

The real constraint is this: build the whole core now, in an order where each part is useful and honest alone, and where no private record reaches a model before its route and basis are proved.

## Decision drivers

- The person's act writes each record. The person sees it, changes it and removes it.
- Nothing claims more than the rows say. This applies to an answer, a state and a list line.
- A private record reaches a model only after the turn records its basis and binds its route.
- The first week is useful also for a person who never saves a goal.
- Help builds skill. Hephaestus hints first when the person wants that, and steps back only when the person agrees.
- Speed and quality are targets that we measure, never promises.

## Considered options

1. **Keep ADR 0156.** One chosen practice and an owner card. It is safe, but it is a form. The goal never shapes help, and each later record kind repeats its guards.
2. **Ship ADRs 0130, 0132 and 0133 as written, with model access at once.** The copy paths that the reviews of #5222 found come back before the basis exists.
3. **One table for each record kind,** such as `goal` and `goal_revisit`. Each kind repeats the owner rule, the export, the erasure and the Slack exclusion.
   ADR 0156 chose this because a generic table had nullable columns for absent kinds. Decision 1 removes that cost: a kind enters with its caller.
4. **One `owned_record` with only the kinds that have a caller, owner-only until the privacy track lands, then model input through typed reads, with a written experience contract (chosen).**

## Decision

Option 4.

1. **One record table.** A personal record is an `owned_record` in the `learning` module ([ADR 0138](0138-domain-modules-own-rows-and-rules-and-agent-modules-host-runs.md)).
   - Each row carries its workspace and its owner account. Every statement binds both ([ADR 0084](0084-every-statement-on-workspace-data-binds-its-workspace.md)).
   - The owner row policy of ADR 0127 covers these tables when it covers the conversation tables.
   - Each change of the words writes a revision with its author kind and the hash of the exact text shown ([ADR 0132](0132-personal-records-are-written-by-the-persons-act-or-noted-from-their-own-words.md) decision 2).
   - A kind enters the schema with its first caller. `GOAL` is the first kind. Each other kind comes with the change that writes it.
2. **A goal is the person's own words.** Its text has 1 to 500 characters. It can link up to three practices that are in use in the workspace.
   - A person has at most three active goals in each workspace. The limit keeps the always-loaded block small (ADR 0132 decision 9).
3. **The person's act writes a record, and each act has its reverse.** The goal follows this table. Code applies it in the transaction of each act, under one lock for the owner in the workspace. A test covers each row.
   - Every act names the version of the record that the person saw. Every act, a state change too, makes a new version.
   - An act applies only when the version that it names is the current version. Retained history never makes an older version current.
   - Undo is the reverse act on the same path. It is not an act of its own.

   | State | Act or event | Next state |
   | --- | --- | --- |
   | No goal | **Save** | `ACTIVE`. The confirmation shows **Undo**. |
   | Any goal | **Undo** of the save | A **Forget** at the version that the save returned, with all of its effects |
   | `ACTIVE` | **Edit** the text or the practices | `ACTIVE`, with a new revision. The confirmation shows **Undo**. |
   | `ACTIVE` | **Undo** of an edit | An **Edit** back to the earlier words and practices, as a new revision. History is never rewritten. |
   | `CLOSED` | **Edit** | Refused. **Reopen** comes first. |
   | `ACTIVE` | **Close** | `CLOSED`. The goal's check-ins pause. The confirmation shows **Undo**. |
   | `CLOSED` | **Reopen**, or **Undo** of the close | `ACTIVE`. The check-ins that the close paused resume. |
   | Three goals `ACTIVE` | **Save**, **Reopen**, or **Undo** of a close | Refused. The list says to close a goal first. |
   | Any goal | **Forget**, after one confirmation | No goal, decision 6 makes its uses stale, and its check-ins stop. The journal records a deletion fact with the record ID only ([ADR 0140](0140-privacy-facts-go-to-a-journal-and-a-restore-reapplies-them.md)). A ready export of the owner expires. |
   | Any goal | An act that names a version other than the current one | Refused as changed since shown, under the owner lock. The list reloads (ADR 0132 decision 15). |
   | `ACTIVE` with a practice | The practice leaves the workspace practices | The link stays. The line says that the practice is no longer in use. **Edit** removes it. |
   | Any goal | The membership ends | Writes stop, except **Forget** and **Take along** in the leaving window of [ADR 0126](0126-a-person-has-one-relationship-and-a-personal-space.md) decision 6. The goal stays under ADR 0132 decision 10. |
   | Any goal | The leaving window ends under a Closed policy of ADR 0132 decision 10 | No goal, as after **Forget**. A ready export of the owner expires. |
   | Any goal | The account is erased, or the workspace is purged | No goal |

4. **A model never writes a record** (ADR 0132 decision 16). Hephaestus may offer "Save this as a goal?" with the exact words only after decision 6 holds. The save is still the person's act, and it binds the hash of the text that the person saw.
5. **One visible list: What Hephaestus keeps in mind.** It shows the help settings of the account and the records of the workspace.
   - Each line shows its text, its source ("You saved this on 26 November"), and where it can shape answers.
   - "Where it can shape answers" comes from the route policy of decision 6, never from a stored flag. Before decision 6 holds, the line says "Not used in answers".
   - Each line has the actions that its kind allows: **Edit**, **Close**, **Forget**, **Not right** (for noted lines) and **Keep** (for kept-in-mind lines) (ADR 0132 decisions 5 and 6). A help setting has **Reset**.
   - A goal with a linked practice shows the newest counted review of that practice on the person's own work after the save. It renders from the owner's item card read at each view ([ADR 0150](0150-an-outcome-renders-from-rows-as-an-item-card-or-a-citation-token.md)), and it is never stored.
6. **A record reaches a model turn only through a typed server read.**
   - `OwnedRecordReads.readForTurn` in `learning` serves the read. The agent runtime binds the record ID and its revision to the attempt's basis with `ConversationBasis.bind`, as source kind `OWNED_RECORD`, before the bytes leave the server ([ADR 0128](0128-a-derived-record-keeps-its-basis-and-revocation-travels-by-generation.md)).
   - A record never goes into the context folder or its archive. An archive is one conservative input, so a record in it would make every answer depend on every record.
   - The owner's app route reads records. A Slack turn under **Full** reads only the kinds that [ADR 0123](0123-slack-is-a-channel-into-the-one-conversation.md) decision 5 lists. The composer, a review and the digest never read them.
   - Every entry that a turn derives from a record carries the record in its basis. Replay, search, summaries, the digest, Slack and exports apply the route check to that basis.
   - **Forget** and **Not right** make every entry whose basis holds the record stale (ADR 0128). A stale entry stays in the owner's app view. It never reaches a model, Slack or an export again.
   - A stale entry's note names the record kind and the act, such as "This answer used a goal that you forgot" or "This answer used a preference that you marked not right".
   - The scope fence of #5141 stops each running turn that read the record ([ADR 0134](0134-the-authorization-recheck-runs-at-three-boundaries-with-two-authorization-generations.md)).
   - The read stays off until three things hold: the durable basis records the read (#5256), the route comes from the held turn before the first read, and the route check of the fourth rule covers every reader of the ledger.
   - No staff export holds an entry whose basis holds a record ([ADR 0125](0125-hephaestus-is-never-a-performance-instrument.md)).
   - The first model input is the help settings, then a goal's linked practices, then a goal's text.
7. **Help settings are a closed set on the account** (ADR 0132 decision 1).
   - The values are answer length, command first, language, form of address and help style.
   - Help style is **Give me a hint first** or **Just tell me**.
   - The person sets them in Settings. Each control is its own reverse, and **Reset** returns the defaults.
   - They hold no free text and no employer content.
   - A turn sets one from the person's own words only after decision 6 holds, as a save with **Undo**.
8. **What Hephaestus used** (ADR 0131 decision 16) sits under each answer. The server renders it from rows. No model writes it. It has four groups, in this order:
   1. **Cited:** the observations, problems and feedback that the answer links, from the link calls that succeeded, with the place in the work.
   2. **Read:** the context documents, the repositories and the practice definitions that the turn opened, and the messages that a search returned.
   3. **Shaped by:** the records and help settings that the turn read, each with its own actions.
   4. **Could not check:** each source that the turn tried to open and could not, with the reason in plain words when the stored result still holds it.

   - Every turn also reads the conversation and the page that the person asked from before it answers. The view says so in one fixed line. It lists earlier messages only when a search returned them.
   - Labels render from current rows at each view. A source that the owner can no longer read shows "No longer available", never its old title.
   - The list shows no tool name, prompt, reasoning, file path, model or internal ID.
   - Until the durable basis records how each source was read (#5277), the server takes **Cited**, **Read** and **Could not check** from the stored tool calls of the answer's turn and the error marks of their results. Then it takes them from `ConversationBasis.sourcesOf`.
   - A stored result can lose its resolved commit and its failure text. So before #5277, a repository shows its name without a commit, and a failure shows without its reason.
   - While the stored calls are the only evidence, the fixed line says that the list shows what Hephaestus opened, not everything that it read.
   - When none of the four groups lists a source, the view says that Hephaestus opened nothing beyond the conversation and the page.
   - An answer whose turn stored no tool calls and no basis says "Hephaestus did not record what this answer used."
9. **A message from Hephaestus says why it exists.** A check-in notice or a requested result carries its reason line and the agreement that allowed it, with that agreement's **Less often** and **Stop this check-in** ([ADR 0131](0131-a-person-has-one-conversation-in-each-workspace-and-its-answers-stream.md) decision 8, [ADR 0133](0133-a-check-in-is-an-agreed-contact-with-a-scope.md) decision 16).
10. **Hephaestus holds or yields on evidence.** When the person says that an answer or feedback is wrong, Hephaestus does these things:
    - It asks for the evidence that the person sees.
    - It says what it is not sure about.
    - It offers the **Disagree** control on the cited observation.

    A conversation alone never changes an observation, a mark or a copy on the work.
    Four records stay distinct on every surface: the observation, the developer's own account, an admin mark and a later review.
11. **A correction is the person's record.** **Correct this** on an answer saves a `CORRECTION` record in the person's words, with **Undo**. It is the second record kind. Under decision 6 it loads right after the goals.
12. **Hephaestus names when a person is the better help.** Examples are a career decision, a conflict with a colleague, a course rule, distress, or a question that the evidence cannot settle.
    - It offers **Prepare a question**. Code fills a draft with the person's own question and links to the item and the cited observation. The person edits it and copies it.
    - Hephaestus never sends the draft, never picks a person to contact, and never contacts anyone.
    - The distress reply stays fixed, with the support resources that the operator set ([Mentoring behaviour](../contributor/mentoring-behaviour.mdx#hard-cases)).
13. **Each state that a person sees has one fixed text from a registry.** No model writes it. Each state maps from one turn status, failure reason or delivery state, and offers a next action:

    | State | When | Next action |
    | --- | --- | --- |
    | Queued | The question is stored and waits for an earlier question or a free slot. A wait for capacity says so. | **Stop** |
    | Not available | The workspace has no ready model, or the member's AI choice is No AI | Who can change it |
    | Could not start | The start wait expired ([ADR 0147](0147-capacity-degrades-gracefully-and-a-question-is-never-refused-for-load.md)) | **Try again** |
    | The model stopped | The model or its provider stopped answering | **Try again**. If it repeats, who can check the model. |
    | Could not finish | The attempts ran out | **Try again** |
    | Could not check | A source did not open, or a capture failed. It is never a claim about the work ([ADR 0124](0124-hephaestus-gives-practice-feedback-with-a-conversation-and-practices-are-the-unit.md) decision 4). | **Try again**, where a retry can help |
    | Could not determine | The observation's outcome is Could not determine | The cited observation |
    | No longer available | Access to a source ended, or its owner erased it. The fence removed the content from the answer. | None. The rest of the conversation stays. |
    | Withheld | A destination policy or a deadline withheld delivery ([ADR 0122](0122-a-copy-on-the-work-that-misses-its-deadline-is-withheld.md)). It is never reported as missing evidence. | The reason, and **Request a new copy** where it applies |
    | Stopped | The person selected **Stop** | None |

    - An unknown reason shows the generic message of the [writing standard](../contributor/simplified-technical-english.mdx#error-messages). It never shows a guess.
14. **Speed has measured targets.**
    - Acknowledgement: the question shows as stored, with its state, within 1 second.
    - First useful output: the first visible unit of the answer within 10 seconds at p95 for a warm turn. A queued state is measured on its own and does not count.
    - Stop settles within its deadline ([ADR 0120](0120-a-conversation-thread-has-one-inbox-and-runs-one-turn-at-a-time.md) decision 9).
    - The server measures queue wait, first visible unit, final reply and Stop, split by cold and warm start and by background load.
    - These are targets, not a promise to the person. A p95 above target opens a performance issue.
15. **The first conversation answers a real question first.**
    - The header names the workspace. It says that Hephaestus is software and can be wrong, and that only the person reads the conversation in Hephaestus.
    - The empty conversation suggests questions about the person's own items and practices.
    - In the conversation, Hephaestus offers a goal or a check-in only after the person's first useful interaction. That is an answer that the person marked **Helpful**, or a reply on an item card.
    - The rhythm choice at first sign-in stays as ADR 0133 decision 12 says.
    - Each kind of offer shows at most once in 30 days, with **Not now**.
    - The empty and the unavailable states say what will appear and who can fix it.
16. **The first useful week is one release check.** A scripted walk covers these steps:
    - the first answer about real work, with **What Hephaestus used**;
    - the first review result that reaches the person;
    - a disagreement on a wrong detail;
    - a goal saved and undone, then saved;
    - the next work on the goal's practice, shown on the goal's line;
    - an agreed check-in notice, opened.

    Each step has a scenario test ([Mentoring evaluation](../contributor/mentoring-evaluation.mdx#journey-evaluation)).
17. **A due occurrence of an agreed check-in that writes a message is a fixed-text notice.** This amends ADR 0133 decisions 7 and 17.
    - The notice is the occurrence's whole effect: the check-in's title, its reason line, and a link to what it is about. No model writes it.
    - The notice holds no words of a record and no linked practice. A goal follow-up links to the goal's line.
    - A deterministic pre-check decides whether a template has something to say, such as a material change of the active work. With nothing to say, the occurrence is skipped.
    - The model runs only when the person opens the notice, as an ordinary interactive turn on the route where the person opens it.
    - **Check my drafts before review** keeps ADR 0133 decision 11: its occurrence requests the private check as a task, with no notice.
    - The dispositions stay those of ADR 0133 decisions 6 and 9.
    - Templates are data rows. The first one is a dated follow-up on one goal, at a date that the person chooses.
    - A closed goal pauses its check-ins and a reopen resumes them. A forgotten goal stops them.
18. **Help steps back only when the person accepts.** This amends ADR 0130 decisions 9 and 11.
    - After the evidence of ADR 0130 decision 10, Hephaestus offers once, with that evidence, one step back on that practice.
    - A step goes from **Explain** to **Hint**, then **Ask first**, then **Step back** (ADR 0130 decision 9). A help style of **Give me a hint first** starts at **Hint**.
    - Acceptance is an act with **Undo**. It writes the help mode for that practice. A decline keeps the mode until new evidence.
    - A direct request for more help always wins in its turn.
    - A Not met observation after a step-back never lowers help silently. Hephaestus says so and offers to go back.
19. **The mentoring journey is evaluated as one unit:** a first answer, a later attempt, a disagreement, a next step and an agreed follow-up.
    - Scripted cases in CI check the deterministic parts: the state texts, **What Hephaestus used**, and no record in a forbidden destination. The destinations include a check-in notice, a summary and the digest.
    - A blinded human review scores groundedness, usefulness, calibration, agency and contact fit before each release that changes a part of the core.
    - Learning outcomes are studied only with consent ([ADR 0141](0141-research-datasets-build-on-the-research-module.md)). No staff view or metric counts the outcomes of one person ([ADR 0125](0125-hephaestus-is-never-a-performance-instrument.md)).

### What this changes in other ADRs

- ADR 0132 decision 2: a kind enters the schema with its first caller. Decision 11: the first save is the person's act in the list, and the save in the conversation waits for decision 6. Decision 1: a turn sets a help setting only after decision 6. Decision 6: **Forget** and **Not right** make the entries derived from a record stale, as decision 6 above says.
- ADR 0133 decision 7: decision 17 replaces the background turn with a fixed-text notice for a check-in that writes a message. Decision 17: a closed goal pauses its check-ins.
- ADR 0130 decisions 9 and 11: decision 18 makes the step-back an offer that the person accepts.
- ADR 0131 decision 16 stays. Decision 8 above gives its contents.

## Consequences

- Good: every part of the core is useful alone. A goal shows the next work on its practice before any model reads it.
- Good: one record table, one list and one typed read serve every later kind. A new kind adds a value and a caller, not a guard set.
- Good: each answer says what it cited, read and could not check. A person can tell evidence from context.
- Good: a failure has a name and a next step, so a slow or failed turn never looks like a claim about the work.
- Bad: until #5256 and the route proof land, answers do not use the person's goals or help settings. The list says so on each line.
- Bad: three active goals is a limit that some people will hit.
- Bad: the fixed-text notice cannot adapt its words to the week. The person must open it to get a written answer.
- Bad: the latency targets can fail on a small host. We measure first and promise nothing.
- Bad: every answer reads every active goal once decision 6 holds. One **Forget** then makes every answer since the save stale. The answers stay readable to the person, but Hephaestus no longer reads them, so the conversation loses that context.
- Bad: a check-in notice cannot name the goal's words. The person opens the goal's line or the notice to see them.

## Confirmation

Not enforced. Each implementing pull request adds its checks, and its review catches a missing one. They are:

- OwnedRecordStateTableTest has one case for each row of decision 3.
- OwnedRecordOwnerOnlyTest fails when another member, a workspace admin, an instance admin, a user view or an elevation reads a record or learns that it exists. It covers one owner in two workspaces.
- OwnedRecordPersonDataTest fails when the owner's export leaves out a record, or holds another account's record. It also fails when an operator preview, export or erasure receipt holds a record row or count, or an erasure or replay leaves one.
- An architecture rule fails when a class outside `learning` reads the record rows. Another fails when a run kind reads a record before decision 6 opens the typed read.
- AnswerUsedViewTest fails when **What Hephaestus used** shows a tool name, a file path or an old title of a source that the owner can no longer read. It also fails when it leaves out a cited row or a failed read.
- TurnStateTextTest fails when a state of decision 13 has no fixed text, or two states share one.
- HelpSettingsTest fails when a help setting accepts a value outside its closed set, or **Reset** leaves a value.
- StaleDerivedEntryTest fails when an entry whose basis holds a forgotten record reaches replay, search, a summary, the digest, Slack or an export.
- CheckInNoticeTextTest fails when a check-in notice holds the words of a record or a linked practice.
- The conversation latency meters of decision 14 have a registration test.
- The journey scenarios of decision 16 run in the release check.

## Revisit trigger

- People ask for more than three active goals in one workspace.
- People open fewer than a third of their check-in notices over four weeks.
- The p95 of the first useful output stays above 10 seconds after a performance fix.
- An evaluation case shows a record in a destination that decision 6 forbids.

## Sources

- Nielsen, [Response times: the 3 important limits](https://www.nngroup.com/articles/response-times-3-important-limits/), Nielsen Norman Group, 1993. One second keeps the flow of thought, and ten seconds is the limit of attention without feedback. Decision 14 takes its two targets from these limits.
- Bastani et al., [Generative AI without guardrails can harm learning](https://doi.org/10.1073/pnas.2422633122), PNAS, 2025. Students with an answer-giving tutor did better while assisted and worse afterwards. Decisions 7 and 18 keep the hint first when the person wants it, and step back only by agreement.
- Google PAIR, [Explainability + Trust](https://pair.withgoogle.com/chapter/explainability-trust/), People + AI Guidebook. It advises to show the data sources that a result used, so that people calibrate their trust. Decision 8 follows it.

## Already priced and declined

These options of the held ADR 0156 ([#5222](https://github.com/hephaestus-build/Hephaestus/blob/e86554063efdc09e1788226f7f7525bfc34bf639/docs/decisions/0156-a-developer-keeps-one-learning-goal-and-hephaestus-revisits-it-on-their-next-work.md#considered-options)) stay declined:

- **A goal that the model writes from the conversation** (its option 3). Other people's text can plant a goal. Decision 4 keeps the person's act.
- **A free-text goal that the model reads before the basis exists** (its option 4). Each review found a new copy path. Decision 6 waits for the basis.
- **A turn that knows the chosen practice before the route proof** (its option 5). Slack history and the staff export carried it. Decision 6 waits for the route proof.
- **A model turn at the check-in time** (its option 6). It costs a call when nothing changed. Decision 17 uses a fixed-text notice.

ADR 0156's own choice is declined too: one chosen practice on a card that no model ever reads. It does not let a goal shape help.
