# ADR 0082: A developer has one Heph conversation, and its ledger is Heph's memory

**Status:** Accepted
**Date:** 2026-11-10
**Authors:** Felix T.J. Dietrich

**Supersedes:** [ADR 0057](0057-one-heph-conversation-per-developer.md)

**Builds on:** [ADR 0079](0079-one-agent-system.md), [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md), [ADR 0046](0046-heph-follows-its-model-not-a-workspace-switch.md), [ADR 0045](0045-activity-counts-work-and-never-ranks-people.md), [ADR 0056](0056-one-practice-model-for-items-and-scans.md)

## Context

ADR 0057 set the conversation model of Heph.
ADR 0068 and ADR 0079 then replaced its runtime and its digest in two update blocks.
A reader had to subtract both to learn what Heph does at 1.0.
One sentence about the ledger was also wrong. Since #3574, no row stores a partial answer.

This ADR states the conversation at 1.0 as rules.
[ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) and [ADR 0079](0079-one-agent-system.md) own where a turn runs.
The [conversation runtime](../contributor/unified-pi-runtime.mdx) page owns the detailed design.
A rule that main does not satisfy yet says "1.0 target" ([ADR 0070](0070-1-0-is-a-cutover-release.md)).

## Decision drivers

- A developer has one place to talk to Heph. The app shows all of it.
- Feedback in conversation counts as delivered only when the developer can see Heph's words.
- No conversation state lives only in a process or a sandbox.
- Erasure, export and tenancy work on rows that Hephaestus owns, with SQL that Hephaestus writes.
- Heph knows when and where each message was written.

## Considered options

1. **Many chats in the app, and one chat for each Slack message.** Context splits, and Slack threads fill the app list.
2. **One conversation with no Heph threads.** A Slack thread reply has no home, and a side question moves the main conversation.
3. **One conversation for each developer and workspace, with Heph threads that fork it at one message (chosen).**

[ADR 0079](0079-one-agent-system.md#considered-options) prices the runtime options: a shared loop host, a split Pod and a workflow engine.

## Decision

1. **One conversation.** Each developer has one Heph conversation in each workspace. The account owns it (`mentor_conversation.account_id`). Heph has no chat list, regenerate, edit-and-resend, Start fresh or steer. An absorbed account's entries follow the claiming account's entries, and its summaries are erased.
   Enforced by: `MentorConversationServiceIntegrationTest#shouldCreateOneMainConversationPerAccountOnTheFirstWriteAndFindItAgain`, `MentorLedgerConcurrencyIntegrationTest#shouldCreateOneMainConversationWhenAnAccountsFirstTwoWritesArriveAtOnce`, `MentorAccountAbsorptionIntegrationTest#shouldMergeTheAbsorbedMainAfterTheClaimingMainAndMoveEveryOtherConversation`.
2. **Heph threads.** A Heph thread forks the conversation at one top-level message and never merges back. It is one level deep. A message has at most one thread.
   Enforced by: `MentorConversationServiceIntegrationTest#shouldRejectAThreadWhenTheParentIsInAThreadOrNotAMessage`, `MentorLedgerConcurrencyIntegrationTest#shouldOpenOneThreadWhenTwoRepliesOpenItAtOnce`.
3. **Only the owner reads it.** An instance admin can read it through View as user and cannot write to it.
   Enforced by: `UserViewIntegrationTest#shouldNotDiscloseAConversationWhenItBelongsToAnotherMember` and `#shouldRefuseToDeleteTheMessageAndRecordNothingWhenAnAdminViewsTheUser`.
4. **Heph answers where its model is ready** ([ADR 0046](0046-heph-follows-its-model-not-a-workspace-switch.md)), within the member's AI choice. A member who chose no AI still reads the conversation.
   Enforced by: `MentorAccessIntegrationTest#shouldKeepTheConversationReadableAndRefuseNewQuestionsWhenTheMemberChoseNoAi`.
5. **The answer appears where the developer asked.** The app shows everything. A Slack question gets its answer in Slack. An app question never goes to Slack. A Slack question gets 👀 while Heph works. At the top level, one reply follows. In a Slack thread, the answer streams.
   Enforced by: `SlackMentorProjectorIntegrationTest#shouldNeverTouchSlackWhenTheQuestionWasAskedInTheApp` and `#shouldStreamTheAnswerAndStopItUnderFeedbackButtonsWhenAThreadTurnRuns`.
6. **A Slack reply is never posted twice.** If Slack cannot confirm a reply, the server reads the Slack history once. The app marks a reply that Slack lacks or cut as "Not fully delivered to Slack".
   1.0 target: the reply is a `slack_post` action in the outbox ([ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) decision 10), in [#4081](https://github.com/hephaestus-build/Hephaestus/pull/4081).
   Enforced by: `SlackMentorProjectorIntegrationTest#shouldFailTheAnswerAndItsFeedbackAndNeverPostItAgainWhenTheSlackHistoryDoesNotHoldIt`.
7. **Stop.** The developer can stop any turn in the app. A stopped running turn keeps its cut-off answer. A stopped queued turn keeps its question. In a Slack thread, Slack's Stop stops only that thread's running Slack turn.
   Enforced by: `MentorTurnServiceIntegrationTest#shouldStopAQueuedTurnAtOnceAndKeepItsQuestionWhenTheDeveloperStopsIt`, `SlackMentorProjectorIntegrationTest#shouldStopOnlyTheStreamingTurnWhenSlackReportsThatTheDeveloperStoppedIt`.
8. **Ratings come from the Helpful and Not helpful buttons only.** Hephaestus reads no Slack reaction.
   Enforced by: `MentorConversationControllerIntegrationTest#shouldSetAndClearTheVoteWhenTheDeveloperRatesHephsMessage`. Not enforced for reactions. Review of a Slack event handler catches a reaction reader.
9. **The ledger is the memory.** Three workspace-scoped tables hold it: `mentor_conversation`, `mentor_entry` and `mentor_turn`. Each turn rebuilds its request from rows. Heph keeps no separate memory and writes no notes.
    Enforced by: `DataIsolationArchitectureTest`, `MentorContextPathBuilderIntegrationTest#shouldStartAtTheFirstKeptEntryWhenASummaryReplacedEarlierEntries` and `#shouldUseTheSummaryAtOrBeforeItsParentWhenAThreadIsBuilt`.
10. **Context management.** Pi's own compaction runs before the prompt, and the turn pays for it. When a turn settles, older tool results become references. `search_conversation` is a PostgreSQL full-text search over messages.
    Enforced by: `heph-host.spec.ts` ("a conversation past the working budget is compacted before the prompt"), `MentorEntryServiceIntegrationTest#shouldFindOnlyMessagesWhenTheTreeAlsoHoldsToolResultsSummariesAndRedactedEntries`.
11. **Submissions.** A client `request_id` makes each question idempotent. At most 3 follow-ups wait behind a running turn.
    Enforced by: `MentorLedgerConcurrencyIntegrationTest#shouldQueueOneTurnWhenTheSameSubmissionArrivesTwiceAtOnce`, `MentorTurnServiceIntegrationTest#shouldRefuseAFourthFollowUpWhenThreeAlreadyWait`.
12. **Commit, then show.** The server commits a row before the app or Slack shows it. The live stream sends a snapshot on each connect, then events. No row stores a partial answer. It travels as Base64 frames in `NOTIFY`, each below the 8000-byte limit. The app reads TanStack Query pages, and one `EventSource` writes live changes into the same cache.
    Enforced by: `MentorTurnServiceIntegrationTest#shouldSendThePartialAnswerWithoutWritingTheTurnAndRefuseOneAfterTheDeveloperStoppedIt`, `MentorLiveIntegrationTest#shouldSendTheCurrentStateAgainWhenTheClientReconnects`, `webapp/src/hooks/conversation-cache.test.ts`.
13. **A turn stores its entries once, at its end.** A failed attempt keeps only its summary. Its retry starts clean. The lease and the retry of a turn follow [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) decision 3, and [#4072](https://github.com/hephaestus-build/Hephaestus/pull/4072) delivers it.
    Enforced by: `MentorTurnServiceIntegrationTest#shouldKeepOnlyTheSummaryOfAFailedTurnSoTheNextTurnStartsFromIt`.
14. **Heph shows a piece of feedback at most once.** `link_result` and `link_problem` cite and deliver nothing. `show_feedback` claims ready `CONVERSATION` feedback for the turn attempt. The receipt names the message that showed it. A lost attempt's claim is `UNCONFIRMED` before its retry starts.
    Enforced by: `MentorTurnCompletionIntegrationTest#shouldDeliverTheFeedbackOnTheMessageThatShowedItWhenAnAppAnswerEndsDone` and `#shouldSettleALostAttemptsClaimUnconfirmedSoItsRetryCannotClaimItAgain`.
15. **Heph reads, and never builds or writes.** Heph reads its context folder and loads repositories. A hidden or unmonitored repository gives `NOT_VISIBLE`, as a missing one does. Heph cannot build, run tests or write to a repository.
    1.0 target: the folder lives in the conversation's sandbox ([ADR 0079](0079-one-agent-system.md) § Subject and sandbox). No pull request delivers it yet. On main, a tools Pod serves each turn attempt.
    Enforced by: `ScmRepositoryCheckoutsIntegrationTest#shouldGiveOneAnswerWhenRepositoryIsNotVisibleToTheWorkspace`.
16. **Erasure stops the ledger.** Each ledger write holds the owner's person-data write fence. After an erasure request, Heph stores nothing new about that person.
    1.0 target: an erasure or a change of access deletes the conversation's sandbox at once ([ADR 0079](0079-one-agent-system.md) § Data model). No pull request delivers it yet.
    Enforced by: `AccountErasureIntegrationTest#shouldForgetAHephAttemptRegisteredAfterThePreviewAndRefuseARegistrationAfterTheSuppression`, `MentorTurnServiceIntegrationTest#shouldStoreNothingAndRefuseNewQuestionsWhenTheDevelopersErasureWasRequestedWhileTheAttemptRan`.
17. **Retention.** The server erases each entry 400 days after it was written, in the two weeks before. An erased entry erases every summary that covers it. A tool result that can copy another source follows the workspace's Slack retention. [Data retention](../admin/data-retention.mdx) gives the setting.
    Enforced by: `MentorRetentionIntegrationTest#shouldEraseEveryEntryInTheTwoWeeksBeforeTheConversationRetentionAndKeepTheRest`, `MentorEntryServiceIntegrationTest#shouldRedactTheEntryAndEverySummaryThatCoversItWhenAMessageIsErased`.
18. **Turn accounting.** Every model call of a turn counts against that turn, also in a lost attempt or a summary. No usage report shows Heph usage for one person.
    Decided, not shipped: one `llm_usage_event` row for each call, as [ADR 0079](0079-one-agent-system.md) § Data model states. No pull request delivers it yet.
    Enforced by: `MentorTurnCompletionIntegrationTest#shouldBillEachLostAttemptFromTheProxyWhenTheReaperTakesItsLeaseBack`. Not enforced for persons. Review of a usage DTO catches a person field.
19. **The weekly digest is opt-in.** It is off by default, and one account switch covers every workspace. It is due on Monday from 09:00 to 21:00 local time. A quiet week writes nothing. It shows no score, rank or streak ([ADR 0045](0045-activity-counts-work-and-never-ranks-people.md)).
    Enforced by: `WeeklyDigestComposerTest#shouldBeDueOnlyOnMondayFromNineToNineInTheEveningLocalTimeWhenTheJobRuns`, `WeeklyDigestIntegrationTest#shouldWriteNothingWhenTheWeekWasQuiet`.
20. **A note points to the digest.** A Slack note, or else an email, says that it is ready. The note has no counts, no practice names and no feedback text.
    1.0 target: the Slack note is a `slack_post` action, in [#4081](https://github.com/hephaestus-build/Hephaestus/pull/4081). The email already goes through the outbox.
    Enforced by: `SlackWeeklyDigestPointerIntegrationTest#shouldPostOneNoteWithoutContentsAndLearnTheDmWhenADigestIsWritten`.
21. **A model writes the digest and nudges.** Each is a `digest` run in the conversation's sandbox ([ADR 0079](0079-one-agent-system.md) § Interactive and proactive work). Admission checks each claim against its sources. The destination rules of [ADR 0057](0057-one-heph-conversation-per-developer.md#weekly-digest) choose its facts.
    Nudges follow the digest opt-in, so they are off by default. A nudge outside the conversation carries no content, as the digest note does.
    Decided, not shipped: no pull request delivers it yet. On main, `WeeklyDigestComposer` writes the digest from facts.
    Not enforced until the `digest` run ships.

## What changed, and why

- **The partial answer is not stored** (ADR 0057 § The ledger). A row write for each snapshot filled the write-ahead log. Since [#3574](https://github.com/hephaestus-build/Hephaestus/pull/3574), the answer is stored once, at its end. PostgreSQL limits a payload to 8000 bytes, so the snapshot travels in frames.
- **A model writes the digest** (ADR 0057 § Weekly digest). The maintainer decided this on 2026-11-09. Admission now covers the new claim about a person that the rejection named ([ADR 0079](0079-one-agent-system.md) § Interactive and proactive work).
- **A conversation has one sandbox while it is in use** (ADR 0057 § The Heph host, ADR 0068 decision J). ADR 0079 and ADR 0081 decide it. The ledger stays the memory, and the sandbox is a cache.
- **Entries expire after 400 days** ([#3746](https://github.com/hephaestus-build/Hephaestus/pull/3746)). ADR 0057 set no age limit. Copies of Slack messages now expire with Slack's retention.

## Consequences

- Good: one ledger serves the app, Slack, search, export and erasure.
- Good: a turn runs anywhere, because it rebuilds from rows. A lost sandbox loses no conversation.
- Good: a developer never sees a Slack reply twice, and Heph shows a piece of feedback at most once.
- Bad: a Slack reply that Slack cannot confirm reaches the developer only in the app.
- Bad: a long conversation pays for a new compaction after each erasure that a summary covers.
- Bad: a model-written digest is a new claim about a person. It depends on admission, and a refused claim is not sent.
- Bad: the upgrade to the ledger deleted all earlier Heph chats ([ADR 0057](0057-one-heph-conversation-per-developer.md#exception-drop-the-chat-tables-in-the-same-release)).

## Confirmation

Each decision above names its test or gate.
Decision 21 has none. Decisions 8 and 18 are enforced only in part. Code review catches the rest.

## Revisit trigger

- Pi Durable has a stable `Storage` contract, hook parity under `Harness`, and an error for a writer that lost ownership.
- An evaluation shows that Heph loses context that a compaction instruction or a separate memory would keep.
- Team channels need Heph. A channel is a conversation that the workspace owns, and it needs its own ADR.
- A Heph tool gains another side effect. Its retry then needs the replay guard that `show_feedback` has.
- Admission refuses most claims of `digest` runs.

## Sources

- PostgreSQL 18, [NOTIFY](https://www.postgresql.org/docs/18/sql-notify.html). A payload "must be shorter than 8000 bytes", and events are "not delivered until and unless the transaction is committed". This gives the frames and the commit order of decision 12.
- Slack, [chat.startStream](https://docs.slack.dev/reference/methods/chat.startStream/). Top-level streaming "is only supported in channels where the whole channel is one session". This is why a top-level reply is one post and a thread reply streams (decision 5).

## Already priced and declined

Each line links the section that priced the option.

- [ADR 0057](0057-one-heph-conversation-per-developer.md#considered-options): many chats in the app, and one chat for each Slack message.
- [ADR 0057](0057-one-heph-conversation-per-developer.md#considered-options): one conversation with no Heph threads.
- [ADR 0057](0057-one-heph-conversation-per-developer.md#considered-options): a sandbox for each developer, with a fixed lock. ADR 0079 chose it in a changed form: one sandbox for each conversation, as a cache.
- [ADR 0057](0057-one-heph-conversation-per-developer.md#pi-durable-is-not-adopted): Heph on Pi Durable, with a Postgres `Storage` adapter.
- [ADR 0057](0057-one-heph-conversation-per-developer.md#considered-options): compaction after the answer.
- [ADR 0057](0057-one-heph-conversation-per-developer.md#considered-options): AI SDK UI (`useChat`) with a custom transport.
- [ADR 0057](0057-one-heph-conversation-per-developer.md#considered-options): the Vercel Chat SDK with its Slack adapter.
- [ADR 0057](0057-one-heph-conversation-per-developer.md#amendment-of-2026-10-22-slacks-stop-in-a-heph-thread): no Slack Stop in a thread. The maintainer chose Slack's Stop on 2026-10-22.
- [ADR 0057](0057-one-heph-conversation-per-developer.md#considered-options): a digest that Java writes from facts. ADR 0079 replaced it with a model-written digest.
- [ADR 0057](0057-one-heph-conversation-per-developer.md#context-folder-and-turn-sandbox): a virtual tree of generated files over Git.
- [ADR 0057](0057-one-heph-conversation-per-developer.md#context-folder-and-turn-sandbox): one warm sandbox for each conversation. ADR 0079 chose it, with deletion on erasure and when idle.
- [ADR 0057](0057-one-heph-conversation-per-developer.md#context-folder-and-turn-sandbox): Pi inside the sandbox for each turn. ADR 0079 chose it: the loop runs in the conversation's sandbox.
- [ADR 0057](0057-one-heph-conversation-per-developer.md#context-folder-and-turn-sandbox): Pi code mode in a shared host, and the earlier program sandbox for each run.
