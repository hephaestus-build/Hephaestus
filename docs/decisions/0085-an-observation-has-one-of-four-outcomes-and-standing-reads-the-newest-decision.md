# ADR 0085: An observation has one of four outcomes, holds one current claim while its sources stay unchanged, and standing reads the newest decision

**Status:** Accepted
**Date:** 2026-11-10
**Authors:** Felix T.J. Dietrich

**Supersedes:** [ADR 0050](0050-one-practice-standard-one-outcome.md), [ADR 0056](0056-one-practice-model-for-items-and-scans.md), [ADR 0065](0065-standing-counts-the-newest-observation-of-each-item.md)

**Builds on:** [ADR 0055](0055-practice-scripts-use-one-result-contract.md), [ADR 0064](0064-one-evidence-contract-for-every-producer.md), [ADR 0070](0070-1-0-is-a-cutover-release.md)

**Relates to:** [ADR 0051](0051-practices-across-the-workspace-counts-developers.md), [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md), ADR 0086

## Context

Three ADRs and four amendments described the practice model.
ADR 0050 named outcome values and definition fields that main does not use. ADR 0069 replaced the currentness rule of ADR 0056, and ADR 0065 changed its standing rule.
This ADR states the model at 1.0 as rules: practice, item, review, observation, outcome and standing.
The [product vocabulary](../contributor/practice-feedback-language.md), the [review glossary](../contributor/practice-review-glossary.mdx) and the [data model](../contributor/practice-feedback-schema.md) own the terms and the tables. Code calls an observation `Result`.

## Decision drivers

- A claim about someone's work stays inside captured and admitted evidence (non-negotiable 1).
- Irrelevant work, unresolved evidence and failed capture stay three different facts.
- One model serves persistence, runtime, API, UI and evaluation, for items and for scans.
- The database enforces the invariants, so every writer obeys them.

## Considered options

1. **Presence × desirability, or Met and Not met only.** The matrix makes each reader reverse the meaning of absence. Two values turn irrelevant work and open questions into false failures.
2. **Provider-specific records, or a parallel scan model.** Job cleanup deletes product history. Two outcome enums and two report readers must agree.
3. **One neutral item, review and observation model with four outcomes, and domain meaning at the boundary (chosen).**

## Decision

1. **A practice version is immutable.** A practice belongs to one workspace and points to its current version. A definition change creates a new version. The database generates the name and the review digest.
   Enforced by: the trigger `practice_version_immutable`, `PracticeVersionTest` and `PracticeVersionServiceIntegrationTest#shouldChangeTheDigestWhenCriteriaChange`.
2. **A definition has one closed shape.** Its fields are `name`, `reviewOn`, `appliesWhen`, `about`, `reads`, `reviewedBy`, `criteria`, `script`, `scan`, `lineNotes` and `guidance`. The item descriptor decides which events, states and roles are valid.
   Enforced by: `PracticeDefinition` (no additional properties), `ReviewOnTest` and `PracticeDefinitionValidatorTest#shouldRefuseEventsAndRolesThatTheExactItemDescriptorDoesNotOffer`.
3. **The review digest covers only what changes a judgment:** `appliesWhen`, `about`, `reads`, `reviewedBy`, `criteria`, `script` and `scan`. An edit of the name, `reviewOn`, `lineNotes` or `guidance` keeps the digest.
   Enforced by: `PracticeVersionServiceIntegrationTest#shouldKeepJudgmentEquivalenceAcrossGuidanceEventAndDeliveryEdits`.
4. **A state selection matches strictly.** An empty `reviewOn.state` selects every state. Values in one dimension are alternatives, and every selected dimension must match. A missing fact does not match.
   Enforced by: `FeedbackGateIntegrationTest#shouldAdmitAnyStateWhenTheVersionRequiresNone` and `#shouldRefuseChangedWorkAndClosedWorkAgainstThePersistedStateFilter`. No test names the missing-fact case.
5. **An item is neutral, and its domain gives it meaning.** An item has a workspace, a kind such as `scm.pull_request`, and an adapter-owned identity. A file has a repository parent and a path. The domain defines its versions. Core code imports no provider or diff type.
   Enforced by: `ck_item_kind`, `PracticeReviewModuleBoundaryTest#shouldKeepDomainAndProviderTypesOutOfCoreProducts` and `PracticesIntegrationBoundaryTest#shouldReachIntegrationItemsOnlyThroughNeutralPorts`.
6. **A review pins what it measures.** A review has a kind (ITEM or SCAN) and an origin (EVENT, REQUEST, SCHEDULE or BACKFILL). It pins the item version and each practice version with its method. Its membership records each practice and item pair. A membership state is not an outcome. Observations outlive runtime jobs.
   Enforced by: the trigger `result_insert` and `PracticeReviewModuleBoundaryTest#shouldKeepProductProofIndependentOfRetainedJobs`.
7. **An observation has exactly one outcome:** `MET`, `NOT_MET`, `DOES_NOT_APPLY` or `COULD_NOT_DETERMINE`. Only Met and Not met are decided. The [outcome contract](../contributor/practice-feedback-language.md#observation-outcomes) states the evidence that each one needs.
   Enforced by: `ck_result_outcome`, `ck_result_inapplicability`, `ck_result_open_questions` and `ResultAdmissionIntegrationTest#shouldAdmitTheOutcomeWithItsRequiredProblemShape`.
8. **Only Not met has problems and severity.** A Not met observation has one or more problems, each with a severity, citations and a claim basis. The database copies the worst severity to the observation. Other outcomes have neither.
   Enforced by: the constraint trigger `result_children` and `PracticeV1ConstraintDatabaseTest#shouldEnforceCompleteChildrenAndRestoreTheSeverityCacheOnRollback`.
9. **A failure is never an observation.** A capture failure, a failed read, a timeout, a spent budget or a script error records no observation. A refused candidate records none and never becomes Met. ADR 0055 and ADR 0064 own the candidate and evidence contract.
   Enforced by: `ResultAdmissionIntegrationTest#shouldRecordCaptureFailureWithoutCreatingAResultForAnyClaimedOutcome` and `#shouldWithholdAnOutcomeWhoseProblemShapeDoesNotMatch`.
10. **One current claim exists for each practice, item and developer.** A null developer is one key. The newest claim in review order ends the holder as `REPLACED`. An older claim that arrives late ends at once as `OUTRANKED`. Observation facts never change, except the end, the severity cache and a move from an unclaimed account to its owner.
    Enforced by: `uk_result_current`, the trigger `result_facts`, `ResultAdmissionIntegrationTest#olderLateAdmissionIsEndedOutrankedWithoutReplacingTheNewerClaim` and `PracticeV1ConstraintDatabaseTest#shouldKeepNobodyInTheCurrentClaimUniquenessKey`.
11. **An observation is current while its sources keep their fingerprints.** It records the [source fingerprint](../contributor/practice-review-glossary.mdx#review-terms) of every source that its session could read. If one changes, `ResultCurrentness` ends it as `WORK_CHANGED`. If the item is gone, it ends as `REMOVED`. With no recorded fingerprint it is unverifiable and does not end. A scan observation reads its frozen scope. The item version stays the capture pin and the delivery fence.
    Enforced by: `SourceFingerprintTest`, `ResultAdmissionIntegrationTest#shouldEndCurrentResultsWhenAnScmChangeEventIsRecordedWithoutEndingLaterResultsOnReplay` and `#shouldRecordAnUnverifiableStartWithoutCallingTheWorkChanged`.
12. **In 1.0, nothing proves a repair.** No removed file, mark, reuse or missed read proves that a problem is gone. The end reason `REPAIRED` has no writer, so it goes from `ResultEndReason` and `ck_result_end_reason`.
    Decided, not shipped: no pull request deletes it yet.
13. **A mark never ends an observation.** An admin marks an observation Incorrect or Accepted with a reason and can remove the mark. A marked observation keeps its claim, and no older one comes back. Incorrect stops new feedback and reuse. Accepted only removes it from open-problem lists.
    Enforced by: `ResultMarkServiceIntegrationTest#incorrectMarkAndRemovalDoNotChangeCurrentOccupancy`, `ResultAdmissionIntegrationTest#markedCurrentClaimStillOccupiesUntilANewerAdmissionReplacesIt` and `RequestedReviewReuseIntegrationTest#shouldMeasureAgainWhenSomeoneMarkedTheResultIncorrect`.
14. **Reuse needs the same inputs.** Only a scan and a requested item review reuse a current observation. Both need the same practice version, model digest and epoch, and complete trusted coverage. A scan also needs the same item version. A requested review needs the same readable fingerprints. Migrated history is never reused.
    Enforced by: `RequestedReviewReuseIntegrationTest`, `ReviewScanReuseTrustTest` and the trigger `review_item_write`.
15. **Standing counts the newest live decision.** For each practice, item and developer, standing reads the newest observation of an EVENT or SCHEDULE review. An observation that `WORK_CHANGED` ended counts until a newer live one replaces it. `REMOVED` ends the chain. A requested or backfilled review neither counts nor hides the live one. Heph reads only observations with current proof.
    Enforced by: `PracticeStandingServiceTest`, for example `#shouldKeepTheLiveStandingWhenAWorkChangeStartedNoReviewOfThePractice` and `#shouldSkipAnOutrankedResultBetweenAWorkChangeAndARequest`.
16. **Feedback is a separate record.** Each piece cites the exact observations and problems that support it. Preparation stores each copy before a provider write. A copy with unknown visibility stays Unconfirmed and never allows an automatic repeat write. A practice-page delivery is the developer's first authenticated read. Slack and Outline items get no feedback on the work.
    Enforced by: `AccountErasureIntegrationTest#shouldPreserveUnconfirmedCopyCustodyUntilExternalRemovalIsConfirmed` and `PracticePageFeedbackLifecycleIntegrationTest#shouldRecordFirstReadOnTheParentWithoutCreatingAPhysicalCardCopy`. No test checks the Slack and Outline copy capabilities.
17. **People are accounts.** The developer of an observation is an account. [Workspace context](../contributor/workspace-context.mdx) owns how roster sync, a claim and an absorption create and join accounts. Account erasure removes affected proof and feedback before the membership.
    Enforced by: `PeopleRegistryIntegrationTest#shouldPreserveDeliveredReceiptsAndRerankClaimsWhenUnclaimedAttributionIsAbsorbed` and `AccountErasureIntegrationTest`.
18. **A scan pins a monitored default-branch inventory.** One scan for each workspace can be active. A file that cannot supply scan text stays in the inventory with an exclusion reason. An incomplete or suspect listing removes nothing.
    Enforced by: `uk_review_scan_active_workspace`, `ScanServiceTest#rejectsAnIncompleteListingWithoutRecordingInventory` and `#shouldKeepIneligibleFilesInTheInventoryAndRecordExclusionsWithoutAssignments`. No test names the suspect-listing guard.
19. **When a review runs, stages, seals and composes** is the lifecycle of ADR 0086. Where it runs is ADR 0081.

## What changed, and why

- **The outcome names are the shipped names.** ADR 0050 wrote `NOT_APPLICABLE` and `UNDETERMINED`. The [product vocabulary](../contributor/practice-feedback-language.md#observations) retires both. The meaning is the same.
- **The definition fields are the shipped fields.** `signals`, `evidenceRequirements`, `reviewWhen`, `subject` and `precondition` became `reviewOn`, `reads`, `about` and `appliesWhen`. The rule of ADR 0050 stays: scheduling is outside the judgment digest.
- **Currentness follows source fingerprints, not the item version** ([ADR 0069 § Freshness by source fingerprints](0069-practices-run-when-ready-and-stay-current-by-their-sources.md#freshness-by-source-fingerprints)). A description edit no longer ends a diff observation, and a comment no longer leaves a stale one current.
- **Dependency-based scan reuse is dropped.** ADR 0056 described it as a future contract. Nothing calls it. The update of ADR 0062 keeps the full input digest out of the 1.0 release. Decision 14 keeps reuse at equal inputs.
- **Standing has the rule of ADR 0065 under new names.** `liveRunOf` is now `PracticeStandingService.liveRunsAt` and `Claim.liveRunAt`. Heph reads only current proof, as the update of ADR 0065 corrected.

## Consequences

- Good: each outcome is one fact. No reader reverses absence, or reads a failure as a finding about the work.
- Good: the database refuses an invalid outcome, problem set, claim or changed fact, whatever the writer.
- Good: a push that no practice reviews again keeps the item in standing.
- Bad: standing can count a decision about an earlier version of the work.
- Bad: an observation with no recorded fingerprint stays current and unverifiable until a newer review replaces it.
- Bad: no observation ends as repaired. An open-problem list shrinks only through a new review, an end or an Accepted mark.
- Bad: reuse needs the same model digest. After a model change, a scan or a requested review measures every practice again.

## Confirmation

Each decision names its test or database constraint. Decision 12 is not shipped yet.
The missing-fact case of decision 4, the Slack and Outline capabilities of decision 16 and the suspect guard of decision 18 have no named test.

## Revisit trigger

- Independently reviewed evaluation cases show a distinction that the four outcomes cannot express. Do not add an outcome only to encode delivery.
- A practice must lose standing at a work change, for example a practice about the current state of a branch.
- A shipped domain needs other person terms or citation coordinates, or measured scan size needs another storage design.
- The host records a complete dependency set for each session. Then reuse across changed inputs can be proposed.

## Sources

- W3C, [Evaluation and Report Language (EARL) 1.0 Schema](https://www.w3.org/TR/EARL10-Schema/), Working Group Note, 2017. Its outcomes separate two cases. One is "the test is not applicable to the subject". The other is "it is unclear if the subject passed or failed". This supports two more outcomes beside Met and Not met (decision 7).
- OASIS, [SARIF 2.1.0](https://docs.oasis-open.org/sarif/sarif/v2.1.0/errata01/os/sarif-v2.1.0-errata01-os-complete.html) (§ 3.27.9 and § 3.27.10) defines `open` as follows. `open` means "insufficient information to decide whether a problem exists". When `kind` is not `fail`, `level` "SHALL have the value none". So only Not met has a severity (decision 8).

## Already priced and declined

- [ADR 0050](0050-one-practice-standard-one-outcome.md#considered-options): the presence and desirability matrix, Met and Not met only, and a nullable conformance with a separate status.
- [ADR 0050](0050-one-practice-standard-one-outcome.md#decision): criteria split into model-authored rubrics, and old positive labels read as full conformance.
- [ADR 0056](0056-one-practice-model-for-items-and-scans.md#considered-options): provider-specific records, a parallel scan model, and a generic graph for future domains.
- [ADR 0065](0065-standing-counts-the-newest-observation-of-each-item.md#considered-options): a chain that a work change ends, and every practice on every push.
- [ADR 0069 § Freshness](0069-practices-run-when-ready-and-stay-current-by-their-sources.md#freshness): version equality, a map from version parts, one verdict per version, and shared-brief fingerprints.
- [ADR 0022](0022-observation-presence-assessment-and-schema-cleanup.md#2-the-reaction-anchors-on-feedback-only): an open verb or event log for reactions.
- [ADR 0021](0021-observations-feedback-synthesis-seam.md#rejected-alternatives) and [ADR 0029](0029-measurement-intervention-seam-and-channel-levels.md#alternatives-considered): advice stored on an observation, and every observation rendered as feedback.
