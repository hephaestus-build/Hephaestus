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
   1.0 target: [#4061](https://github.com/hephaestus-build/Hephaestus/pull/4061) ends such a review as `REPLACED`. On main it ends Partial with a runtime reason.
   Enforced by: `ScmEventReviewIntegrationTest#shouldCarryWhatAReplacedQueuedReviewSelectedIntoTheReviewOfTheNewerHead` and `#shouldLeaveAPersonsRequestedReviewWhenTheWorkMoves`.
7. **A skip is not a claim.** Only Applies when can record Does not apply, and only when complete captured evidence proves every alternative false. An undecidable alternative keeps the practice. Selection, readiness, capacity, deadline or capture failure records its own reason, never Does not apply. No model selects practices.
   Enforced by: the invariant of `AppliesWhenCheck`, and `AppliesWhenExecutionIntegrationTest#shouldStopWithItsReasonInsteadOfDoesNotApplyWhenTheDiscussionWasNotReadWhole` and `#shouldRecordTheCaptureFailureWithoutAnInapplicabilityResultWhenTheSourceIsUnavailable`.
8. **Stage each practice when it ends.** The server admits the candidate at once with the one verifier ([ADR 0064](0064-one-evidence-contract-for-every-producer.md)). It refuses a citation of an undeclared source. It keeps the candidate `STAGED` on its membership with the cited texts. A staged candidate changes no visible feedback or standing. A retry measures only practices that are not staged.
   Enforced by: `ReviewToDeliveryContractIntegrationTest#shouldKeepStagingPrivateAndSealAfterAnInterruptedAttemptWithoutItsFolder` and `#shouldWithholdAnUndeclaredSourceAndComposeOnlyTheValidSibling`.
9. **The fence is `(run_id, lease_epoch)`.** A stage or seal write fails unless the `REVIEW` run is `LEASED` with that epoch and an unexpired lease.
   1.0 target: [#4080](https://github.com/hephaestus-build/Hephaestus/pull/4080) fences the sandbox's routes on the run's claim. On main, `ReviewInvocationFence` checks the job attempt. No pull request moves that check to the run yet.
   Enforced by: `ReviewToDeliveryContractIntegrationTest#shouldRefuseLateStagingAndSealingAfterTheAttemptLeaseExpires`.
10. **One seal.** The seal is one transaction under the item lock. It writes the results and problems of the staged rows. A staged result that is stale at the seal ends `WORK_CHANGED` and is never delivered. A retry at a moved version seals what it staged and ends the rest `REPLACED`. A cancel before the seal withholds staged practices with `CANCELLED`.
    Enforced by: `ReviewToDeliveryContractIntegrationTest#shouldWriteOneResultSetWhenTwoSealRequestsRace`, `#shouldEndAStagedObservationWhenTheWorkMovesBeforeTheSeal` and `#shouldClearStagingWithItsCancellationCauseAtTerminalSettlement`.
11. **Compose after the seal.** A `REVIEW_COMPOSE` run continues the `REVIEW` run in the review's sandbox and spends no attempt. A scan, a backfill or a review with no eligible destination composes nothing.
    Enforced by: `AgentRunsIntegrationTest#shouldContinueAnEndedRunAsTheNextStepOfItsWorkWithoutSpendingAnAttempt` and `FeedbackCompositionInputsTest#shouldKeepScanBackfillAndUnsupportedRequestsQuiet`.
12. **The composer has tools and no provider token.** It has `check_package`, `publish_package` and `edit_own`. The tools refuse an entry without an admitted, current result. They also refuse a claim from a withholding, and a thread that a person resolved. The server writes, and a retry reads back by the draft ID instead of posting again.
    Enforced by: `ReviewToDeliveryContractIntegrationTest#shouldCheckWithoutPreparingFeedbackAndRefusePublicationAfterItsLeaseExpires` and `FeedbackRepairIntegrationTest#shouldKeepThePostedCopyWhenARecipientTakesControlBeforeTheEdit`.
13. **One notifying write, then quiet corrections.** A review makes one GitHub review or one GitLab bulk publication. The composer reads the provider back and corrects with `edit_own`. A composition has at most 3 edits, counted on the feedback row, and an attempt at most 12 model calls. Then `STEP_LIMIT` stops it, and a posted copy stays.
    1.0 target: the write goes through the `agent_action` outbox in [#4070](https://github.com/hephaestus-build/Hephaestus/pull/4070).
    Enforced by: `FeedbackRepairIntegrationTest#shouldSpendThreeEditsAcrossRevisionsAndNeverResetTheBudgetOnReplay` and `#shouldKeepImmutableWordsAndEditTheSameProviderObjectWithOneNotification`.
14. **Each composition stop is recorded.** A destination that is not composed records `NOT_COMPOSED` or a more specific reason on its feedback row. If no attempt confirms every destination, the review closes Partial with `COMPOSITION_UNCONFIRMED`.
    Enforced by: `AgentOrphanRecoveryIntegrationTest#shouldCloseAnAdmittedReviewAsPartialWhenEveryAttemptIsLost`.
15. **Delivery checks again.** At send, a result whose sources changed withholds the feedback with `REVIEWED_VERSION_CHANGED`. A missing proof withholds it with `UNCORROBORATED` and never says that the work changed.
    Enforced by: `FeedbackGateIntegrationTest#shouldWithholdUnavailableCurrentProofWithoutClaimingTheWorkChanged`.
16. **Some work gets private feedback only.** A submitted pull request review gets no feedback on the work. A draft, merged work and closed work get feedback on the practice page and in conversation only. The one exception for merged and closed work is the workspace setting **Post feedback after merge**. It is off by default. When a draft becomes ready, that milestone reviews every practice again.
    Decided, not shipped for drafts, merged and closed work: no pull request delivers it yet.
    Enforced by: `PublicReviewEligibilityTest#shouldPublishOnlyPinnedAuthorResultsWithProviderCopySupport`, for submitted reviews only.
17. **The review command is a request.** `/hephaestus review` goes through the request service with the checks of **Request review**. Only a new comment that is exactly the command counts. A redelivered comment starts no second review.
    1.0 target: GitLab accepts any text after the command until [#4111](https://github.com/hephaestus-build/Hephaestus/pull/4111).
    Enforced by: `ReviewCommandListenerTest#shouldNotAnswerAgainForACommentDeliveredAgain`.
18. **Tools check facts, and a model never checks its own facts.** A rubric critique pass is decided and ships off. It turns on only when the composition eval shows a gain.
    Not enforced. The eval ([feedback quality evals](../contributor/feedback-quality-evals.md)) runs outside CI and has no rollout gate yet.
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
