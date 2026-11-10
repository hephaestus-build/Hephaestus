# ADR 0090: A practice review cites only bytes that the server captured, admission verifies each citation once, and Hephaestus keeps the verdict, not the bytes

**Status:** Accepted
**Date:** 2026-11-10
**Authors:** Felix T.J. Dietrich

**Supersedes:** [ADR 0020](0020-context-fabric-everything-is-an-integration.md), [ADR 0039](0039-git-and-postgresql-own-evidence.md)

**Amends:** [ADR 0041](0041-compose-1x-kubernetes-2.md): § Evidence admission and deletion. This ADR is now the home of that rule.

**Builds on:** [ADR 0064](0064-one-evidence-contract-for-every-producer.md), [ADR 0079](0079-one-agent-system.md), [ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md), [ADR 0015](0015-unified-integration-framework.md), [ADR 0070](0070-1-0-is-a-cutover-release.md)

**Relates to:** [ADR 0063](0063-each-practice-is-staged-when-it-ends.md), [ADR 0056](0056-one-practice-model-for-items-and-scans.md)

## Context

ADR 0020 described a "Context Fabric": a register of ten decisions, most of them a target state that was never built.
It had five updates. Two of them moved its storage to ADR 0039 and then to ADR 0041.
ADR 0039 chose keep-refs in one worker clone and payload rows in PostgreSQL. That design never shipped.

ADR 0041 § Evidence admission and deletion decided the opposite, and main follows it: admission verifies each citation once, and only the verdict stays.
Meanwhile, ADR 0079 removed the worker, and the server runs as several replicas.

This ADR states evidence at 1.0 as rules. They say what a review may cite and where the bytes live. They also say how a citation is verified and how long evidence stays.
[ADR 0064](0064-one-evidence-contract-for-every-producer.md) keeps the evidence contract for each producer. ADR 0079 § Evidence authority keeps the digest rule for coverage.
A rule that main does not satisfy yet says "1.0 target" and names its pull request.

## Decision drivers

- Never turn a capture failure into a claim about someone's work (non-negotiable 1).
- A sandbox runs code that a model wrote. Its files cannot be evidence.
- Any server replica must verify and deliver any review, with no shared volume.
- Keep as few copies of people's work as the product needs, for as short a time as it needs them.

## Considered options

1. **Keep the captured bytes, and replay a citation at delivery** (ADR 0039). This needs keep-refs, payload rows and a durability contract, for a replay that no reader uses.
2. **Verify once at admission, keep the verdict and drop the bytes (chosen).** Any replica delivers from PostgreSQL alone.
3. **For the home of repository bytes across replicas:** a ReadWriteMany volume, or an object store. Or a cache on each server that it rebuilds and verifies against PostgreSQL (chosen, in [#4094](https://github.com/hephaestus-build/Hephaestus/pull/4094)). Many clusters have no ReadWriteMany volume. An object store adds a second home for bytes that PostgreSQL and the provider already hold.

## Decision

1. **Only the server captures.** Capture uses the server's credentials and writes the attempt's folder. `review_job.capture` records the SHA-256 digest of each captured file and of each checkout. A digest proves integrity, not permission. The sandbox gets captured bytes from the gateway and holds no provider credential ([ADR 0081](0081-one-server-role-and-sandboxes-that-reach-only-the-server.md) decision 5).
   1.0 target: on main, the worker captures. [#4079](https://github.com/hephaestus-build/Hephaestus/pull/4079) moves capture into the server.
   Not enforced. Code review catches a capture outside the server.
2. **Every source is an integration's.** Each source of evidence is a source kind in the versioned source catalog. The adapter of its integration captures it. Core review code, Heph and the evidence module never import a provider integration. A Heph conversation is not a source kind, so it is never evidence.
   Enforced by: `PracticeReviewModuleBoundaryTest#shouldKeepDomainAndProviderTypesOutOfCoreProducts`, `SourceCatalogCoverageTest`.
3. **Only a permitted source can be cited.** A citation must name a catalogued source kind whose source-use decision permits automated practice review now. Otherwise admission refuses the candidate.
   Enforced by: `ClasspathSourceCatalogRegistryTest#shouldStopPermittingAtExpiryAndRejectAnotherPurposeWhenDecisionDoesNotAuthorizeUse`, `CandidateEvidenceVerifierTest#shouldRefuseAnUnknownSourceForItsCandidateInsteadOfFailingTheEnvelope`.
4. **Admission verifies each citation once, byte for byte.** Admission reads the cited text only when its bytes have the captured digest. The quote must stand at its lines, or once in the text. Otherwise admission withholds the candidate as `quote_unverified`. A domain verifier checks sides and versions, but never supplies the text.
   Enforced by: `CandidateEvidenceVerifierTest#shouldStillRefuseAWrongQuoteDigestSideCommitPathOrAmbiguousText`, `#shouldRefuseAnAmbiguousQuoteWhenCapturedTextRepeatsIt`, `JobEvidenceFilesTest#shouldVerifyDigestAfterMatchingAcrossBuffersAndRejectChangedBytes`.
5. **Coverage and absence** follow ADR 0064 and [ADR 0079 § Evidence authority](0079-one-agent-system.md#subject-and-sandbox) (`JobEvidenceReadReceiptsTest#shouldReadOnlyBytesWithTheCapturedDigest`).
6. **A capture failure is never a claim.** A missing, failed, partial or unverifiable capture withholds the practice with its recorded reason. It never creates a result and never proves Does not apply. A condition that capture cannot decide keeps the practice eligible. A citation of a source that was not captured is `source_unavailable`, never a false quote.
   Enforced by: `ResultAdmissionIntegrationTest#shouldRecordCaptureFailureWithoutCreatingAResultForAnyClaimedOutcome`, `CandidateEvidenceVerifierTest#shouldRecordSourceUnavailableWithoutClaimingTheQuoteIsFalse`, `ScmCapturedApplicabilityTest#shouldRunThePracticeWhenTheSourceWasNeverCaptured`.
7. **The verdict persists, the bytes do not.** Each admitted citation keeps its quote and the status `VERIFIED` with its result. It also keeps the digest of the captured text and the digest of the quote. Results and feedback do not depend on the review job or its folder.
   Enforced by: `CitationVerificationTest#retainedProofDoesNotDependOnARuntimeJobOrAttempt`, `PracticeReviewModuleBoundaryTest#shouldKeepProductProofIndependentOfRetainedJobs`.
8. **No replay.** Delivery checks the stored verdict, the digest of the stored quote and the source-use decision for delivery. It never reads the captured bytes again, and the current upstream never stands in for them.
   Enforced by: `CitationVerificationTest#refusesMissingOrChangedQuoteProof`. Code review catches a delivery that reads a folder or the provider.
9. **Copies are short-lived.** The attempt folder goes when admission commits. An unadmitted folder goes 1 hour after its attempt ends. A restart removes the folders of unknown and finished attempts. The composition inputs ([ADR 0063](0063-each-practice-is-staged-when-it-ends.md)) stay at most 24 hours. So does the debug transcript ([ADR 0041](0041-compose-1x-kubernetes-2.md#update--2026-10-09-debug-transcripts)). Neither stays past the expiry of its occasion's evidence. [Data retention](../admin/data-retention.mdx) owns every period.
   Enforced by: `JobEvidenceFilesTest#shouldDeleteAdmittedFinalEvidenceImmediatelyWithoutDeletingAReplacementHandle`, `#shouldRetainUnadmittedEvidenceForOneHourAfterTheOwningHandleCloses`, `#shouldCleanUnknownAndFinishedFoldersImmediatelyAfterRestartButKeepRunningJobs`, `#shouldRemoveRawTranscriptAtTheOriginalOccasionDeadline`, `ResultAdmissionServiceTest#shouldBoundRetainedWriterInputsByTheirOriginalOccasionDeadline`.
10. **No evidence store.** There is no object store, no content-addressed store, no keep-ref and no shared evidence volume. The Git mirror and the attempt folder on a server are caches. PostgreSQL is the record.
    Not enforced. Code review catches a new store.
11. **Every server can verify every attempt.** Capture records each checkout's key (repository, commit and tree) and the folder's other files in PostgreSQL while the attempt runs. A server without the folder rebuilds it from its own mirror and checks the tree and the checkout digest. A rebuild that fails or differs is the capture failure `CHECKOUT_UNVERIFIED`, never a citation refusal.
    1.0 target: [#4094](https://github.com/hephaestus-build/Hephaestus/pull/4094), with `EvidenceAcrossServersIntegrationTest`. On main, only the process that captured an attempt holds its folder.
12. **Erasure follows every copied person.** A person erasure removes each attempt folder, composition input and transcript that copied that person ([personal data map](../admin/dsms/personal-data-map.md)).
    1.0 target: on main, erasure waits for the acknowledgement of the process that holds each copy. [#4094](https://github.com/hephaestus-build/Hephaestus/pull/4094) marks copies `ERASED` in PostgreSQL, so erasure waits for no server. Each server then removes erased folders within 2 minutes.
    Enforced by: `EvidenceFolderAccountErasureIntegrationTest#waitsForTheRuntimeAndErasesEveryAttemptInTwoWorkspacesWithoutTouchingAnotherPerson`, `EvidenceReceiptRetentionIntegrationTest`.

The [pipeline](../contributor/practice-review-pipeline.mdx#admit-results) owns the details of capture and admission.

## What changed, and why

- **No keep-refs and no payload rows** (ADR 0039 § Authority and identity). A stored payload pays sensitive-byte retention for a replay that the product does not offer (ADR 0041 § Rejected alternatives). The "unreplayable-legacy" outcome goes with replay.
- **No single storage node** (ADR 0039). The server runs as several replicas (ADR 0079), so each server keeps its own verified cache.
- **The Context Fabric register goes** (ADR 0020). Only the source catalog and the `source:purpose` decisions shipped. "Everything is an integration" stays as decision 2, a module rule, not as a Connector SPI.
- **Kept from both:** PostgreSQL is the record, and disk is a cache. SHA-256 digests and Git object IDs are the identity of evidence.

## Consequences

- Good: any replica verifies and delivers a review from PostgreSQL. There is no evidence store to back up, scale or erase.
- Good: the quote is the only captured text that outlives the composition inputs.
- Bad: after the copies expire, nobody can check a citation against the whole captured text again. An audit sees the quote and the digests only.
- Bad: a quote stays as long as its result, up to 400 days. It stays longer while feedback cites it.
- Bad: a force-push that removes the captured commit makes a rebuild on another server fail. The attempt then captures again.
- Bad: an expired source-use decision stops reviews of that source on its date, until a release renews it.

## Confirmation

Each decision names its test or says that it is not enforced.
Decisions 1 and 10 rely on code review. Decisions 11 and 12 are enforced once #4094 merges.

## Revisit trigger

- Rebuilds that end as `CHECKOUT_UNVERIFIED` because a fetch failed become a material share.
- A reader needs the cited bytes after 24 hours, for example an audit window.
- A practice needs evidence that capture cannot freeze as files with a digest.

## Sources

- OWASP, [LLM09:2025 Misinformation](https://genai.owasp.org/llmrisk/llm092025-misinformation/): "Implement tools and processes to automatically validate key outputs, especially output from high-stakes environments." Admission is that check, outside the model, for every citation (decisions 4 and 6).
- GDPR, [Article 5(1)(c) and (e)](https://eur-lex.europa.eu/eli/reg/2016/679/art_5/oj): personal data are "limited to what is necessary". They are kept "for no longer than is necessary". This is why the bytes go at admission and only the quote stays (decisions 7 and 9).

## Already priced and declined

- [ADR 0020](0020-context-fabric-everything-is-an-integration.md#considered-options): the full Connector SPI with a content-addressed store and a node graph, built at once.
- [ADR 0020](0020-context-fabric-everything-is-an-integration.md#considered-options): ad hoc context sources with no catalog.
- [ADR 0020](0020-context-fabric-everything-is-an-integration.md#decision-register): five PROV-O kinds, `entity_node` and `entity_edge` with split confidence, a capability registry, and `workspace_binding`.
- [ADR 0020](0020-context-fabric-everything-is-an-integration.md#decision-register): an audience tag with a `consistencyToken`. The `source:purpose` decision replaced it.
- [ADR 0020](0020-context-fabric-everything-is-an-integration.md#decision-register): row-level security as part of the fabric. [ADR 0004](0004-sql-layer-tenancy-via-statement-inspector.md) owns tenancy.
- [ADR 0020](0020-context-fabric-everything-is-an-integration.md#governance-and-minimization-supersedes-8s-audience-tag): a per-deployment override of source-use decisions.
- [ADR 0039](0039-git-and-postgresql-own-evidence.md#considered-options): a repaired filesystem content-addressed store.
- [ADR 0039](0039-git-and-postgresql-own-evidence.md#considered-options): every byte in PostgreSQL, including repository history.
- [ADR 0039](0039-git-and-postgresql-own-evidence.md#considered-options): S3-compatible storage.
- [ADR 0039](0039-git-and-postgresql-own-evidence.md#materialization-and-limits): a partial clone (`--filter=blob:none`) on the evidence path.
- [ADR 0041](0041-compose-1x-kubernetes-2.md#rejected-alternatives): evidence copies in PostgreSQL, object storage, a Git server, and context as Git repositories.
