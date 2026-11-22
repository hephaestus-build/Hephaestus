# ADR 0086: A practice review stages, seals, then composes, and each stage records why it stops

**Status:** Accepted
**Date:** 2026-11-10
**Authors:** Felix T.J. Dietrich

**Supersedes:** [ADR 0061](0061-review-seals-then-feedback-composes.md), [ADR 0062](0062-the-item-is-the-review-scope.md), [ADR 0063](0063-each-practice-is-staged-when-it-ends.md), [ADR 0069](0069-practices-run-when-ready-and-stay-current-by-their-sources.md)

**Builds on:** [ADR 0085](0085-an-observation-has-one-of-four-outcomes-and-standing-reads-the-newest-decision.md), [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md), [ADR 0079](0079-one-agent-system.md), [ADR 0064](0064-one-evidence-contract-for-every-producer.md), [ADR 0070](0070-1-0-is-a-cutover-release.md)

**Relates to:** [ADR 0083](0083-each-purpose-uses-one-governed-model-and-every-call-is-counted.md)

## Context

Four ADRs described the way from an item change to delivered feedback. ADR 0069 alone had 974 lines and eight updates.
ADR 0079 then moved the composer and the fence, and renamed the run kinds in an update of two ADRs.
This ADR states the review lifecycle at 1.0 as rules. It also takes over "a skip is not a claim" from ADR 0074 § Selection.
This lifecycle is the core of non-negotiable 1: feedback earns trust or it is not sent.

The [pipeline guide](../contributor/practice-review-pipeline.mdx) and the [review glossary](../contributor/practice-review-glossary.mdx) own the detail.

## Decision drivers

- Each stage can stop, and each stop has its own recorded reason. A stop is never a claim about the work.
- One review at the newest version is worth more than several reviews at stale versions.
- A practice that ended does not run again after a loss.
- Selection, readiness and currentness are derived from rows that exist. No plan table and no workflow engine.
- A model writes what a person reads. Hard rules exist only for trust, safety and tenancy.

## Considered options

1. **One review for each event, with all candidates admitted at the end.** Every push runs every practice, and one lost Pod loses every measured practice.
2. **A plan table or a workflow engine for each item.** Every fact in the plan is derivable, and an engine journal is a second store of personal data.
3. **The item as the key over existing rows, staging for each practice, one seal, then a composer with tools (chosen).**

## Decision

1. **The model:** practice, item, review, result, outcome, standing and currentness by source fingerprint are as [ADR 0085](0085-an-observation-has-one-of-four-outcomes-and-standing-reads-the-newest-decision.md) decides.
2. **The runtime:** the `REVIEW` and `REVIEW_COMPOSE` runs, the sandbox and the outbox are as [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) decides.
3. **Stages point one way.** Selection, capture, admission, seal, composition and delivery follow in this order. Feedback reads sealed results and never writes a result. A composition failure never changes a result.
   Enforced by: `ReviewToDeliveryContractIntegrationTest#shouldWithholdTheFeedbackAsNotComposedWhenTheFinalComposerAttemptHasNoTimeLeft`.
4. **The item is the review scope.** An item event waits in its item's quiet window, which a newer event extends up to a cap. Merged, closed and a submitted review never wait. At the readiness edge, one review runs at the newest event. The older events end `COALESCED`.
   Enforced by: `QuietWindowTest` and `ScmEventReviewIntegrationTest#shouldDecideEveryOccasionOfABurstOnSeveralItemsWithOneReviewEach`.
5. **Readiness for each practice.** A milestone (opened, ready, a request) measures every practice it selects. A continuing event measures only practices with no current result. A practice in flight holds the window with `REVIEW_IN_PROGRESS`. A practice that needs the checks waits with its readiness reason, and no cap ends the wait. A request reuses current results.
   Enforced by: `ScmEventReviewIntegrationTest#shouldHoldALaterOccasionWhileTheEarlierReviewOnlyStagedThePractice` and `#shouldRecordChecksCompletedOncePerSettledRunSetOnlyWhenTheWholeListOfRunsEnded`.
6. **Supersede.** At claim, a newer event at another version ends a queued event review as `REPLACED`, and no sandbox starts. Its unmeasured practices wait for the next edge. A person's request is never replaced. At each practice start, a changed source ends the practice as `REPLACED`, and an unverifiable one as `READINESS_REFUSED`.
   Enforced by: `ScmEventReviewIntegrationTest#shouldCarryWhatAReplacedQueuedReviewSelectedIntoTheReviewOfTheNewerHead` and `#shouldLeaveAPersonsRequestedReviewWhenTheWorkMoves`.
7. **A skip is not a claim.** Only Applies when can record Does not apply, and only when complete captured evidence proves every alternative false. An undecidable alternative keeps the practice. Selection, readiness, capacity, deadline or capture failure records its own reason, never Does not apply. No model selects practices.
   Enforced by: the invariant of `AppliesWhenCheck`, and `AppliesWhenExecutionIntegrationTest#shouldStopWithItsReasonInsteadOfDoesNotApplyWhenTheDiscussionWasNotReadWhole` and `#shouldRecordTheCaptureFailureWithoutAnInapplicabilityResultWhenTheSourceIsUnavailable`.
8. **Stage each practice when it ends.** The server admits the candidate at once with the one verifier ([ADR 0064](0064-one-evidence-contract-for-every-producer.md)). It refuses a citation of an undeclared source. It keeps the candidate `STAGED` on its membership with the cited texts. A staged candidate changes no visible feedback or standing. A retry measures only practices that are not staged.
   Enforced by: `ReviewToDeliveryContractIntegrationTest#shouldKeepStagingPrivateAndSealAfterAnInterruptedAttemptWithoutItsFolder` and `#shouldWithholdAnUndeclaredSourceAndComposeOnlyTheValidSibling`.
9. **The fence is `(run_id, lease_epoch)`.** A stage or seal write fails unless the `REVIEW` run is `LEASED` with that epoch and an unexpired lease.
   [#4080](https://github.com/hephaestus-build/Hephaestus/pull/4080) fences the sandbox's routes on the run's claim. 1.0 target: the stage and seal writes still check the review job's attempt in `ReviewInvocationFence`. No pull request moves that check to the run yet.
   Enforced by: `SandboxTokenOutOfTurnIntegrationTest` for the routes, and `ReviewToDeliveryContractIntegrationTest#shouldRefuseLateStagingAndSealingAfterTheAttemptLeaseExpires` for the attempt.
10. **One seal.** The seal is one transaction under the item lock. It writes the results and problems of the staged rows. A staged result that is stale at the seal ends `WORK_CHANGED` and is never delivered. A retry at a moved version seals what it staged and ends the rest `REPLACED`. A cancel before the seal withholds staged practices with `CANCELLED`.
    Enforced by: `ReviewToDeliveryContractIntegrationTest#shouldWriteOneResultSetWhenTwoSealRequestsRace`, `#shouldEndAStagedObservationWhenTheWorkMovesBeforeTheSeal` and `#shouldClearStagingWithItsCancellationCauseAtTerminalSettlement`.
11. **Compose after the seal.** A `REVIEW_COMPOSE` run continues the `REVIEW` run in the review's sandbox and spends no attempt. A scan, a backfill or a review with no eligible destination composes nothing.
    Enforced by: `AgentRunsIntegrationTest#shouldContinueAnEndedRunAsTheNextStepOfItsWorkWithoutSpendingAnAttempt` and `FeedbackCompositionInputsTest#shouldKeepScanBackfillAndUnsupportedRequestsQuiet`.
12. **The composer has tools and no provider token.** It has `check_package`, `publish_package` and `edit_own`. The tools refuse an entry without an admitted, current result. They also refuse a claim from a withholding, and a thread that a person resolved. The server writes, and a retry reads back by the draft ID instead of posting again.
    Enforced by: `ReviewToDeliveryContractIntegrationTest#shouldCheckWithoutPreparingFeedbackAndRefusePublicationAfterItsLeaseExpires` and `FeedbackRepairIntegrationTest#shouldKeepThePostedCopyWhenARecipientTakesControlBeforeTheEdit`.
13. **One notifying write, then quiet corrections.** A review makes one GitHub review or one GitLab bulk publication. The composer reads the provider back and corrects with `edit_own`. A composition has at most 3 edits, counted on the feedback row, and an attempt at most 12 model calls. Then `STEP_LIMIT` stops it, and a posted copy stays.
    Enforced by: `FeedbackRepairIntegrationTest#shouldSpendThreeEditsAcrossRevisionsAndNeverResetTheBudgetOnReplay` and `#shouldKeepImmutableWordsAndEditTheSameProviderObjectWithOneNotification`.
14. **Each composition stop is recorded.** A destination that is not composed records `NOT_COMPOSED` or a more specific reason on its feedback row. If no attempt confirms every destination, the review closes Partial with `COMPOSITION_UNCONFIRMED`.
    Enforced by: `AgentOrphanRecoveryIntegrationTest#shouldCloseAnAdmittedReviewAsPartialWhenEveryAttemptIsLost`.
15. **Delivery checks again.** At send, a result whose sources changed withholds the feedback with `REVIEWED_VERSION_CHANGED`. A missing proof withholds it with `UNCORROBORATED` and never says that the work changed.
    Enforced by: `FeedbackGateIntegrationTest#shouldWithholdUnavailableCurrentProofWithoutClaimingTheWorkChanged`.
16. **Some work gets private feedback only.** A submitted pull request review gets no feedback on the work. A draft, merged work and closed work get feedback on the practice page and in conversation only. The one exception for merged and closed work is the workspace setting **Post feedback after merge**. It is off by default. When a draft becomes ready, that milestone reviews every practice again.
    Enforced by: `PublicReviewEligibilityTest#shouldPublishOnlyPinnedAuthorResultsWithProviderCopySupport` for submitted reviews, and `FeedbackGateIntegrationTest#shouldWithholdFeedbackOnMergedOrClosedWorkAndKeepPrivateDestinationsWhenTheWorkspaceDoesNotPostAfterMerge`, `#shouldPostFeedbackOnMergedWorkWhenTheWorkspacePostsAfterMerge` and `#shouldWithholdFeedbackOnADraftEvenWhenThePracticeAsksForNoStateAndPostFeedbackOnceItIsNoLongerADraft`.
17. **The review command is a request.** `/hephaestus review` goes through the request service with the checks of **Request review**. Only a new comment that is exactly the command counts. A redelivered comment starts no second review.
    Enforced by: `ReviewCommandListenerTest#shouldNotAnswerAgainForACommentDeliveredAgain` and `#shouldStartNoReviewWhenTheCommentHoldsMoreThanTheCommand`.
18. **Tools check facts, and a model never checks its own facts.** A rubric critique pass is decided and ships off. It turns on only when the composition eval shows a gain.
    Not enforced. The eval ([feedback quality evals](../contributor/feedback-quality-evals.mdx)) runs outside CI and has no rollout gate yet.
19. **Decided, not shipped.** Problem lineage (New, Unchanged, Updated, No longer present) and the server's resolve of a thread from complete proof stay decided. The same holds for repeat detection by problem identity, and the `reopened` and `converted_to_draft` events. No pull request delivers them yet.
    Not enforced. Today the composer sees its earlier feedback, and preparation withholds a word-for-word repeat with `REPEATS_DELIVERED_NOTE`.

Prompt layout, caching and the stagger are tuning, so the [pipeline guide](../contributor/practice-review-pipeline.mdx#admit-results) owns them. ADR 0083 owns the wire protocols.
The guide's [resume rules](../contributor/practice-review-pipeline.mdx#resuming-a-lost-attempt) replace the failure table of ADR 0063.

## What changed, and why

- **Composition runs in the sandbox** (ADR 0061 option 3). The composer is a model with tools that reads untrusted text. ADR 0079 runs such loops in the subject's sandbox, which already exists for the review ([ADR 0079](0079-one-agent-system.md#update--2026-11-09-the-review-run-kinds-are-review-and-review_compose)).
- **The fence moves** from job, attempt and worker to `(run_id, lease_epoch)` (ADR 0079 § Claim, priority and reap).
- **The composition skip of ADR 0061 goes.** #3238 merged without it. The delivery gate already withholds feedback on ended results with `REVIEWED_VERSION_CHANGED`, so the skip saves only model calls.
- **Freshness does not narrow to declared sources.** ADR 0069 D8 expected a volume that only the loop mounts. ADR 0079 gives each sandbox one container, so a session can read every captured file. A result therefore records the fingerprints of every captured source.
- **No `ITEM_IN_DRAFT` reason.** The prepared change withholds draft feedback with the existing `ITEM_STATE_INELIGIBLE`, whose user text already names drafts.

## Consequences

- Good: each stop has a reason on the event, the membership, the review or the feedback row.
- Good: a lost sandbox costs only the practices that were running. A composition failure leaves the results intact.
- Good: a push burst gives one review, and a practice that does not read CI never waits for CI.
- Bad: the quiet window delays the first feedback by up to its length.
- Bad: two small reviews can run at one version when the checks settle after the window.
- Bad: each ready transition costs one full review.
- Bad: an edit to any captured source makes every result of that capture stale. This includes practices that did not declare that source.
- Bad: composition on ended results spends model calls that delivery then withholds.
- Bad: until lineage ships, feedback cannot say that a problem is gone, and repeats rest on the composer's judgment.

## Confirmation

Each decision names its test. Decisions 18 and 19 are not enforced, and code review catches a change to them.
Decision 16 is enforced for submitted reviews only. The prepared change adds `FeedbackGateIntegrationTest` cases for drafts and merged work.

## Revisit trigger

- An item needs more than one active review at a time.
- The measured window delay (spike M-WINDOW of ADR 0069) harms the feedback loop.
- A practice must judge CI that never settles.
- The host refuses reads outside the declared sources. Then results record only the declared fingerprints.
- Measured noise on the work: volume, rate-limit answers or repeats. Then the matching rule under "Already priced and declined".

## Sources

- Martin Kleppmann, [How to do distributed locking](https://martin.kleppmann.com/2016/02/08/how-to-do-distributed-locking.html). A fencing token is "a number that increases" each time a client takes the lock. The store "rejects the request" with an older token. This is why every stage and seal write names its epoch (decision 9).
- Huang et al., [Large language models cannot self-correct reasoning yet](https://arxiv.org/abs/2310.01798), 2023. "LLMs struggle to self-correct their responses without external feedback." So tools and the provider's readback check facts, not a second model (decisions 12 and 18).
- GitHub Docs, [Configure automatic code review](https://docs.github.com/en/copilot/how-tos/use-copilot-agents/request-a-code-review/configure-automatic-review). Review of drafts is an option to "enable". A peer bot thus leaves drafts alone unless asked, as decision 16 does.

## Already priced and declined

- [ADR 0061](0061-review-seals-then-feedback-composes.md#considered-options): composition in the measuring process, and composition as a job kind with its own Pod and attempts.
- [ADR 0062](0062-the-item-is-the-review-scope.md#considered-options): one review for each event, and a state table for each item and practice.
- [ADR 0063](0063-each-practice-is-staged-when-it-ends.md#considered-options): admit all at the end, write each result at once, and checkpoint inside a practice.
- [ADR 0069 § Trigger model](0069-practices-run-when-ready-and-stay-current-by-their-sources.md#trigger-model): a window held until CI settles, no window, and a CI cap timer.
- [ADR 0069 § Trigger model](0069-practices-run-when-ready-and-stay-current-by-their-sources.md#trigger-model): compose at a milestone, a plan table, and one review per practice.
- [ADR 0069 § Trigger model](0069-practices-run-when-ready-and-stay-current-by-their-sources.md#trigger-model): a merge into a running review.
- [ADR 0069 § Prompt layout](0069-practices-run-when-ready-and-stay-current-by-their-sources.md#prompt-layout): intent first, a tool schema per practice, and native Anthropic only.
- [ADR 0069 § Caching](0069-practices-run-when-ready-and-stay-current-by-their-sources.md#caching): automatic caching only, the 1-hour TTL, batch APIs, and a routing header.
- [ADR 0069 § Feedback on the work](0069-practices-run-when-ready-and-stay-current-by-their-sources.md#feedback-on-the-work): a summary with no memory, a fixed template, and a one-shot composer.
- [ADR 0069 § Feedback on the work](0069-practices-run-when-ready-and-stay-current-by-their-sources.md#feedback-on-the-work): a fact-checking second model, and provider writers in the composer.
- [ADR 0069 § Quality](0069-practices-run-when-ready-and-stay-current-by-their-sources.md#quality): a correctness self-critique, a confidence gate, and probation formulas.
- [ADR 0069 § Source contract versions](0069-practices-run-when-ready-and-stay-current-by-their-sources.md#source-contract-versions): several contract versions side by side.
- [ADR 0069 § Removed rules](0069-practices-run-when-ready-and-stay-current-by-their-sources.md#removed-rules): the living summary, the placement table, and the thread cap.
- [ADR 0069 § Removed rules](0069-practices-run-when-ready-and-stay-current-by-their-sources.md#removed-rules): the item budget and pause, and the write budget.
- [ADR 0069 § Removed rules](0069-practices-run-when-ready-and-stay-current-by-their-sources.md#removed-rules): a window per kind, the check run, a three-value draft setting, and the deadline switch.
- [ADR 0069 § Removed rules](0069-practices-run-when-ready-and-stay-current-by-their-sources.md#removed-rules): the interdiff resolve rule, and extra summary reasons.
- [ADR 0074](0074-a-reviews-width-and-count-follow-the-provider.md#considered-options): a model check that selects practices before the sessions.

## Update — 2026-11-14: `bash` reads undeclared sources, and this is accepted

A practice session has `bash`.
`bash` can read every captured file, including the files of sources that the practice does not declare.
The read tools refuse such a file, so they keep a session on its sources, but they are not a boundary.
The gap stays inside one review: a sandbox holds only its subject's capture.
This update accepts the gap for 1.0. It does not close it.

**Why it is accepted:**

- A second user or container for the tools is already declined: [ADR 0079 § Option 2](0079-one-agent-system.md#2-a-pod-for-each-conversation-with-a-split-loop-and-tools-rejected).
  The sandbox also runs as one unprivileged user with no privilege escalation. So it cannot start a process as another user.
- Landlock can restrict an unprivileged process, but it has costs that 1.0 does not pay.
  Node has no Landlock API, so the image needs a native helper. gVisor support is not verified. Some nodes run kernels without Landlock.
- Removing `bash` from practice sessions changes review quality. Literal reads and Git history use it. No eval proves that a session without `bash` reviews as well.

**What keeps results trustworthy:**

- For each practice, freshness records the fingerprint of every captured source, whatever the practice declares.
  The section above says this: "Freshness does not narrow to declared sources".
  An undeclared read thus cannot keep a stale result current. The cost is extra staleness.
  Enforced by: `DefaultReviewExecutionHandlerTest#shouldRecordEveryCapturedSourceFingerprintForEachPracticeWhateverItDeclares`.
- Admission refuses a result that cites or consulted an undeclared source, with `UNDECLARED_SOURCE_CITED`.
  So a result cannot rest on a source that its practice does not declare.
  Enforced by: `ReviewOutputServiceTest#shouldWithholdAResultThatConsultedASourceItsPracticeDoesNotDeclare` and `ReviewToDeliveryContractIntegrationTest#shouldWithholdAnUndeclaredSourceAndComposeOnlyTheValidSibling`.

**Residual risk:** an undeclared file can still influence the model's reasoning, without a citation.
The mitigation is the review prompt's rule that captured context is data, never instructions.

**Revisit trigger:** the isolation gate proves a per-process file boundary in the sandbox runtime, such as Landlock under gVisor, on the supported clusters.
Then the host refuses undeclared reads for `bash` too, and results record only the declared fingerprints (§ Revisit trigger).

## Update — 2026-11-17: freshness reads a practice's own sources

This update replaces "Freshness does not narrow to declared sources" (§ What changed, and why).
It also replaces the first rule under "What keeps results trustworthy" in the update of 2026-11-14.

**What production showed.** In workspace 10 from 16:11Z to 16:36Z, 68 of 92 practice starts read `STALE` and were withheld.
Every one of them named `scm.repository.tree` as changed, and 34 of them also named `scm.review-threads`.
One review lost all six practices that run with Approve first.
Two causes made the staleness:

- The tree fingerprint covered every branch and tag of the repository, so a push to any other branch changed it.
- Each practice recorded every captured source, so a bot's comment staled a practice that does not read the discussion.

**Decision.** A practice's result records the fingerprints of these sources only:

- the sources that the practice declares
- the sources that decide whether it applies
- the item's record, which every practice's inputs carry
- the change (`scm.pull-request.diff`) and the checkout (`scm.repository.tree`), which every session can read through Git.

The runtime keeps no record of which sources a session read.
A result's `consulted` sources are refused unless the practice declares them, so they add no source to this set.

**The checkout holds only the change's commits.** A pull request's checkout holds the reviewed commit, the review base and their history.
It holds no branch and no tag.
The tree fingerprint is therefore the two commits, and the server computes it from the stored version.
No practice in the default catalog resolves another branch or a tag.
The stored provider-ref observation (`repository_to_monitor.git_refs_fingerprint`) had no reader left, so it is removed.

**Residual risk that this adds.** A session can read an undeclared captured file with `bash`, and nothing records that read.
A change to such a file no longer ends the result.
So a result can stay current although a file that influenced its reasoning has changed.

**Why it is accepted:**

- The former rule had a measured, high cost. It withheld 68 of 92 practice starts, and a review lost every Approve-first practice.
- Admission still refuses a result that cites or consulted an undeclared source (`UNDECLARED_SOURCE_CITED`). A result cannot rest on such a source.
- With Approve first, a person reads the feedback before delivery.
- The review prompt still says that captured context is data, never instructions.

Enforced by:

- `DefaultReviewExecutionHandlerTest#shouldRecordForEachPracticeOnlyItsOwnSourcesWithTheItemRecordAndTheCheckout`: a bot's comment changes only the discussion, so it ends only the result of a practice that reads the discussion.
- `PullRequestTreeFingerprintIntegrationTest#shouldKeepAPracticeCurrentWhenAnotherBranchOrATagOfItsRepositoryMoves`: a push to another branch, or a new tag, leaves the tree fingerprint unchanged.
- `GitRepositoryManagerTest#shouldCaptureOnlyTheReviewedAndBaseCommitsSoAnotherBranchOrTagChangesNothingItCanRead`: the checkout holds the two commits and no ref.

**Revisit trigger:** the per-process file boundary in the sandbox ([ADR 0079](0079-one-agent-system.md)).
When the host refuses undeclared reads for `bash` too, the residual risk is gone.

## Update — 2026-11-17: `REPLACED` means a newer review, and owed practices run again

Production showed reviews whose practices were all withheld as `REPLACED`, although no newer review of the item was ever ordered.
A reason must say what happened, so the reasons split:

- `REPLACED`: a newer review of the item measured the practice or can still measure it. A newer review that ended without measuring it takes nothing over.
- `SOURCES_MOVED`: a source that the practice reads changed before it started. No newer review measured it or can still measure it.
- `NOT_RUN`: no attempt measured the practice before the run ended, by its deadline, a time-out, a watchdog or a lost sandbox. It says nothing about the work. The run keeps its own stop reason.

All three are carried, so the next readiness edge selects the practice again.
`NOT_RUN` is not withheld on purpose, so a review that owes it ends with `EXECUTION_INCOMPLETE`.

A moved source or a spent deadline is no provider event, so nothing would measure such a practice before the work changes.
An event review of an issue or a pull request can end with a `SOURCES_MOVED` or `NOT_RUN` practice while its work is still at its version.
It then records one follow-up occasion of the item at that version.
It uses the occasion machinery: the occasion joins the quiet window, selects no practice itself, and its readiness edge measures only the carried practices.
It keeps no record of the work: it reads the record that the review's own occasion retained, under that occasion's expiry.
A requested review, a cancelled one, or one of another item kind waits for the item's next event.

An occasion is unique per item, name and version, so a version gets one follow-up.
A practice that does not fit again stays carried until a real event.

Enforced by:

- `ResultAdmissionIntegrationTest#shouldCallAStalePracticeReplacedOnlyWhenANewerReviewMeasuredIt`
- `ResultAdmissionIntegrationTest#shouldCallAStalePracticeReplacedWhileANewerOpenReviewCanStillMeasureIt`
- `ResultAdmissionIntegrationTest#shouldCallAStalePracticeSourcesMovedWhenTheNewerReviewWithheldIt`
- `ResultAdmissionIntegrationTest#shouldAskOnceForAFollowUpThatReadsTheReviewsOwnOccasionWhenReviewsEndOwingPractices`
- `ResultAdmissionServiceTest#shouldStopTheOpenPracticesAsNotRunAndSealWhenTheRetryFindsTheSourcesStale`
- `ReviewJobCompletionServiceTest#shouldCarryEveryPracticeOfARunThatTimedOutBeforeItMeasuredOne`

## Update — 2026-11-21: a push measures only practices whose feedback reaches someone

Production reviewed a repository whose pull requests got several pushes within one review.
A review of 40 practices took about 16 minutes, so nearly every review met a push before its one seal (§ 10).
A push changes the sources of most practices, so most of the staged work ended `WORK_CHANGED`.
The six practices in Approve first were among them, and no feedback reached anyone.
Selection was the cause: each push measured every practice that reviews on pushes.
This included the 34 practices in feedback mode Off, whose feedback reaches no one.

This update extends § 5 and § 6:

- Occasions that all continue leave out a practice in feedback mode Off. A push, an edit and a follow-up occasion continue.
  The practice waits for a milestone or an outcome of its item, such as opened, ready, merged, closed or published.
  This also holds when it is carried from an older version, so it never follows a newer head.
  A practice that its own version still owes, for any carried reason, finishes at that version.
  Otherwise a practice that waits for the checks at opened would wait until merged.
  Also, the follow-up of a review that ran out of time would measure nothing.
- A practice in Off can review on no milestone or outcome of its item kind. Then it has no other moment.
  So its continuing occasions keep it. A conversation thread is such a kind: its one event continues.
- When every practice that a push selects is in Off, the push is withheld with `FEEDBACK_MODE_OFF`.
- A newer push can replace a queued review at claim. The review then withholds such a practice with `SOURCES_MOVED`.
  The review of the push does not measure it, and `REPLACED` means that a newer review measures the practice.
  When this holds for every practice, the review ends with `SOURCES_MOVED` too, since nothing replaces it.
  When a newer milestone waits, every practice is `REPLACED`, as before.
- An event review that has not started holds every newer occasion of its item that joins a window, with `REVIEW_IN_PROGRESS`.
  This holds whatever practices it lists. Thus, its claim replaces it, and no sandbox starts on the old head.
- The selection reads the effective feedback mode when it decides.
  A practice that changes from Off to an active mode is measured on the next push.
  That push must change what it reads, or the practice stays current.

The run already starts the practices whose feedback reaches someone first ([pipeline guide](../contributor/practice-review-pipeline.mdx#admit-results)).
So a push now measures only what someone reads, and a review of a push is about as short as those practices.
Feedback still describes only the head that it reviewed: the seal and delivery checks are unchanged.

Considered and rejected:

- A longer quiet window. It delays all feedback and does not make a review of 40 practices shorter.
- A seal per practice. It breaks the one consistent result set of § 10.

Enforced by:

- `ScmEventReviewIntegrationTest#shouldMeasureAPracticeInFeedbackModeOffAtAMilestoneButNotOnAPushUntilItsFeedbackReachesSomeone`
- `ScmEventReviewIntegrationTest#shouldWithholdAPushWhoseEverySelectedPracticeIsOffAndKeepAnOffPracticeThatReviewsOnNoMilestone`
- `ScmEventReviewIntegrationTest#shouldReplaceOnlyThePracticesThatTheReviewOfANewerPushMeasures`
- `ScmEventReviewIntegrationTest#shouldEndAReviewWhoseEveryPracticeWaitsForAMilestoneWithSourcesMovedWhenAPushReplacesIt`
- `ScmEventReviewIntegrationTest#shouldFinishAnOffPracticeThatItsVersionOwesOnTheChecksEvent`
- `ScmEventReviewIntegrationTest#shouldReplaceAQueuedReviewOfOnlyOffPracticesWhenAPushSelectsOthers`

## Update — 2026-11-21: an item review records problem lineage

This update ships the first part of decision 19: problem lineage for item reviews.
Thread resolution from lineage, repeats by identity and the composer's view of lineage stay decided, not shipped.

- When the seal writes an item review's result, it compares each problem with the previous comparable result.
  That is the newest earlier result of the same practice version, item and developer, with the same model method.
  The comparison writes one `problem_lineage` row for each problem: New, Unchanged or Updated.
  It writes no rows when there is no such result.
  The same holds when that result no longer verifies.
- Identity is the scan comparison's. A problem matches by its key.
  A problem with no key matches by the source and the normalized quote of its first citation.
- Unchanged and Updated compare the problem's content without line numbers and without the cited commit.
  Every push moves the head that a citation names.
- A previous problem that the newer result does not find is No longer present only with complete proof.
  The proof is the scan comparison's: a judged result of a changed version, unmarked and with trusted coverage.
  Item reviews add one more condition, because the model chooses the keys and can leave a problem out.
  Every text that the previous problem quoted must be gone from the newer review's capture.
- The capture decides as follows:
  - Only file versions count: texts that the capture stages at a commit.
    A record of the change, such as its unified diff, also holds the lines that the change removes.
  - The capture must hold the cited source whole, and it must hold the cited path.
  - A quote of the old side cites what the change removes, so it proves nothing.
  - The quote is searched first in the file at the cited path, at the reviewed version.
    A source that captures a change is then searched in every new file version, within a read budget.
    Leading and trailing blanks of each line are ignored.
  - A renamed file keeps its code at its new path, so the quote is still present.
  - A deleted file keeps its old text in the capture of a change.
    So its path is held, and its code is gone.
  - A file that the change no longer touches is not in the capture, so it proves nothing.
- Without that proof, the previous problem gets no row. A missing problem never reads as a repair.

Enforced by:

- `ResultAdmissionIntegrationTest#shouldRecordAProblemUnchangedUpdatedOrNewAgainstThePreviousComparableResult`
- `ResultAdmissionIntegrationTest#shouldRecordAProblemNoLongerPresentWhenAJudgedReviewOfAChangedVersionNoLongerFindsItAndItsTextIsGone`
- `ResultAdmissionIntegrationTest#shouldNotRecordAProblemNoLongerPresentWithoutCompleteProof`
- `ResultAdmissionIntegrationTest#shouldCompareWithNoResultThatAPersonMarkedIncorrect`
- `CapturedQuoteProbeTest`

## Update — 2026-11-21: problem lineage resolves and reopens threads

This update ships the second part of decision 19: the server resolves a thread from problem lineage, and reopens it.
Repeats by identity and the composer's view of lineage stay decided, not shipped.
[ADR 0069](0069-practices-run-when-ready-and-stay-current-by-their-sources.md) keeps the rule: resolution is a server path, not a model tool.

- **When.** The server decides after the seal commits the lineage rows, through the event publication registry.
  So a failed decision never undoes a result, and the registry delivers it again.
  A person's incorrect mark on a result also starts a decision.
  The server looks at each thread that a posted note of the developer's feedback starts on the item.
  When an edit made a newer revision of the feedback, the newest revision that owns the thread decides.
- **Which problems.** A note cites the problems that its composition support names for that copy.
  A note whose support is missing, or rests on a result without a problem, never resolves.
- **Resolve.** Every problem that the note cites must be No longer present now.
  Take the newest lineage row with the problem's identity, in the same practice, item and developer.
  The problem is No longer present now when that row is No longer present.
  Only rows of usable results count: an item review that is no trial, not outranked, and not marked incorrect.
  A usable result can be newer than the cited problem's own result and measured another way.
  It has another practice version or model method, or it ended as it was written.
  Lineage compares only within one measurement, so the chain from the cited problem breaks there.
  Then the problem has no standing.
  So a note that cites two problems resolves only after both are gone, in one review or in two.
  A lineage row records the problem's identity for this reason.
  The migration derives it for rows of a problem with a key: `key:` and the key.
  It drops the rows of a problem without a key, whose identity is a hash that SQL cannot reproduce exactly.
  A key is not unique across practices or items, so the match stays within one practice, item and developer.
- **Reopen.** When one cited problem is present again, the server reopens the thread.
  A later review finds the problem as New, Unchanged or Updated.
  A problem without standing does not reopen a thread: no proof is no claim.
  It withdraws a resolve that has not begun to write.
  The one exception is a person's incorrect mark on a result whose lineage proved a cited problem gone.
  The mark takes back the proof, so that resolved thread reopens when its problems lost their standing.
  A mark on a result that the note itself rests on reopens nothing.
  The note is retracted, and its thread stops.
  The server reopens only a thread that its own resolve may have reached.
  The row keeps that fact across asks, until a reopen is confirmed.
  A resolve that never started is withdrawn instead.
  The decision holds the item's lock, the lock of the seal, while it reads standing and asks.
  So no newer lineage commits in between, and two decisions for one thread never race.
  The server reopens only where the provider can tell its own reopen from a person's: on GitHub, not on GitLab.
- **A note closed as fixed.** On GitLab, a thread that the server resolved stays resolved, so its note no longer stands as advice.
  Any resolve that began counts, also an unconfirmed one.
  The history that the next review on the work reads marks that note `closedAsFixed`.
  A thread that a person changed is theirs, so its note is not marked.
  It names the practices whose every note in the piece is closed.
  The summary does not keep such a practice standing, because it rests on the same results as the notes.
  A practice that only the summary carries stays standing.
  The piece is then no witness for `ALREADY_SAID` or `NO_MATERIAL_CHANGE` on those practices, and its other notes still stand.
  So a problem that came back is raised again in a new note.
  The closed note does not hold it back.
  Its summary can repeat the words of the earlier summary.
  If that earlier piece has a note closed as fixed, preparation does not withhold it as `REPEATS_DELIVERED_NOTE`.
  On GitHub the server reopens the thread instead, so the piece keeps standing as advice.
- **A person's state wins.** A person's resolve, reopen or reply hands the thread to people for good.
  - An event is Hephaestus's own only when its actor is the account that writes the open change.
    That change must also ask for the state that the event reports.
    The writer records its account before the write starts.
    So the event of its own write matches even when it arrives before the write is confirmed.
  - Any other event is a person's.
    An event without an actor is a person's too.
    So a person can resolve a thread while Hephaestus's resolve still waits.
    That person then owns the thread, and Hephaestus never reopens it.
  - The event of an earlier own write can arrive after the next change asked for the other state.
    It then reads as a person's, and the thread goes to people. That errs toward people.
  - The writing account and the event's actor can fail to match.
    Then Hephaestus's own changes read as a person's, and it stops.
    That is safe, but it would silence the feature.
    `practice_feedback_thread_actor_mismatch_total` counts each event that matches an open change but names another actor or none.
    A person who resolves while Hephaestus's write is in flight counts once too.
    `HephaestusFeedbackThreadActorMismatch` alerts on any count.
  - The server never resolves a thread with a reply from a person newer than its note.
    It reads every comment after the note. A later comment of the account does not hide an earlier reply.
    A thread longer than one read counts as replied.
    A person can still reply between the server's read and its write. That window is inherent.
  - The writer reads who resolved a thread.
    A thread that another account resolved is a person's, even when no event told the server.
    The server hands it over, and it never reopens it.
  - A readback of a thread that the server resolved itself does not mark its feedback human-controlled.
- **Permission.** Before it writes, the server checks that the evidence of the note still permits practice feedback delivery.
  It checks again just before the write, after it asked the provider who the account is.
  It also checks that the feedback is not withdrawn and that the developer's data is not held.
- **Delivery.** One outbox action changes one thread.
  The provider's thread state is its readback.
  So an attempt whose write may have started reads the thread before it writes.
  A thread already in the asked state counts as done.

Integrations:

- **GitHub**: `resolveReviewThread` and `unresolveReviewThread`.
  The writing account is the token's viewer: for a GitHub App, its bot, which webhooks name as the sender.
  A bot is no GitHub User, so a thread that the bot resolved names no resolver.
  The server takes any resolve without a resolver as its own, also one by another app or workflow.
  The pull request review thread webhook now records its sender as the event's actor.
  The activity of a reopened thread now names that sender, not the pull request's author.
- **GitLab: resolve only, no reopen.** The server resolves the discussion through its `resolved` flag, as the token's user.
  GitLab resolves notes one by one, so a discussion counts as resolved only when every resolvable note is.
  A failed read of the discussion is read again, never taken for a thread that is not the server's.
  A synced resolve names the discussion's resolver.
  A synced reopen names no actor, so GitLab cannot tell the server's own reopen from a person's.
  A reopen of its own would hand the thread to people and mark its own feedback human-controlled.
  So the server never reopens on GitLab, and the thread stays resolved.
  A problem that comes back reaches the developer in a new note.
- **A personal access token.** When a connection uses a person's token, the writing account is that person.
  A change that the person makes in the direction that the server asked for then reads as the server's own.
- **Slack and Outline: not applicable.** Their feedback has no note on the work, and no thread that the server opened.
  Their adapters have no line note writer.

Enforced by:

- `FeedbackThreadResolutionIntegrationTest#shouldResolveAThreadWhenItsProblemIsNoLongerPresentAndReopenItWhenTheProblemComesBack`
- `FeedbackThreadResolutionIntegrationTest#shouldResolveANoteThatCitesTwoProblemsOnlyWhenBothAreNoLongerPresent`
- `FeedbackThreadResolutionIntegrationTest#shouldHandAThreadToPeopleWhenAPersonResolvesItWhileHephaestusStillWaitsAndNeverReopenIt`
- `FeedbackThreadResolutionIntegrationTest#shouldTakeTheThreadEventOfItsOwnWriteAsItsOwnEvenWhenItArrivesBeforeTheWriteIsConfirmed`
- `FeedbackThreadResolutionIntegrationTest#shouldTakeAThreadEventWithoutAnActorAsAPersons`
- `FeedbackThreadResolutionIntegrationTest#shouldNotResolveAThreadWhereAPersonRepliedAfterTheNote`
- `FeedbackThreadResolutionIntegrationTest#shouldNotResolveAThreadWhoseEvidenceNoLongerPermitsDelivery`
- `FeedbackThreadResolutionIntegrationTest#shouldWithdrawAResolveThatNeverStartedWhenTheProblemComesBackWithoutTouchingTheThread`
- `FeedbackThreadResolutionIntegrationTest#shouldHandAThreadToPeopleWhenItFindsItResolvedByAPersonWithoutAnEvent`
- `FeedbackThreadResolutionIntegrationTest#shouldReopenAResolvedThreadWhenAPersonMarksItsProofIncorrect`
- `FeedbackThreadResolutionIntegrationTest#shouldNotReopenAThreadWhenAPersonMarksTheResultItsNoteRestsOnIncorrect`
- `FeedbackThreadResolutionIntegrationTest#shouldKeepAGitLabThreadResolvedWhenItsProblemComesBack`
- `ReviewOnWorkPriorAdviceTest#shouldRaiseAProblemAgainWhenHephaestusClosedTheNoteThatGaveItsAdviceAsFixed`
- `GitHubThreadResolutionTest` and `GitLabThreadResolutionTest`
- `ProblemLineageIdentityMigrationTest`
