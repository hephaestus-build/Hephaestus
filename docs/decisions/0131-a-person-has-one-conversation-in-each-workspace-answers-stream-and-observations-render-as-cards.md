# ADR 0131: A person has one conversation in each workspace, answers stream, and observations render as cards

**Status:** Proposed
**Date:** 2026-11-23
**Authors:** Felix T.J. Dietrich

**Supersedes:** [ADR 0082](0082-a-developer-has-one-heph-conversation-and-its-ledger-is-hephs-memory.md), as ADR 0120 (proposed in #4862) amends it

**Builds on:** [ADR 0079](0079-one-agent-system.md) (one sandbox for each subject), [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) (runs, leases and the outbox), [ADR 0093](0093-members-see-activity-a-workspace-can-publish-its-public-work-and-private-practice-feedback-has-one-reader.md) (private practice feedback has one reader)

**Relates to:** [ADR 0132](0132-personal-records-are-written-by-the-persons-act-or-noted-from-their-own-words.md) (personal records), [ADR 0133](0133-a-check-in-is-an-agreed-contact-with-a-scope.md) (check-ins), [ADR 0139](0139-the-contact-policy-decides-whether-hephaestus-may-interrupt-a-person-now.md) (the contact policy)

## Context

[ADR 0082](0082-a-developer-has-one-heph-conversation-and-its-ledger-is-hephs-memory.md) states the conversation at 1.0 as 21 rules.
ADR 0120 (proposed in #4862) changes five of them: steering, Stop, submissions, the commit and the digest.
ADR 0121 and ADR 0123 (both proposed in #4862) change where feedback on the work and Slack answers come from.
A reader must now subtract three proposals from one accepted ADR to learn what the conversation does.

Hephaestus now also speaks first, with results, check-ins and item cards.
ADR 0082 has no rule for a message that Hephaestus starts. It also has no rule for a claim about a result, or for the home of an item's results.
A model that writes an outcome as free text can contradict the admitted observation.
The real constraint is one record of the conversation, in rows that Hephaestus owns. In that record, no prose can state a result that the rows do not hold.

This ADR states the conversation as rules, in the terms of ADR 0120.
The **conversation agent** is the agent that runs turns on one account's private threads.
Hephaestus is the application. It is not an agent.

## Decision drivers

- A person has one place to talk to Hephaestus in each workspace. The app shows all of it.
- An answer starts fast, because the person waits for it.
- A message that Hephaestus starts never moves under the person's eyes and never changes after it is stored.
- A claim about a result always matches the admitted rows, also when the rows change later.
- A reply a day later finds the context of the item that it is about.
- Erasure, export and tenancy work on rows that Hephaestus owns.

## Considered options

1. **Keep ADR 0082 and add each proposal as an update block.** The reader subtracts four documents. ADR 0082 would get its third and fourth update, which the README answers with a superseding ADR.
2. **One conversation across all workspaces.** A single transcript mixes employers. SQL tenancy then needs an exception for each entry, and a lesson from one team can reach another team's turn.
3. **Many chats for each person, as general assistants have.** Context splits, and a result has no one home. ADR 0057 already priced and declined this.
4. **Prose for results.** The model writes outcomes and delivery states in its own words. The words can contradict the rows, and nothing corrects them when the rows change.
5. **One conversation for each account and workspace, held in private threads, with streamed answers and results as cards (chosen).**

## Decision

Option 5.

1. **One conversation for each account and workspace.** The account owns it. It is the set of the account's private threads in that workspace (ADR 0120 decision 1). These are the main thread, message threads and work threads.
2. **A message thread** starts under one message and never merges back. It is one level deep. A Slack direct message thread reply opens one.
3. **A work thread** has the key account, workspace and item. It starts when the person selects **Reply** on an item card, or asks from the extension on the item's page. Its anchor is the item's current results, read from rows at each turn.
4. **A proposed cross-workspace view** shows the conversations of all the account's workspaces together. Each message carries a workspace chip. The view reads each workspace under its own tenancy and stores nothing.
5. **Only the owner reads the conversation.** No staff role reads it. ADR 0125 (proposed in the product and trust pull request) deletes View as user.
6. **Hephaestus answers where its model is ready,** within the member's AI choice. A member who chose No AI still reads the conversation. Hephaestus writes no message and no item card about that member.

7. **Every input is an inbox item. One turn at a time runs on each thread** (ADR 0120 decisions 2 to 6). Threads run in parallel.
8. **A message cancels a queued or leased background turn. A compatible message steers an interactive turn at the next tool-result boundary. Events never interrupt.** ADR 0120 decision 9 owns the table. This ADR adds nothing to it.
9. **Stop** ends any turn of the person, under the settlement deadline of ADR 0120 decision 9.
10. **An answer is a streamed response.** It streams token by token while the turn runs. A sentence that holds a citation token streams with a placeholder until its check passes.
11. **A message from Hephaestus is written complete.** A background turn commits it whole, at its commit, and never streams it. It has a message summary, a reason line, and at most three more lines.
12. **A stored entry never changes.** A wrong message gets a correction below it, with a reference to the wrong message.
13. **The client groups consecutive messages from Hephaestus,** such as "3 reviews". The stored messages do not change.
14. **The view never jumps.** If the person has scrolled up or types, a "New" marker shows where unread messages start.
15. **A requested result appears where it was requested,** on the route of the thread that asked (ADR 0120 decision 8).

16. **An item card is a projection of rows about one item.** It shows the problems with their labels and cited lines. It also shows the delivery state of each copy, the task status and "Could not check". No model writes it. An update needs no turn.
17. **The card exists from the first queued review of the item.** "Reviewing #42" is a state of the card. Its lifecycle is in progress, open and closed. Its revision only grows.
18. **The same card shows in four places.** These are each message that cites it, the Activity section, the practice page and the extension.
19. **A claim about a result uses a citation token.** The server renders the token from current rows. The model never writes an outcome or a delivery state as free text. Prose may discuss a card. It mints no new verdict.
20. **A message says "posted" only if the copy was delivered when the message was stored.** Its token renders the current delivery state after that.
21. **Activity gets a proposed section that lists the owner's item cards** in three groups: In progress, Open feedback and Closed. Only the owner reads it. Main's Needs you stays as it is.
22. **Card controls.** Mark card done closes a card, and its reverse opens it again. Mute card notifications stops messages and notifications about the card. Typed commands follow ADR 0121 (proposed in #4862).

23. **The ledger is the memory of the conversation.** Each turn rebuilds its request from rows. The ledger, the personal records of [ADR 0132](0132-personal-records-are-written-by-the-persons-act-or-noted-from-their-own-words.md) and the summaries are the only memory.
24. **Commit, then show.** The server commits a row before a client shows it. No row stores a partial answer. The stream travels in `NOTIFY` frames below 8000 bytes.
25. **Compaction** covers only the committed prefix. A pending message is never in a summary. An events entry keeps its provenance mark.
26. **"What Hephaestus used"** under each answer lists the personal records and entries that the answer drew on.
27. **Hephaestus reads, and never builds or writes.** It reads its context folder and loads repositories. It cannot build, run tests or write to a repository.
28. **Erasure stops the ledger.** Each ledger write holds the owner's person-data write fence. Entries expire 400 days after they were written, unless the person keeps an item ([ADR 0132](0132-personal-records-are-written-by-the-persons-act-or-noted-from-their-own-words.md)).
29. **Ratings come from Helpful and Not helpful only.** Hephaestus reads no Slack reaction.
30. **Every model call of a turn counts against that turn.** No usage report shows conversation usage for one person.
31. **The weekly digest** stays opt-in for a person who does not turn on the weekly reflection. For a person who does, the reflection replaces the digest and carries its links ([ADR 0133](0133-a-check-in-is-an-agreed-contact-with-a-scope.md)).

### What this changes in other ADRs

- ADR 0082 is superseded. Its decisions 1, 2, 3, 5, 7, 11, 13 and 21 are restated above, as ADR 0120 amends them. Decisions 4, 8, 9, 10, 12 and 15 to 20 stay in substance.
- ADR 0082 decision 3 changes. No instance admin reads the conversation through View as user.
- ADR 0082 decision 9 changes. Hephaestus now writes noted records ([ADR 0132](0132-personal-records-are-written-by-the-persons-act-or-noted-from-their-own-words.md)). The ledger is no longer the only memory.
- ADR 0082 decision 14 changes. A citation token takes the role of `show_feedback`. Feedback in conversation counts as delivered when a committed entry cites it.
- ADR 0082 decisions 5 and 6 on Slack are owned by ADR 0123 (proposed in #4862).
- [ADR 0093](0093-members-see-activity-a-workspace-can-publish-its-public-work-and-private-practice-feedback-has-one-reader.md) rule 18 stays. The item card and the Activity section have the same one reader.

## Consequences

- Good: one record serves the app, Slack, the extension, search, export and erasure.
- Good: a result shows on its card before any model runs, and it stays correct when the rows change.
- Good: a reply on a card a day later opens the work thread of that item, with its results.
- Good: a lost sandbox loses no conversation, because every turn rebuilds from rows.
- Bad: streamed prose can still state a verdict without a card. A lint in the release evaluation cases finds these phrases, but it runs only before a release.
- Bad: a person who works in several workspaces has several conversations. The cross-workspace view shows them together, but a turn reads only its own workspace.
- Bad: a correction leaves the wrong message in place. The person reads both.
- Bad: the upgrade maps existing conversations to main threads, and the threads of ADR 0082 to message threads.

## Confirmation

Not enforced yet. The implementation adds these tests:

- `OneConversationPerWorkspaceTest` fails when one account has two main threads in one workspace.
- `WorkThreadKeyTest` fails when two replies on one card open two work threads.
- `CardWithoutModelTest` fails when an item card update needs a turn.
- `VerdictWithoutCardLintTest` fails when a stored answer states an outcome of a cited result without a citation token.
- `PostedClaimTest` fails when a message says "posted" for a copy that was not delivered at its commit.
- `NoStaffConversationReadTest` fails when a staff route returns an entry of another account.
- The tests of ADR 0082 that cover decisions 4, 8, 9, 12 and 15 to 20 stay.

## Revisit trigger

- People ask for one transcript across workspaces, and an employer boundary allows it.
- The lint finds verdict phrases without a card in more than 1 % of stored answers.
- A work thread grows past the budget of one turn's context in normal use.

## Sources

- PostgreSQL 18, [NOTIFY](https://www.postgresql.org/docs/18/sql-notify.html). A payload "must be shorter than 8000 bytes", and events are "not delivered until and unless the transaction is committed". This keeps the frames and the commit order of decision 24.
- Anthropic, [Streaming input](https://code.claude.com/docs/en/agent-sdk/streaming-vs-single-mode). A message sent during tool calls reaches the model after those calls finish. Decision 8 relies on this boundary through ADR 0120.

## Already priced and declined

- [ADR 0057](0057-one-heph-conversation-per-developer.md#considered-options): many chats in the app, and one chat for each Slack message.
- [ADR 0057](0057-one-heph-conversation-per-developer.md#considered-options): one conversation with no threads.
- [ADR 0057](0057-one-heph-conversation-per-developer.md#pi-durable-is-not-adopted): the conversation on Pi Durable, with a Postgres `Storage` adapter.
- [ADR 0057](0057-one-heph-conversation-per-developer.md#considered-options): compaction after the answer.
- [ADR 0057](0057-one-heph-conversation-per-developer.md#considered-options): AI SDK UI (`useChat`) with a custom transport, and the Vercel Chat SDK with its Slack adapter.
- [ADR 0082](0082-a-developer-has-one-heph-conversation-and-its-ledger-is-hephs-memory.md#considered-options): many chats in the app, and one conversation with no threads.
- ADR 0120 (proposed in #4862): two kinds of turn, with yield and requeue, and a queue for each input source.
- ADR 0120 (proposed in #4862): a heartbeat loop, and an interrupt on every message.
