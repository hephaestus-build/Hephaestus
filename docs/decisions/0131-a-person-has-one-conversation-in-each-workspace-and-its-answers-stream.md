# ADR 0131: A person has one conversation in each workspace, and its answers stream

**Status:** Accepted
**Date:** 2026-11-24
**Authors:** Felix T.J. Dietrich

**Supersedes:** [ADR 0082](0082-a-developer-has-one-heph-conversation-and-its-ledger-is-hephs-memory.md)

**Builds on:**

- [ADR 0120](0120-a-conversation-thread-has-one-inbox-and-runs-one-turn-at-a-time.md) (threads, inbox items and turns)
- [ADR 0079](0079-one-agent-system.md) (one sandbox for each subject)
- [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) (runs, leases and the outbox)
- [ADR 0093](0093-members-see-activity-a-workspace-can-publish-its-public-work-and-private-practice-feedback-has-one-reader.md) (private practice feedback has one reader)

**Relates to:**

- [ADR 0150](0150-an-outcome-renders-from-rows-as-an-item-card-or-a-citation-token.md) (item cards and citation tokens)
- [ADR 0123](0123-slack-is-a-channel-into-the-one-conversation.md) (the Slack route)
- ADR 0132 and ADR 0133 (proposed in #4865: personal records and check-ins)

## Context

[ADR 0082](0082-a-developer-has-one-heph-conversation-and-its-ledger-is-hephs-memory.md) states the conversation at 1.0 as 21 rules.
The proposals of this set change many of them: [ADR 0120](0120-a-conversation-thread-has-one-inbox-and-runs-one-turn-at-a-time.md) changes steering, Stop, submissions, the commit and the digest. [ADR 0123](0123-slack-is-a-channel-into-the-one-conversation.md) changes Slack. ADR 0132 and ADR 0133 change memory and the digest.
A reader would have to subtract several proposals from one accepted ADR to learn what the conversation does.

This ADR supersedes ADR 0082 once.
It states the conversation as rules, in the terms of ADR 0120, and its table says where each rule of ADR 0082 now lives.
The other ADRs of the set do not amend ADR 0082.

The conversation agent of ADR 0120 runs the turns of these threads. Hephaestus is the application, not an agent.

## Decision drivers

- A person has one place to talk to Hephaestus in each workspace. The app shows all of it.
- An answer starts fast, because the person waits for it.
- A message that Hephaestus starts never moves under the person's eyes and never changes after it is stored.
- A reply a day later finds the context of the item that it is about.
- Erasure, export and tenancy work on rows that Hephaestus owns.

## Considered options

1. **Keep ADR 0082 and add each proposal as an update block.** The reader subtracts several documents. ADR 0082 would get its third and fourth update, which the README answers with a superseding ADR.
2. **One conversation across all workspaces.** A single transcript mixes employers. SQL tenancy then needs an exception for each entry, and a lesson from one team can reach another team's turn.
3. **Many chats for each person, as general assistants have.** Context splits, and a result has no one home. [ADR 0057](0057-one-heph-conversation-per-developer.md#considered-options) and ADR 0082 already priced and declined this.
4. **One conversation for each account and workspace, held in private threads, with streamed answers (chosen).**

## Decision

Option 4.

1. **One conversation for each account and workspace.** The account owns it. It is the set of the account's private threads in that workspace ([ADR 0120](0120-a-conversation-thread-has-one-inbox-and-runs-one-turn-at-a-time.md) decision 1). These are the main thread, message threads and work threads.
2. **A message thread** starts under one message and never merges back. It is one level deep. A Slack direct message thread reply opens one.
3. **A work thread** has the key account, workspace and item. It starts when the person selects **Reply** on an item card, or asks from the extension on the item's page. Its anchor is the item's current results, read from rows at each turn.
4. **A proposed cross-workspace view** shows the conversations of all the account's workspaces together. Each message carries a workspace chip. The view reads each workspace under its own tenancy and stores nothing.
5. **Only the owner reads the conversation.** No staff role reads it. ADR 0125 (proposed in #4863) deletes View as user.
6. **Hephaestus answers where its model is ready,** within the member's AI choice. A member who chose No AI still reads the conversation. Hephaestus writes no message and no item card about that member.

   Inbox items, one turn at a time, cancel, steering and Stop are [ADR 0120](0120-a-conversation-thread-has-one-inbox-and-runs-one-turn-at-a-time.md) decisions 2 to 9. This ADR does not restate them.

7. **An answer to the owner in the app is a streamed response.** It streams token by token while the turn runs. Each visible unit passes the known-check rule of [ADR 0150](0150-an-outcome-renders-from-rows-as-an-item-card-or-a-citation-token.md) decision 4 before it streams.
   - A Slack answer posts as one complete message ([ADR 0120](0120-a-conversation-thread-has-one-inbox-and-runs-one-turn-at-a-time.md) decision 6).
8. **A message from Hephaestus is written complete.** A background turn commits it whole, at its commit, and never streams it. It has a message summary, a reason line, and at most three more lines.
9. **An entry that Hephaestus wrote never changes.** A wrong message gets a correction below it, with a reference to the wrong message.
   - An entry that the person wrote in Slack follows Slack ([ADR 0123](0123-slack-is-a-channel-into-the-one-conversation.md)). A Slack edit stores a new revision and keeps the earlier one. A Slack delete redacts the text.
   - An entry that the person wrote in the app never changes.
10. **The client groups consecutive messages from Hephaestus,** such as "3 reviews". The stored messages do not change.
11. **The view never jumps.** If the person has scrolled up or types, a "New" marker shows where unread messages start.
12. **A requested result appears where it was requested,** on the route of the thread that asked ([ADR 0120](0120-a-conversation-thread-has-one-inbox-and-runs-one-turn-at-a-time.md) decision 8). A task kind can limit its result to the app (ADR 0120 decision 16).
13. **The ledger is the memory of the conversation.** Each turn rebuilds its request from rows. The ledger, the personal records of ADR 0132 and the summaries are the only memory.
14. **Commit, then show.** The server commits a row before a client shows it. No row stores a partial answer. The stream travels in `NOTIFY` frames below 8000 bytes.
15. **Compaction** covers only the committed prefix. A pending message is never in a summary. An entry of inbox events keeps its provenance mark.
16. **"What Hephaestus used"** under each answer lists the personal records and entries that the answer drew on.
17. **Hephaestus reads, and never builds or writes.** It reads its context folder and loads repositories. It cannot build, run tests or write to a repository.
18. **Erasure stops the ledger.** Each ledger write holds the owner's person-data write fence. Entries expire 400 days after they were written, unless the person keeps an item (ADR 0132).
19. **Ratings come from Helpful and Not helpful only.** Hephaestus reads no Slack reaction.
20. **Every model call of a turn counts against that turn.** No usage report shows conversation usage for one person.
21. **The weekly digest** stays opt-in for a person who does not turn on the weekly reflection. For a person who does, the reflection replaces the digest and carries its links (ADR 0133).

### What this changes in other ADRs

ADR 0082 is superseded. Each of its decisions lives here now:

| ADR 0082 decision | Now |
| --- | --- |
| 1. One conversation | Decision 1. Steering returns at the tool-result boundary, for compatible input only ([ADR 0120](0120-a-conversation-thread-has-one-inbox-and-runs-one-turn-at-a-time.md) decision 9). |
| 2. Heph threads | Decision 2, as message threads. Decision 3 adds work threads. |
| 3. Only the owner reads it | Decision 5. No instance admin reads the conversation through View as user. |
| 4. Heph answers where its model is ready | Decision 6 |
| 5. The answer appears where the developer asked | Decision 12 and [ADR 0123](0123-slack-is-a-channel-into-the-one-conversation.md). A background message can also go to the Slack direct message. |
| 6. A Slack reply is never posted twice | [ADR 0123](0123-slack-is-a-channel-into-the-one-conversation.md) decision 7 and [ADR 0151](0151-each-external-write-has-one-delivery-key-and-is-read-back-never-retried-blindly.md). The key is the covered set of source keys, not one question. |
| 7. Stop | [ADR 0120](0120-a-conversation-thread-has-one-inbox-and-runs-one-turn-at-a-time.md) decision 9. Stop settles within a deadline. |
| 8. Ratings | Decision 19 |
| 9. The ledger is the memory | Decision 13. ADR 0132 adds noted records, so the ledger is no longer the only memory. |
| 10. Context management | Stays in substance. Decision 15 adds the provenance mark. |
| 11. Submissions | [ADR 0120](0120-a-conversation-thread-has-one-inbox-and-runs-one-turn-at-a-time.md) decisions 2, 3 and 7. The dedupe key takes the role of `request_id`. The limit of 3 follow-ups goes. |
| 12. Commit, then show | Decision 14 |
| 13. A turn stores its entries once | [ADR 0120](0120-a-conversation-thread-has-one-inbox-and-runs-one-turn-at-a-time.md) decision 10, the commit rule |
| 14. Heph shows a piece of feedback at most once | [ADR 0150](0150-an-outcome-renders-from-rows-as-an-item-card-or-a-citation-token.md). A citation token takes the role of `show_feedback`. |
| 15. Heph reads, and never builds or writes | Decision 17 |
| 16. Erasure stops the ledger | Decision 18 |
| 17. Retention | Decision 18. A kept-in-mind item that the person keeps follows record retention (ADR 0132). |
| 18. Turn accounting | Decision 20 |
| 19. The weekly digest is opt-in | Decision 21 and ADR 0133 |
| 20. A note points to the digest | Stays in substance |
| 21. A model writes the digest and nudges | [ADR 0120](0120-a-conversation-thread-has-one-inbox-and-runs-one-turn-at-a-time.md) decision 4. A digest and each check-in are scheduled inbox items on the main thread. A check-in of ADR 0133 replaces the nudge. The `DIGEST` run kind goes. |

[ADR 0093](0093-members-see-activity-a-workspace-can-publish-its-public-work-and-private-practice-feedback-has-one-reader.md) rule 18 stays. The conversation has the same one reader.

## Consequences

- Good: one record serves the app, Slack, the extension, search, export and erasure.
- Good: a reply on a card a day later opens the work thread of that item, with its results.
- Good: a lost sandbox loses no conversation, because every turn rebuilds from rows.
- Good: one ADR replaces ADR 0082, so a reader subtracts nothing.
- Bad: a person who works in several workspaces has several conversations. The cross-workspace view shows them together, but a turn reads only its own workspace.
- Bad: a correction leaves the wrong message in place. The person reads both.
- Bad: the upgrade maps existing conversations to main threads, and the threads of ADR 0082 to message threads.

## Confirmation

Not enforced. The implementing issues add the checks below, and review of each implementing pull request catches a missing one. They are:

- OneConversationPerWorkspaceTest fails when one account has two main threads in one workspace.
- WorkThreadKeyTest fails when two replies on one card open two work threads.
- EntryRevisionTest fails when an entry that Hephaestus wrote changes.
  It also fails when a Slack edit of a person's entry loses the earlier revision.
- NoStaffConversationReadTest fails when a staff route returns an entry of another account.
- The tests of ADR 0082 that cover its decisions 4, 8, 10, 12 and 15 to 20 stay.

## Revisit trigger

- People ask for one transcript across workspaces, and an employer boundary allows it.
- A work thread grows past the budget of one turn's context in normal use.

## Sources

- PostgreSQL 18, [NOTIFY](https://www.postgresql.org/docs/18/sql-notify.html). A payload "must be shorter than 8000 bytes", and events are "not delivered until and unless the transaction is committed". This keeps the frames and the commit order of decision 14.
