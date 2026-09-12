package de.tum.cit.aet.hephaestus.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceContract;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceManifest;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessReason;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceReason;
import de.tum.cit.aet.hephaestus.evidence.SourceAbsenceState;
import de.tum.cit.aet.hephaestus.evidence.SourceCaptureState;
import de.tum.cit.aet.hephaestus.evidence.SourceCompleteness;
import de.tum.cit.aet.hephaestus.evidence.SourceContentState;
import de.tum.cit.aet.hephaestus.evidence.SourceContractVersion;
import de.tum.cit.aet.hephaestus.evidence.SourceKind;
import de.tum.cit.aet.hephaestus.evidence.SourceReadinessCheck;
import de.tum.cit.aet.hephaestus.evidence.SourceReadinessReason;
import de.tum.cit.aet.hephaestus.evidence.SourceUsePurpose;
import de.tum.cit.aet.hephaestus.evidence.internal.ClasspathArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.practices.EvidenceStance;
import de.tum.cit.aet.hephaestus.practices.PracticeAutomatedReview;
import de.tum.cit.aet.hephaestus.practices.PracticeAutomatedReviewMode;
import de.tum.cit.aet.hephaestus.practices.PracticeAutomatedReviewPolicy;
import de.tum.cit.aet.hephaestus.practices.PracticeBinding;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceLimitation;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceRequirement;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceSufficiency;
import de.tum.cit.aet.hephaestus.practices.PracticeInsufficientEvidenceAction;
import de.tum.cit.aet.hephaestus.practices.PracticeSubject;
import de.tum.cit.aet.hephaestus.practices.PracticeSubjectClause;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ContextManifestBuilderTest extends BaseUnitTest {

    private static final SourceKind DIFF = new SourceKind("scm.pull-request.diff");
    private static final SourceKind CORE = new SourceKind("scm.pull-request.core");
    private static final SourceKind COMMENTS = new SourceKind("scm.pull-request.comments");
    private static final SourceKind CONVERSATION = new SourceKind("slack.conversation.thread");
    private static final SourceKind LINKED_ITEMS = new SourceKind("scm.linked-work-items");
    private static final SourceKind REPOSITORY_TREE = new SourceKind("scm.repository.tree");
    private static final SourceKind OUTLINE = new SourceKind("outline.documents");
    private static final SourceKind PROJECT_INVENTORY = new SourceKind("workspace.project-inventory");
    private static final Instant NOW = Instant.parse("2026-09-11T10:00:00Z");

    private final JsonMapper mapper = JsonMapper.builder().build();
    private ContextManifestBuilder builder;

    @BeforeEach
    void setUp() {
        builder = builderAt(NOW);
    }

    @Test
    void shouldExposeCitationDigestsWithoutInternalMetadata() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        byte[] diff = "diff --git a b".getBytes(StandardCharsets.UTF_8);
        files.put("inputs/context/diff.patch", diff);
        EvidencePlan plan = plan();

        builder.augment(
                files,
                Map.of("inputs/context/diff.patch", DIFF),
                "job-42",
                plan,
                new ContextManifestBuilder.CaptureMetadata(
                        Map.of(DIFF, SourceCompleteness.COMPLETE),
                        Map.of(DIFF, "abc123"),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Set.of(DIFF)));

        JsonNode visible = mapper.readTree(files.get("inputs/manifest.json"));
        assertThat(visible.path("contractVersion").asString()).isEqualTo("1.1.0");
        assertThat(visible.toString()).doesNotContain("job-42").doesNotContain("workspaceId");
        JsonNode diffSource = findSource(visible, DIFF.value());
        assertThat(diffSource.path("state").path("availability").asString()).isEqualTo("AVAILABLE");
        assertThat(diffSource.path("artifacts").get(0).path("path").asString()).isEqualTo("inputs/context/diff.patch");
        assertThat(diffSource.path("artifacts").get(0).path("sha256").asString())
                .isEqualTo(de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest.sha256Hex(diff));
    }

    @Test
    void shouldReportASourceWithNoCollectorAsUnavailableRatherThanUnwanted() {
        Map<String, byte[]> files = new LinkedHashMap<>();

        builder.augment(files, Map.of(), "job-7", plan(), metadata(COMMENTS, NOW));

        JsonNode visible = mapper.readTree(files.get("inputs/manifest.json"));
        JsonNode diff = findSource(visible, DIFF.value());
        assertThat(diff.path("state").path("availability").asString()).isEqualTo("UNAVAILABLE");
        assertThat(diff.path("state").path("reasonCode").asString()).isEqualTo("NO_PROVIDER");
        assertThat(diff.has("paths")).isFalse();
        JsonNode comments = findSource(visible, COMMENTS.value());
        assertThat(comments.path("state").path("content").asString()).isEqualTo("EMPTY");
        assertThat(comments.path("state").path("completeness").asString()).isEqualTo("COMPLETE");
    }

    @Test
    void shouldStageASourceNoPracticeDeclares() {
        assertThat(builder.stagedSources(plan())).contains(PROJECT_INVENTORY, OUTLINE, REPOSITORY_TREE);
    }

    @Test
    void shouldRefuseANewCaptureUnderARetiredContract() {
        var retired = new EvidencePlan(new SourceContractVersion("1.0.0"), ArtifactKinds.PULL_REQUEST);
        assertThatThrownBy(() -> builder.stagedSources(retired))
                .isInstanceOf(de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException.class)
                .hasMessageContaining("1.0.0");
    }

    @Test
    void shouldAuthorizeCaptureForTheDetectionAudience() {
        ArtifactSourceCatalogRegistry catalogs = mock(ArtifactSourceCatalogRegistry.class);
        ContextManifestBuilder target =
                new ContextManifestBuilder(mapper, catalogs, new PracticeSubjectEvaluator(mapper), Clock.systemUTC());
        SourceContractVersion version = new SourceContractVersion("1.1.0");

        target.isSourceUsePermitted(version, DIFF);

        verify(catalogs).isSourceUsePermitted(version, DIFF, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW);
    }

    @Test
    void shouldRepresentAWhollyWithheldSourceAsRedactedWithoutLeakingArtifacts() {
        Map<String, byte[]> files = new LinkedHashMap<>();

        builder.augment(
                files,
                Map.of(),
                "job-redacted",
                plan(),
                new ContextManifestBuilder.CaptureMetadata(
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(COMMENTS, new SourceCaptureState.Redacted(SourceAbsenceReason.CONSENT_NOT_ACTIVE)),
                        Set.of(COMMENTS)));

        JsonNode source = findSource(mapper.readTree(files.get("inputs/manifest.json")), COMMENTS.value());
        assertThat(source.path("state").path("availability").asString()).isEqualTo("REDACTED");
        assertThat(source.has("paths")).isFalse();
    }

    @Test
    void shouldReviewAMirroredRecordWhoseCurrentnessCannotBeEstablished() {
        // A pull request unchanged for months is correctly mirrored, not stale — reading the mirror's
        // last-written timestamp as a last-verified one would wrongly refuse this case.
        var manifest = coreManifest(builder, "job-quiet", Instant.EPOCH);

        assertThat(builder.checkAutomatedReviewReadinessAsOfNow(manifest, List.of(practiceRequiring(CORE, "pr-core")))
                        .readyPractices())
                .hasSize(1);
    }

    @Test
    void shouldDeclineWhenASourceThatMustHoldSomethingCapturedNothing() {
        // An empty diff is valid (full revert, force-push, merge-only range); only the declared
        // COMPLETE_AND_NON_EMPTY separates "nothing to judge" from "looked and it was fine".
        Map<String, byte[]> files = new LinkedHashMap<>();
        String path = "inputs/context/diff.patch";
        files.put(path, new byte[0]);
        ArtifactSourceManifest manifest = builder.augment(
                files,
                Map.of(path, DIFF),
                "job-empty-diff",
                plan(),
                new ContextManifestBuilder.CaptureMetadata(
                        Map.of(DIFF, SourceCompleteness.COMPLETE),
                        Map.of(DIFF, SourceContentState.EMPTY),
                        Map.of(DIFF, "abc123"),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Set.of(DIFF)));

        AutomatedReviewReadinessResult refused = builder.checkAutomatedReviewReadinessAsOfNow(
                manifest, List.of(practiceRequiring(DIFF, "needs-substance")));

        assertThat(refused.readyPractices()).isEmpty();
        // Exactly one reason, not merely this one among others: an empty capture is a capture, so
        // nothing may also call it absent.
        assertThat(refused.decisions().getFirst().sourceChecks().getFirst().reasonCodes())
                .containsExactly(SourceReadinessReason.SOURCE_EMPTY);
    }

    /**
     * Incompleteness and emptiness are facts about a capture that happened; where none did, absence is
     * the whole answer.
     */
    @Test
    void shouldNameOnlyAbsenceWhenNothingWasCaptured() {
        ArtifactSourceManifest manifest = builder.augment(
                new LinkedHashMap<>(),
                Map.of(),
                "job-absent-diff",
                plan(),
                new ContextManifestBuilder.CaptureMetadata(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of()));

        AutomatedReviewReadinessResult refused =
                builder.checkAutomatedReviewReadinessAsOfNow(manifest, List.of(practiceRequiring(DIFF, "needs-diff")));

        assertThat(refused.readyPractices()).isEmpty();
        assertThat(refused.decisions().getFirst().sourceChecks().getFirst().reasonCodes())
                .containsExactly(SourceReadinessReason.SOURCE_NOT_AVAILABLE);
    }

    @Test
    void shouldRefuseReadinessWhenCoreReportsAnUpstreamDeletion() {
        var unavailable = new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND);
        ArtifactSourceManifest manifest = builder.augment(
                new LinkedHashMap<>(),
                Map.of(),
                "job-deleted-core",
                plan(),
                new ContextManifestBuilder.CaptureMetadata(
                        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(CORE, unavailable), Set.of(CORE)));

        var readiness =
                builder.checkAutomatedReviewReadinessAsOfNow(manifest, List.of(practiceRequiring(CORE, "needs-core")));

        assertThat(readiness.readyPractices()).isEmpty();
        assertThat(readiness.decisions().getFirst().sourceChecks().getFirst().reasonCodes())
                .containsExactly(SourceReadinessReason.SOURCE_NOT_AVAILABLE);
    }

    @Test
    void shouldNameNoReasonWhenACompleteCaptureHeldSomething() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        String path = "inputs/context/diff.patch";
        files.put(path, "diff --git a b".getBytes(StandardCharsets.UTF_8));
        ArtifactSourceManifest manifest = builder.augment(
                files,
                Map.of(path, DIFF),
                "job-good-diff",
                plan(),
                new ContextManifestBuilder.CaptureMetadata(
                        Map.of(DIFF, SourceCompleteness.COMPLETE),
                        Map.of(DIFF, SourceContentState.NON_EMPTY),
                        Map.of(DIFF, "abc123"),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Set.of(DIFF)));
        Practice practice = practiceRequiring(DIFF, "needs-substance");

        AutomatedReviewReadinessResult accepted =
                builder.checkAutomatedReviewReadinessAsOfNow(manifest, List.of(practice));

        assertThat(accepted.readyPractices()).containsExactly(practice);
        SourceReadinessCheck check =
                accepted.decisions().getFirst().sourceChecks().getFirst();
        assertThat(check.meetsRequirements()).isTrue();
        assertThat(check.reasonCodes()).isEmpty();
    }

    @Test
    void shouldReviewAnEmptyCaptureOfASourceThatMayBeEmpty() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        String path = "inputs/context/comments.json";
        files.put(path, "[]".getBytes(StandardCharsets.UTF_8));
        ArtifactSourceManifest manifest = builder.augment(
                files,
                Map.of(path, COMMENTS),
                "job-empty-comments",
                plan(),
                new ContextManifestBuilder.CaptureMetadata(
                        Map.of(COMMENTS, SourceCompleteness.COMPLETE),
                        Map.of(COMMENTS, SourceContentState.EMPTY),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Set.of(COMMENTS)));

        assertThat(builder.checkAutomatedReviewReadinessAsOfNow(manifest, List.of(practiceRequiringComments()))
                        .readyPractices())
                .hasSize(1);
    }

    /**
     * A fragment that does not contain something is equally consistent with that something being in the
     * part nobody fetched, so a partial capture still refuses an absence claim.
     */
    @Test
    void shouldRefuseAnAbsenceClaimOnAPartialCapture() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        String path = "inputs/context/comments.json";
        files.put(path, "[{}]".getBytes(StandardCharsets.UTF_8));
        ArtifactSourceManifest manifest = builder.augment(
                files,
                Map.of(path, COMMENTS),
                "job-partial-comments",
                plan(),
                new ContextManifestBuilder.CaptureMetadata(
                        Map.of(COMMENTS, SourceCompleteness.PARTIAL),
                        Map.of(COMMENTS, SourceContentState.NON_EMPTY),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Set.of(COMMENTS)));

        assertThat(builder.checkAutomatedReviewReadinessAsOfNow(manifest, List.of(practiceRequiringComments()))
                        .readyPractices())
                .hasSize(1);

        AutomatedReviewReadinessResult refused = builder.checkAutomatedReviewReadinessAsOfNow(
                manifest, List.of(practiceRequiring(COMMENTS, "asserts-an-absence", EvidenceStance.EXHAUSTIVE)));

        assertThat(refused.readyPractices()).isEmpty();
        // Exactly one reason: the fragment that was captured is still a capture, so nothing may also
        // call it absent.
        assertThat(refused.decisions().getFirst().sourceChecks().getFirst().reasonCodes())
                .containsExactly(SourceReadinessReason.SOURCE_INCOMPLETE);
    }

    @Test
    @DisplayName(
            "a repository tree the bounds truncated is PARTIAL, names what it dropped, and refuses an absence claim")
    void shouldRefuseAnAbsenceClaimOnATruncatedRepositoryTree() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        String path = "inputs/sources/scm/repo/src/App.java";
        files.put(path, "class App {}".getBytes(StandardCharsets.UTF_8));
        ArtifactSourceManifest manifest = builder.augment(
                files,
                Map.of(path, REPOSITORY_TREE),
                "job-truncated-tree",
                plan(),
                new ContextManifestBuilder.CaptureMetadata(
                        Map.of(REPOSITORY_TREE, SourceCompleteness.PARTIAL),
                        Map.of(REPOSITORY_TREE, SourceContentState.NON_EMPTY),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(REPOSITORY_TREE, List.of(GitRepositoryManager.TREE_LIMITATION_UNSAFE_PATH)),
                        Set.of(REPOSITORY_TREE)));

        // The manifest must say which bound stopped the walk, not merely that something is missing.
        SourceCaptureState.Available tree = (SourceCaptureState.Available) manifest.sources().stream()
                .filter(source -> source.kind().equals(REPOSITORY_TREE))
                .findFirst()
                .orElseThrow()
                .state();
        assertThat(tree.completeness()).isEqualTo(SourceCompleteness.PARTIAL);
        assertThat(tree.limitations()).containsExactly(GitRepositoryManager.TREE_LIMITATION_UNSAFE_PATH);

        assertThat(builder.checkAutomatedReviewReadinessAsOfNow(
                                manifest, List.of(practiceRequiring(REPOSITORY_TREE, "reads-the-tree")))
                        .readyPractices())
                .hasSize(1);

        // A practice whose verdict rests on something being absent from the repository must not be
        // answered from a tree we only partly walked.
        AutomatedReviewReadinessResult refused = builder.checkAutomatedReviewReadinessAsOfNow(
                manifest,
                List.of(practiceRequiring(REPOSITORY_TREE, "asserts-nothing-in-the-repo", EvidenceStance.EXHAUSTIVE)));

        assertThat(refused.readyPractices()).isEmpty();
        assertThat(refused.decisions().getFirst().sourceChecks().getFirst().reasonCodes())
                .containsExactly(SourceReadinessReason.SOURCE_INCOMPLETE);
    }

    @Test
    @DisplayName("refuses to publish a capture that calls itself complete and still names an omission")
    void shouldRejectACompleteCaptureThatNamesAnOmission() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        String path = "inputs/sources/scm/repo/src/App.java";
        files.put(path, "class App {}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> builder.augment(
                        files,
                        Map.of(path, REPOSITORY_TREE),
                        "job-contradictory-tree",
                        plan(),
                        new ContextManifestBuilder.CaptureMetadata(
                                Map.of(REPOSITORY_TREE, SourceCompleteness.COMPLETE),
                                Map.of(REPOSITORY_TREE, SourceContentState.NON_EMPTY),
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                Map.of(REPOSITORY_TREE, List.of(GitRepositoryManager.TREE_LIMITATION_SUBMODULE)),
                                Set.of(REPOSITORY_TREE))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reported COMPLETE while naming what it omitted");
    }

    @Test
    void shouldSkipAutomatedReviewsThatCannotRun() {
        ArtifactSourceManifest manifest = coreManifest(builder, "job-unsupported-assessment", NOW);
        List<PracticeAutomatedReview> configurations = List.of(
                new PracticeAutomatedReview(
                        PracticeAutomatedReviewMode.LANGUAGE_MODEL,
                        PracticeEvidenceSufficiency.DECLARED_EVIDENCE_INSUFFICIENT),
                new PracticeAutomatedReview(PracticeAutomatedReviewMode.NONE, PracticeEvidenceSufficiency.NONE));
        List<AutomatedReviewReadinessReason> expectedReasons = List.of(
                AutomatedReviewReadinessReason.DECLARED_EVIDENCE_INSUFFICIENT,
                AutomatedReviewReadinessReason.NO_AUTOMATED_REVIEW);

        for (int index = 0; index < configurations.size(); index++) {
            Practice practice = practiceRequiring(CORE, "unsupported-assessment-" + index);
            boolean assessmentAbsent = configurations.get(index).mode() == PracticeAutomatedReviewMode.NONE;
            boolean needsAdditionalContext = configurations.get(index).evidenceSufficiency()
                    == PracticeEvidenceSufficiency.DECLARED_EVIDENCE_INSUFFICIENT;
            practice.setAutomatedReviewPolicy(new PracticeAutomatedReviewPolicy(
                    practice.getAutomatedReviewPolicy().sourceContractVersion(),
                    configurations.get(index),
                    practice.getAutomatedReviewPolicy().whenEvidenceIsInsufficient(),
                    assessmentAbsent
                            ? List.of()
                            : needsAdditionalContext
                                    ? List.of(
                                            new PracticeEvidenceLimitation(
                                                    "ADDITIONAL_CONTEXT_NEEDED",
                                                    "The available sources do not contain the context required for this assessment."))
                                    : practice.getAutomatedReviewPolicy().knownLimitations(),
                    needsAdditionalContext
                            ? new PracticeEvidenceLimitation(
                                    "ADDITIONAL_CONTEXT_NEEDED",
                                    "The available sources do not contain the context required for this assessment.")
                            : null));
            if (assessmentAbsent) {
                // A practice nobody automates reads nothing, so its bindings carry no evidence — the
                // shape PracticeService leaves behind when automated review is switched off.
                practice.setBindings(practice.getBindings().stream()
                        .map(binding -> new PracticeBinding(binding.signals(), List.of(), binding.onDrafts()))
                        .toList());
            }

            AutomatedReviewReadinessResult result =
                    builder.checkAutomatedReviewReadinessAsOfNow(manifest, List.of(practice));
            assertThat(result.readyPractices()).isEmpty();
            assertThat(result.decisions().getFirst().reasonCodes()).containsExactly(expectedReasons.get(index));
            if (assessmentAbsent) {
                assertThat(result.decisions().getFirst().sourceChecks()).isEmpty();
            } else {
                assertThat(result.decisions().getFirst().sourceChecks())
                        .allMatch(SourceReadinessCheck::meetsRequirements);
            }
        }
    }

    @Test
    void shouldReviewWorkUnchangedUpstreamSinceTheLastSynchronization() {
        // A mirrored record upstream has not touched is current, however old the last write is.
        ArtifactSourceManifest manifest = coreManifest(builder, "job-quiet-mirror", NOW.minusSeconds(14 * 86_400));

        assertThat(builder.checkAutomatedReviewReadiness(
                                manifest, List.of(practiceRequiring(CORE, "pr-core")), NOW, null)
                        .readyPractices())
                .hasSize(1);
    }

    @Test
    void shouldProduceTheSameReadinessResultWhenReplayed() {
        ArtifactSourceManifest manifest = coreManifest(builder, "job-replay", NOW);
        List<Practice> practices = List.of(practiceRequiring(CORE, "pr-core"));

        var original = builder.checkAutomatedReviewReadiness(manifest, practices, NOW, null);
        // Readiness is a pure function of the recorded evidence and anchor, so re-evaluating later must
        // produce an identical result.
        var replayed =
                builderAt(NOW.plusSeconds(90 * 86_400)).checkAutomatedReviewReadiness(manifest, practices, NOW, null);

        assertThat(replayed.readyPractices()).hasSameElementsAs(original.readyPractices());
        assertThat(replayed.decisions().getFirst().ready())
                .isEqualTo(original.decisions().getFirst().ready());
        assertThat(replayed.decisions().getFirst().reasonCodes())
                .isEqualTo(original.decisions().getFirst().reasonCodes());
    }

    @Test
    void shouldReturnEvidenceRefusalsAsTypedDecisions() {
        ArtifactSourceManifest manifest = coreManifest(builder, "job-refused", NOW);

        var prepared = builder.prepareAutomatedReviewReadiness(
                manifest, List.of(practiceRequiringComments()), "job-refused", NOW, null, Map.of());
        assertThat(prepared.readyPractices()).isEmpty();
        JsonNode report = mapper.valueToTree(prepared.report());
        JsonNode decision = report.path("decisions").get(0);
        assertThat(decision.path("ready").asBoolean()).isFalse();
        assertThat(decision.path("sourceChecks")
                        .get(0)
                        .path("reasonCodes")
                        .get(0)
                        .asString())
                .isEqualTo("SOURCE_NOT_AVAILABLE");
    }

    @Test
    void shouldAcceptCompleteCurrentEmptyEvidence() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        var manifest = builder.augment(files, Map.of(), "job-empty", plan(), metadata(COMMENTS, NOW));
        Practice practice = practiceRequiringComments();

        assertThat(builder.checkAutomatedReviewReadinessAsOfNow(manifest, List.of(practice))
                        .readyPractices())
                .containsExactly(practice);
    }

    @Test
    void shouldRejectEmptyContentWhenTheSourceContractForbidsIt() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        builder.augment(files, Map.of(), "job-invalid-empty", plan(), metadata(CORE, NOW));

        JsonNode core = findSource(mapper.readTree(files.get("inputs/manifest.json")), CORE.value());
        assertThat(core.path("state").path("availability").asString()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void shouldRejectAReplayAgainstDifferentContractBytes() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        ArtifactSourceManifest manifest =
                builder.augment(files, Map.of(), "job-old-contract", plan(), metadata(COMMENTS, NOW));
        ArtifactSourceManifest changedContract = new ArtifactSourceManifest(
                manifest.contractVersion(),
                "0".repeat(64),
                manifest.artifactKind(),
                manifest.capturedAt(),
                manifest.sources());

        assertThatThrownBy(() -> builder.checkAutomatedReviewReadinessAsOfNow(
                        changedContract, List.of(practiceRequiringComments())))
                .isInstanceOf(UnreplayableEvidenceException.class)
                .hasMessageContaining("no longer ships");
    }

    @Test
    void shouldReviewAConversationWithoutAFreshnessWatermark() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        Instant eventTime = NOW.minusSeconds(1);
        ArtifactSourceManifest manifest = builder.augment(
                files,
                Map.of(),
                "job-event-time",
                conversationPlan(),
                new ContextManifestBuilder.CaptureMetadata(
                        Map.of(CONVERSATION, SourceCompleteness.COMPLETE),
                        Map.of(),
                        Map.of(),
                        Map.of(CONVERSATION, eventTime),
                        Map.of(),
                        Set.of(CONVERSATION)));
        Practice practice = practiceRequiring(CONVERSATION, "conversation");

        assertThat(builder.checkAutomatedReviewReadinessAsOfNow(manifest, List.of(practice))
                        .readyPractices())
                .containsExactly(practice);
    }

    @Test
    void shouldAcceptMirrorWatermarkThatCoversTheRequestedSnapshot() {
        ContextManifestBuilder laterBuilder = builderAt(NOW.plusSeconds(60));
        ArtifactSourceManifest manifest = coreManifest(laterBuilder, "job-future-watermark", NOW.plusSeconds(60));

        Practice practice = practiceRequiring(CORE, "pr-core");
        assertThat(laterBuilder
                        .prepareAutomatedReviewReadiness(
                                manifest, List.of(practice), "job-future-watermark", NOW, null, Map.of())
                        .readyPractices())
                .containsExactly(practice);
    }

    @Test
    void shouldTreatAnIncoherentWatermarkAsUnknownRatherThanStale() {
        ArtifactSourceManifest manifest = coreManifest(builder, "job-invalid-watermark", NOW.plusSeconds(60));

        // The mirror records when a row last changed, not when it was last checked, so nothing here can
        // show the copy is behind.
        assertThat(builder.checkAutomatedReviewReadinessAsOfNow(manifest, List.of(practiceRequiring(CORE, "pr-core")))
                        .readyPractices())
                .hasSize(1);
    }

    @Test
    void shouldAssessDelayedWorkAgainstSubmissionTime() {
        ContextManifestBuilder delayedBuilder = builderAt(NOW.plusSeconds(3_600));
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("inputs/context/metadata.json", "{}".getBytes(StandardCharsets.UTF_8));
        ArtifactSourceManifest manifest = delayedBuilder.augment(
                files, Map.of("inputs/context/metadata.json", CORE), "job-delayed", plan(), metadata(CORE, NOW));
        Practice practice = practiceRequiring(CORE, "pr-core");

        var prepared = delayedBuilder.prepareAutomatedReviewReadiness(
                manifest, List.of(practice), "job-delayed", NOW, null, Map.of());
        assertThat(prepared.readyPractices()).containsExactly(practice);
        JsonNode sourceCheck = mapper.valueToTree(prepared.report())
                .path("decisions")
                .get(0)
                .path("sourceChecks")
                .get(0);
        assertThat(sourceCheck.path("checkedAt").asString())
                .isEqualTo(NOW.plusSeconds(3_600).toString());
        assertThat(sourceCheck.path("temporalAnchor").asString()).isEqualTo(NOW.toString());
    }

    @Test
    void shouldNotInferCompleteFromSourceCapabilityAlone() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("inputs/context/linked_work_items.json", "{\"workItems\":[{}]}".getBytes(StandardCharsets.UTF_8));
        ArtifactSourceManifest manifest = builder.augment(
                files,
                Map.of("inputs/context/linked_work_items.json", LINKED_ITEMS),
                "job-unreported-completeness",
                plan(),
                new ContextManifestBuilder.CaptureMetadata(
                        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of(LINKED_ITEMS)));

        JsonNode source = findSource(mapper.readTree(files.get("inputs/manifest.json")), LINKED_ITEMS.value());
        assertThat(source.path("state").path("completeness").asString()).isEqualTo("PARTIAL");
    }

    @Test
    void shouldRejectCaptureFactsForSourcesThatDoNotApplyToTheReviewedKind() {
        assertThatThrownBy(() -> builder.augment(
                        new LinkedHashMap<>(), Map.of(), "job-invalid-plan", plan(), metadata(CONVERSATION, NOW)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("do not apply to scm.pull_request");
    }

    @Test
    void shouldRejectAbsenceStateForbiddenByTheSourceContract() {
        var realCatalogs = new ClasspathArtifactSourceCatalogRegistry(mapper, Clock.systemUTC());
        ArtifactSourceContract diff = realCatalogs.requireSource(plan().contractVersion(), DIFF);
        ArtifactSourceContract restrictedDiff = new ArtifactSourceContract(
                diff.kind(),
                diff.displayName(),
                diff.description(),
                diff.selectionScope(),
                diff.artifactKinds(),
                diff.isDefaultRequirement(),
                diff.authority(),
                diff.identityPolicy(),
                diff.completenessPolicy(),
                diff.requiredQuality(),
                diff.privacyClass(),
                Set.of(SourceAbsenceState.UNAVAILABLE),
                diff.retentionPolicy(),
                diff.erasurePolicy(),
                diff.useDecisionIds());
        ArtifactSourceCatalogRegistry catalogs = mock(ArtifactSourceCatalogRegistry.class);
        when(catalogs.current()).thenReturn(realCatalogs.current());
        when(catalogs.requireSourcesFor(any(), any()))
                .thenAnswer(invocation ->
                        realCatalogs.requireSourcesFor(invocation.getArgument(0), invocation.getArgument(1)));
        when(catalogs.requireSource(any(), any())).thenAnswer(invocation -> {
            SourceKind kind = invocation.getArgument(1);
            return kind.equals(DIFF) ? restrictedDiff : realCatalogs.requireSource(invocation.getArgument(0), kind);
        });
        ContextManifestBuilder restrictedBuilder =
                new ContextManifestBuilder(mapper, catalogs, new PracticeSubjectEvaluator(mapper), Clock.systemUTC());

        // The live NOT_COLLECTED path: governance refused the source, and this contract says the diff may
        // never be reported that way.
        assertThatThrownBy(() -> restrictedBuilder.augment(
                        new LinkedHashMap<>(),
                        Map.of(),
                        "job-unsupported-absence-state",
                        plan(),
                        new ContextManifestBuilder.CaptureMetadata(
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                Map.of(
                                        DIFF,
                                        new SourceCaptureState.NotCollected(
                                                SourceAbsenceReason.GOVERNANCE_NOT_EFFECTIVE)),
                                Set.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not support absence state NOT_COLLECTED");
    }

    @Test
    void shouldRejectPracticeEvidenceFromAnotherProfile() {
        ArtifactSourceManifest manifest = builder.augment(
                new LinkedHashMap<>(), Map.of(), "job-profile-mismatch", plan(), metadata(COMMENTS, NOW));

        assertThatThrownBy(() -> builder.checkAutomatedReviewReadinessAsOfNow(
                        manifest, List.of(practiceRequiring(CONVERSATION, "conversation"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match manifest");
    }

    @Test
    void shouldRejectManifestThatOmitsAnApplicableSource() {
        ArtifactSourceManifest manifest = coreManifest(builder, "job-incomplete-sources", NOW);
        ArtifactSourceManifest incomplete = new ArtifactSourceManifest(
                manifest.contractVersion(),
                manifest.catalogDigest(),
                manifest.artifactKind(),
                manifest.capturedAt(),
                manifest.sources().stream()
                        .filter(source -> !source.kind().equals(COMMENTS))
                        .toList());

        assertThatThrownBy(() -> builder.checkAutomatedReviewReadinessAsOfNow(
                        incomplete, List.of(practiceRequiring(CORE, "pr-core"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("do not match the sources its artifact kind applies to");
    }

    @Test
    void shouldTreatAnEmptyRepositoryTreeAsValidCompleteEvidence() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        builder.augment(
                files,
                Map.of(),
                "job-empty-tree",
                plan(),
                new ContextManifestBuilder.CaptureMetadata(
                        Map.of(REPOSITORY_TREE, SourceCompleteness.COMPLETE),
                        Map.of(REPOSITORY_TREE, "commit:tree"),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Set.of(REPOSITORY_TREE)));

        JsonNode source = findSource(mapper.readTree(files.get("inputs/manifest.json")), REPOSITORY_TREE.value());
        assertThat(source.path("state").path("availability").asString()).isEqualTo("AVAILABLE");
        assertThat(source.path("state").path("content").asString()).isEqualTo("EMPTY");
        assertThat(source.path("state").path("completeness").asString()).isEqualTo("COMPLETE");
    }

    @Test
    void shouldTreatEmptyOutlineEvidenceAsPartial() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        builder.augment(
                files,
                Map.of(),
                "job-empty-outline",
                plan(),
                new ContextManifestBuilder.CaptureMetadata(
                        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of(OUTLINE)));

        JsonNode source = findSource(mapper.readTree(files.get("inputs/manifest.json")), OUTLINE.value());
        assertThat(source.path("state").path("availability").asString()).isEqualTo("AVAILABLE");
        assertThat(source.path("state").path("content").asString()).isEqualTo("EMPTY");
        assertThat(source.path("state").path("completeness").asString()).isEqualTo("PARTIAL");
    }

    @Test
    void shouldRejectUndocumentedRuntimeSourceKinds() {
        EvidenceSource undocumented = new EvidenceSource() {
            private final SourceKind kind = new SourceKind("scm.undocumented");

            @Override
            public Set<SourceKind> sourceKinds() {
                return Set.of(kind);
            }

            @Override
            public SourceKind sourceKindFor(String path) {
                return kind;
            }

            @Override
            public boolean supports(ContextRequest request) {
                return true;
            }

            @Override
            public void contribute(ContextRequest request, Map<String, byte[]> files) {}
        };

        assertThatThrownBy(() -> builder.validateEvidenceSources(List.of(undocumented)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown source kind");
    }

    private static EvidencePlan plan() {
        return new EvidencePlan(new SourceContractVersion("1.1.0"), ArtifactKinds.PULL_REQUEST);
    }

    private ContextManifestBuilder builderAt(Instant instant) {
        return new ContextManifestBuilder(
                mapper,
                new ClasspathArtifactSourceCatalogRegistry(mapper, Clock.systemUTC()),
                new PracticeSubjectEvaluator(mapper),
                Clock.fixed(instant, java.time.ZoneOffset.UTC));
    }

    private ArtifactSourceManifest coreManifest(ContextManifestBuilder target, String jobId, Instant observedAt) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        String path = "inputs/context/metadata.json";
        files.put(path, "{}".getBytes(StandardCharsets.UTF_8));
        return target.augment(files, Map.of(path, CORE), jobId, plan(), metadata(CORE, observedAt));
    }

    private static EvidencePlan conversationPlan() {
        return new EvidencePlan(new SourceContractVersion("1.1.0"), ArtifactKinds.CONVERSATION_THREAD);
    }

    private static ContextManifestBuilder.CaptureMetadata metadata(SourceKind kind, Instant observedAt) {
        return new ContextManifestBuilder.CaptureMetadata(
                Map.of(kind, SourceCompleteness.COMPLETE),
                Map.of(),
                Map.of(kind, observedAt),
                Map.of(),
                Map.of(),
                Set.of(kind));
    }

    @Nested
    @DisplayName("A practice whose subject is not in the work is not asked")
    class SubjectDeclarations {

        @Test
        void shouldWithholdThePracticeAndRecordThePredicateThatRuledItOut() {
            var prepared =
                    diffCapture("job-subject-absent", "diff --git a/src/App.java b/src/App.java\n@@ -1 +1 @@\n+x\n");

            AutomatedReviewReadinessResult result = builder.checkAutomatedReviewReadiness(
                    java.util.Objects.requireNonNull(prepared.manifest()),
                    List.of(withSubject(practiceRequiring(DIFF, "dependencies"), dependencySubject())),
                    NOW,
                    null,
                    prepared.files());

            assertThat(result.readyPractices()).isEmpty();
            var decision = result.decisions().getFirst();
            assertThat(decision.reasonCodes()).containsExactly(AutomatedReviewReadinessReason.SUBJECT_NOT_IN_THE_WORK);
            // The sentence a reader is shown, carried on the decision rather than re-derived downstream.
            assertThat(decision.subjectCheck()).isNotNull();
            assertThat(decision.subjectCheck().describedAs())
                    .isEqualTo("the change touches no dependency manifest or lockfile");
            // Nothing about the evidence was wrong, so nothing may say it was.
            assertThat(decision.sourceChecks()).allMatch(SourceReadinessCheck::meetsRequirements);
        }

        @Test
        void shouldAskThePracticeWhenTheSubjectIsInTheWork() {
            var prepared = diffCapture("job-subject-present", "diff --git a/pom.xml b/pom.xml\n@@ -1 +1 @@\n+<dep/>\n");

            AutomatedReviewReadinessResult result = builder.checkAutomatedReviewReadiness(
                    java.util.Objects.requireNonNull(prepared.manifest()),
                    List.of(withSubject(practiceRequiring(DIFF, "dependencies"), dependencySubject())),
                    NOW,
                    null,
                    prepared.files());

            assertThat(result.readyPractices()).hasSize(1);
            assertThat(result.decisions().getFirst().reasonCodes()).isEmpty();
        }

        /**
         * A caller holding the manifest but not the capture it describes — a replay, or any code path
         * that has not been taught to pass the bytes. It must ask the practice, never skip it.
         */
        @Test
        void shouldAskThePracticeWhenNobodySuppliedTheStagedBytes() {
            var prepared = diffCapture("job-no-bytes", "diff --git a/src/App.java b/src/App.java\n@@ -1 +1 @@\n+x\n");

            AutomatedReviewReadinessResult result = builder.checkAutomatedReviewReadinessAsOfNow(
                    java.util.Objects.requireNonNull(prepared.manifest()),
                    List.of(withSubject(practiceRequiring(DIFF, "dependencies"), dependencySubject())));

            assertThat(result.readyPractices()).hasSize(1);
        }

        /**
         * "We could not look" outranks "there was nothing of this kind here". Judging a subject over a
         * capture we could not read would dress an instrument failure up as a fact about somebody's work,
         * so the subject is not even asked and no subject check is recorded.
         */
        @Test
        void shouldNotJudgeTheSubjectOfAPracticeWhoseEvidenceCouldNotBeRead() {
            ArtifactSourceManifest manifest = builder.augment(
                    new LinkedHashMap<>(),
                    Map.of(),
                    "job-unreadable",
                    plan(),
                    new ContextManifestBuilder.CaptureMetadata(
                            Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of()));

            AutomatedReviewReadinessResult result = builder.checkAutomatedReviewReadiness(
                    manifest,
                    List.of(withSubject(practiceRequiring(DIFF, "dependencies"), dependencySubject())),
                    NOW,
                    null,
                    Map.of());

            assertThat(result.readyPractices()).isEmpty();
            var decision = result.decisions().getFirst();
            assertThat(decision.subjectCheck()).isNull();
            assertThat(decision.reasonCodes()).doesNotContain(AutomatedReviewReadinessReason.SUBJECT_NOT_IN_THE_WORK);
            assertThat(decision.sourceChecks().getFirst().reasonCodes())
                    .containsExactly(SourceReadinessReason.SOURCE_NOT_AVAILABLE);
        }

        private PreparedDiff diffCapture(String jobId, String diff) {
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put("inputs/context/diff.patch", diff.getBytes(StandardCharsets.UTF_8));
            ArtifactSourceManifest manifest = builder.augment(
                    files,
                    Map.of("inputs/context/diff.patch", DIFF),
                    jobId,
                    plan(),
                    new ContextManifestBuilder.CaptureMetadata(
                            Map.of(DIFF, SourceCompleteness.COMPLETE),
                            Map.of(DIFF, SourceContentState.NON_EMPTY),
                            Map.of(DIFF, "abc123"),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Set.of(DIFF)));
            return new PreparedDiff(manifest, files);
        }

        private record PreparedDiff(ArtifactSourceManifest manifest, Map<String, byte[]> files) {}
    }

    private static PracticeSubject dependencySubject() {
        return new PracticeSubject(
                "the change touches no dependency manifest or lockfile",
                List.of(PracticeSubjectClause.changedPathMatches(List.of("**/pom.xml", "**/package.json"))));
    }

    /** Re-declares the practice's single binding with a subject, leaving its evidence untouched. */
    private static Practice withSubject(Practice practice, PracticeSubject subject) {
        PracticeBinding binding = practice.getBindings().getFirst();
        practice.setBindings(List.of(new PracticeBinding(
                binding.signals(), binding.needs(), binding.onDrafts(), binding.subject(), subject)));
        return practice;
    }

    private static Practice practiceRequiringComments() {
        return practiceRequiring(COMMENTS, "review-comments");
    }

    private static Practice practiceRequiring(SourceKind sourceKind, String slug) {
        return practiceRequiring(sourceKind, slug, EvidenceStance.REQUIRED);
    }

    private static Practice practiceRequiring(SourceKind sourceKind, String slug, EvidenceStance stance) {
        boolean conversation = sourceKind.equals(CONVERSATION);
        Practice practice = new Practice();
        practice.setSlug(slug);
        practice.setBindings(List.of(PracticeBinding.on(
                PracticeTestEvidence.defaultSignal(
                        conversation ? ArtifactKinds.CONVERSATION_THREAD : ArtifactKinds.PULL_REQUEST),
                List.of(new PracticeEvidenceRequirement(sourceKind, stance)))));
        practice.setAutomatedReviewPolicy(new PracticeAutomatedReviewPolicy(
                new SourceContractVersion("1.1.0"),
                new PracticeAutomatedReview(
                        PracticeAutomatedReviewMode.LANGUAGE_MODEL,
                        PracticeEvidenceSufficiency.SUFFICIENT_WHEN_REQUIREMENTS_MET),
                PracticeInsufficientEvidenceAction.SKIP_AUTOMATED_REVIEW,
                List.of(),
                null));
        return practice;
    }

    private static JsonNode findSource(JsonNode root, String kind) {
        for (JsonNode source : root.path("sources")) {
            if (kind.equals(source.path("kind").asString())) return source;
        }
        throw new AssertionError("Missing source " + kind);
    }
}
