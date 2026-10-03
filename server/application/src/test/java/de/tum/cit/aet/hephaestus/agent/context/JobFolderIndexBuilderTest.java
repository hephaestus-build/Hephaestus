package de.tum.cit.aet.hephaestus.agent.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.runtime.ProvenanceDigest;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceContract;
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
import de.tum.cit.aet.hephaestus.integration.core.spi.ActorRole;
import de.tum.cit.aet.hephaestus.integration.scm.domain.workdir.GitRepositoryManager;
import de.tum.cit.aet.hephaestus.practices.EvidenceStance;
import de.tum.cit.aet.hephaestus.practices.PracticeAutomatedReview;
import de.tum.cit.aet.hephaestus.practices.PracticeAutomatedReviewMode;
import de.tum.cit.aet.hephaestus.practices.PracticeAutomatedReviewPolicy;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceLimitation;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceRequirement;
import de.tum.cit.aet.hephaestus.practices.PracticeEvidenceSufficiency;
import de.tum.cit.aet.hephaestus.practices.PracticeInsufficientEvidenceAction;
import de.tum.cit.aet.hephaestus.practices.PracticePrecondition;
import de.tum.cit.aet.hephaestus.practices.PracticePreconditionClause;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.review.AutomatedReviewFence;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class JobFolderIndexBuilderTest extends BaseUnitTest {

    private static final SourceKind DIFF = new SourceKind("scm.pull-request.diff");
    private static final SourceKind CORE = new SourceKind("scm.pull-request.core");
    private static final SourceKind COMMENTS = new SourceKind("scm.pull-request.comments");
    private static final SourceKind CONVERSATION = new SourceKind("slack.conversation.thread");
    private static final SourceKind LINKED_ITEMS = new SourceKind("scm.linked-work-items");
    private static final SourceKind REPOSITORY_TREE = new SourceKind("scm.repository.tree");
    private static final SourceKind OUTLINE = new SourceKind("outline.documents");
    private static final SourceKind PROJECT_INVENTORY = new SourceKind("workspace.project-inventory");
    private static final Instant NOW = Instant.parse("2026-09-11T10:00:00Z");
    private static final String CHANGE_PATH = "context/change.json";
    private static final byte[] CHANGE_JSON =
            "{\"base_sha\":\"abc123\",\"head_sha\":\"def456\"}".getBytes(StandardCharsets.UTF_8);

    private static final AutomatedReviewFence NO_FENCE = new AutomatedReviewFence(Map.of());

    private final JsonMapper mapper = JsonMapper.builder().build();
    private JobFolderIndexBuilder builder;

    @BeforeEach
    void setUp() {
        builder = builderAt(NOW);
    }

    @Test
    void shouldExposeCitationDigestsWithoutInternalMetadata() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(CHANGE_PATH, CHANGE_JSON);
        EvidencePlan plan = plan();

        builder.augment(
                files,
                Map.of(CHANGE_PATH, DIFF),
                "job-42",
                plan,
                new JobFolderIndexBuilder.CaptureMetadata(
                        Map.of(DIFF, SourceCompleteness.COMPLETE),
                        Map.of(DIFF, "abc123"),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Set.of(DIFF)));

        JsonNode visible = mapper.readTree(files.get("INDEX.json"));
        assertThat(visible.path("contractVersion").asString()).isEqualTo("1.3.0");
        assertThat(visible.toString()).doesNotContain("job-42").doesNotContain("workspaceId");
        JsonNode diffSource = findSource(visible, DIFF.value());
        assertThat(diffSource.path("state").path("availability").asString()).isEqualTo("AVAILABLE");
        assertThat(diffSource.path("artifacts").get(0).path("path").asString()).isEqualTo(CHANGE_PATH);
        assertThat(diffSource.path("artifacts").get(0).path("sha256").asString())
                .isEqualTo(ProvenanceDigest.sha256Hex(CHANGE_JSON));
    }

    @Test
    void shouldReportASourceWithNoCollectorAsUnavailableRatherThanUnwanted() {
        Map<String, byte[]> files = new LinkedHashMap<>();

        builder.augment(files, Map.of(), "job-7", plan(), metadata(COMMENTS, NOW));

        JsonNode visible = mapper.readTree(files.get("INDEX.json"));
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

    @ParameterizedTest
    @ValueSource(strings = {"1.0.0", "1.1.0"})
    void shouldRefuseANewCaptureUnderARetiredContract(String version) {
        var retired = new EvidencePlan(new SourceContractVersion(version), ArtifactKinds.PULL_REQUEST);
        assertThatThrownBy(() -> builder.stagedSources(retired))
                .isInstanceOf(JobPreparationException.class)
                .hasMessageContaining(version);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldAuthorizeCaptureWhenTheCatalogPermitsAutomatedPracticeReview(boolean permitted) {
        ArtifactSourceCatalogRegistry catalogs = mock(ArtifactSourceCatalogRegistry.class);
        JobFolderIndexBuilder target = new JobFolderIndexBuilder(
                mapper, catalogs, new PracticePreconditionEvaluator(mapper), NO_FENCE, Clock.systemUTC());
        SourceContractVersion version = new SourceContractVersion("1.3.0");
        when(catalogs.isSourceUsePermitted(version, DIFF, SourceUsePurpose.AUTOMATED_PRACTICE_REVIEW))
                .thenReturn(permitted);

        assertThat(target.isSourceUsePermitted(version, DIFF)).isEqualTo(permitted);
    }

    @Test
    void shouldRepresentAWhollyWithheldSourceAsRedactedWithoutLeakingArtifacts() {
        Map<String, byte[]> files = new LinkedHashMap<>();

        builder.augment(
                files,
                Map.of(),
                "job-redacted",
                plan(),
                new JobFolderIndexBuilder.CaptureMetadata(
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Map.of(COMMENTS, new SourceCaptureState.Redacted(SourceAbsenceReason.CONSENT_NOT_ACTIVE)),
                        Set.of(COMMENTS)));

        JsonNode source = findSource(mapper.readTree(files.get("INDEX.json")), COMMENTS.value());
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
    void shouldAllowPracticeSpecificOccasionJudgmentForACompleteEmptyDiff() {
        // Capture readiness qualifies the evidence, not the practice-specific occasion or outcome. The
        // range is pinned either way; that nothing changed inside it is the reported content state.
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(CHANGE_PATH, CHANGE_JSON);
        JobFolderIndex manifest = builder.augment(
                files,
                Map.of(CHANGE_PATH, DIFF),
                "job-empty-diff",
                plan(),
                new JobFolderIndexBuilder.CaptureMetadata(
                        Map.of(DIFF, SourceCompleteness.COMPLETE),
                        Map.of(DIFF, SourceContentState.EMPTY),
                        Map.of(DIFF, "abc123"),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Set.of(DIFF)));

        Practice practice = practiceRequiring(DIFF, "needs-diff");
        AutomatedReviewReadinessResult accepted =
                builder.checkAutomatedReviewReadinessAsOfNow(manifest, List.of(practice));

        assertThat(accepted.readyPractices()).containsExactly(practice);
        assertThat(accepted.decisions().getFirst().sourceChecks().getFirst().reasonCodes())
                .isEmpty();
    }

    @Test
    void shouldNotTreatFailedDiffCaptureAsVerifiedEmpty() {
        var failed = new SourceCaptureState.CollectionError(SourceAbsenceReason.PROVIDER_FAILURE);
        JobFolderIndex manifest = builder.augment(
                new LinkedHashMap<>(),
                Map.of(),
                "job-failed-diff",
                plan(),
                new JobFolderIndexBuilder.CaptureMetadata(
                        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(DIFF, failed), Set.of(DIFF)));
        var readiness =
                builder.checkAutomatedReviewReadinessAsOfNow(manifest, List.of(practiceRequiring(DIFF, "needs-diff")));
        assertThat(readiness.readyPractices()).isEmpty();
        assertThat(readiness.decisions().getFirst().sourceChecks().getFirst().reasonCodes())
                .containsExactly(SourceReadinessReason.SOURCE_NOT_AVAILABLE);
    }

    @Test
    void shouldRejectPartialDiffEvenWhenItsCapturedBytesAreEmpty() {
        assertThatThrownBy(() -> builder.augment(
                        new LinkedHashMap<>(Map.of(CHANGE_PATH, new byte[0])),
                        Map.of(CHANGE_PATH, DIFF),
                        "job-partial-empty-diff",
                        plan(),
                        new JobFolderIndexBuilder.CaptureMetadata(
                                Map.of(DIFF, SourceCompleteness.PARTIAL),
                                Map.of(DIFF, SourceContentState.EMPTY),
                                Map.of(DIFF, "abc123"),
                                Map.of(),
                                Map.of(),
                                Map.of(),
                                Set.of(DIFF))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("completeness forbidden");
    }

    /**
     * Incompleteness and emptiness are facts about a capture that happened; where none did, absence is
     * the whole answer.
     */
    @Test
    void shouldNameOnlyAbsenceWhenNothingWasCaptured() {
        JobFolderIndex manifest = builder.augment(
                new LinkedHashMap<>(),
                Map.of(),
                "job-absent-diff",
                plan(),
                new JobFolderIndexBuilder.CaptureMetadata(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of()));

        AutomatedReviewReadinessResult refused =
                builder.checkAutomatedReviewReadinessAsOfNow(manifest, List.of(practiceRequiring(DIFF, "needs-diff")));

        assertThat(refused.readyPractices()).isEmpty();
        assertThat(refused.decisions().getFirst().sourceChecks().getFirst().reasonCodes())
                .containsExactly(SourceReadinessReason.SOURCE_NOT_AVAILABLE);
    }

    @Test
    void shouldRefuseReadinessWhenCoreReportsAnUpstreamDeletion() {
        var unavailable = new SourceCaptureState.Unavailable(SourceAbsenceReason.NOT_FOUND);
        JobFolderIndex manifest = builder.augment(
                new LinkedHashMap<>(),
                Map.of(),
                "job-deleted-core",
                plan(),
                new JobFolderIndexBuilder.CaptureMetadata(
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
        files.put(CHANGE_PATH, CHANGE_JSON);
        JobFolderIndex manifest = builder.augment(
                files,
                Map.of(CHANGE_PATH, DIFF),
                "job-good-diff",
                plan(),
                new JobFolderIndexBuilder.CaptureMetadata(
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
        String path = "context/comments.json";
        files.put(path, "[]".getBytes(StandardCharsets.UTF_8));
        JobFolderIndex manifest = builder.augment(
                files,
                Map.of(path, COMMENTS),
                "job-empty-comments",
                plan(),
                new JobFolderIndexBuilder.CaptureMetadata(
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
        String path = "context/comments.json";
        files.put(path, "[{}]".getBytes(StandardCharsets.UTF_8));
        JobFolderIndex manifest = builder.augment(
                files,
                Map.of(path, COMMENTS),
                "job-partial-comments",
                plan(),
                new JobFolderIndexBuilder.CaptureMetadata(
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
        JobFolderIndex manifest = builder.augment(
                files,
                Map.of(path, REPOSITORY_TREE),
                "job-truncated-tree",
                plan(),
                new JobFolderIndexBuilder.CaptureMetadata(
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
                        new JobFolderIndexBuilder.CaptureMetadata(
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
        JobFolderIndex manifest = coreManifest(builder, "job-unsupported-assessment", NOW);
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
                practice.setEvidenceRequirements(List.of());
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
    void shouldKeepACaptureFailureAsItsOwnReasonWhenThePracticeAlsoNeedsHumanReview() {
        JobFolderIndex manifest = coreManifest(builder, "job-human-review-failed-capture", NOW);
        Practice practice = practiceRequiringComments();
        PracticeAutomatedReviewPolicy policy = practice.getAutomatedReviewPolicy();
        practice.setAutomatedReviewPolicy(new PracticeAutomatedReviewPolicy(
                policy.sourceContractVersion(),
                new PracticeAutomatedReview(
                        PracticeAutomatedReviewMode.LANGUAGE_MODEL,
                        PracticeEvidenceSufficiency.DECLARED_EVIDENCE_INSUFFICIENT),
                policy.whenEvidenceIsInsufficient(),
                policy.knownLimitations(),
                new PracticeEvidenceLimitation("AT_CLOSE_STATE_NOT_CAPTURED", "Nothing records the close.")));

        AutomatedReviewReadinessResult result =
                builder.checkAutomatedReviewReadinessAsOfNow(manifest, List.of(practice));

        assertThat(result.readyPractices()).isEmpty();
        var decision = result.decisions().getFirst();
        assertThat(decision.reasonCodes())
                .containsExactly(AutomatedReviewReadinessReason.DECLARED_EVIDENCE_INSUFFICIENT);
        assertThat(decision.sourceChecks())
                .singleElement()
                .satisfies(check ->
                        assertThat(check.reasonCodes()).containsExactly(SourceReadinessReason.SOURCE_NOT_AVAILABLE));
    }

    @Test
    void shouldReviewWorkUnchangedUpstreamSinceTheLastSynchronization() {
        // A mirrored record upstream has not touched is current, however old the last write is.
        JobFolderIndex manifest = coreManifest(builder, "job-quiet-mirror", NOW.minusSeconds(14 * 86_400));

        assertThat(builder.checkAutomatedReviewReadiness(
                                manifest, List.of(practiceRequiring(CORE, "pr-core")), NOW, Map.of(), null)
                        .readyPractices())
                .hasSize(1);
    }

    @Test
    void shouldProduceTheSameReadinessResultWhenReplayed() {
        JobFolderIndex manifest = coreManifest(builder, "job-replay", NOW);
        List<Practice> practices = List.of(practiceRequiring(CORE, "pr-core"));

        var original = builder.checkAutomatedReviewReadiness(manifest, practices, NOW, Map.of(), null);
        // Readiness is a pure function of the recorded evidence and anchor, so re-evaluating later must
        // produce an identical result.
        var replayed = builderAt(NOW.plusSeconds(90 * 86_400))
                .checkAutomatedReviewReadiness(manifest, practices, NOW, Map.of(), null);

        assertThat(replayed.readyPractices()).hasSameElementsAs(original.readyPractices());
        assertThat(replayed.decisions().getFirst().ready())
                .isEqualTo(original.decisions().getFirst().ready());
        assertThat(replayed.decisions().getFirst().reasonCodes())
                .isEqualTo(original.decisions().getFirst().reasonCodes());
    }

    @Test
    void shouldReturnEvidenceRefusalsAsTypedDecisions() {
        JobFolderIndex manifest = coreManifest(builder, "job-refused", NOW);

        var prepared = builder.prepareAutomatedReviewReadiness(
                manifest, List.of(practiceRequiringComments()), NOW, Map.of(), null);
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

        JsonNode core = findSource(mapper.readTree(files.get("INDEX.json")), CORE.value());
        assertThat(core.path("state").path("availability").asString()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void shouldRejectAReplayAgainstDifferentContractBytes() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        JobFolderIndex manifest = builder.augment(files, Map.of(), "job-old-contract", plan(), metadata(COMMENTS, NOW));
        JobFolderIndex changedContract = new JobFolderIndex(
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
        JobFolderIndex manifest = builder.augment(
                files,
                Map.of(),
                "job-event-time",
                conversationPlan(),
                new JobFolderIndexBuilder.CaptureMetadata(
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
        JobFolderIndexBuilder laterBuilder = builderAt(NOW.plusSeconds(60));
        JobFolderIndex manifest = coreManifest(laterBuilder, "job-future-watermark", NOW.plusSeconds(60));

        Practice practice = practiceRequiring(CORE, "pr-core");
        assertThat(laterBuilder
                        .prepareAutomatedReviewReadiness(manifest, List.of(practice), NOW, Map.of(), null)
                        .readyPractices())
                .containsExactly(practice);
    }

    @Test
    void shouldTreatAnIncoherentWatermarkAsUnknownRatherThanStale() {
        JobFolderIndex manifest = coreManifest(builder, "job-invalid-watermark", NOW.plusSeconds(60));

        // The mirror records when a row last changed, not when it was last checked, so nothing here can
        // show the copy is behind.
        assertThat(builder.checkAutomatedReviewReadinessAsOfNow(manifest, List.of(practiceRequiring(CORE, "pr-core")))
                        .readyPractices())
                .hasSize(1);
    }

    @Test
    void shouldAssessDelayedWorkAgainstSubmissionTime() {
        JobFolderIndexBuilder delayedBuilder = builderAt(NOW.plusSeconds(3_600));
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("context/metadata.json", "{}".getBytes(StandardCharsets.UTF_8));
        JobFolderIndex manifest = delayedBuilder.augment(
                files, Map.of("context/metadata.json", CORE), "job-delayed", plan(), metadata(CORE, NOW));
        Practice practice = practiceRequiring(CORE, "pr-core");

        var prepared = delayedBuilder.prepareAutomatedReviewReadiness(manifest, List.of(practice), NOW, Map.of(), null);
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
        files.put("context/linked_work_items.json", "{\"workItems\":[{}]}".getBytes(StandardCharsets.UTF_8));
        JobFolderIndex manifest = builder.augment(
                files,
                Map.of("context/linked_work_items.json", LINKED_ITEMS),
                "job-unreported-completeness",
                plan(),
                new JobFolderIndexBuilder.CaptureMetadata(
                        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of(LINKED_ITEMS)));

        JsonNode source = findSource(mapper.readTree(files.get("INDEX.json")), LINKED_ITEMS.value());
        assertThat(source.path("state").path("completeness").asString()).isEqualTo("PARTIAL");
    }

    @Test
    void shouldAcceptAWorkspaceSourceBeyondTheReviewedWorkKind() {
        var index = builder.augment(
                new LinkedHashMap<>(), Map.of(), "cross-source-review", plan(), metadata(CONVERSATION, NOW));
        assertThat(index.sources())
                .anySatisfy(source -> assertThat(source.kind()).isEqualTo(CONVERSATION));
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
        when(catalogs.requireSource(any(), any())).thenAnswer(invocation -> {
            SourceKind kind = invocation.getArgument(1);
            return kind.equals(DIFF) ? restrictedDiff : realCatalogs.requireSource(invocation.getArgument(0), kind);
        });
        JobFolderIndexBuilder restrictedBuilder = new JobFolderIndexBuilder(
                mapper, catalogs, new PracticePreconditionEvaluator(mapper), NO_FENCE, Clock.systemUTC());

        // The live NOT_COLLECTED path: governance refused the source, and this contract says the diff may
        // never be reported that way.
        assertThatThrownBy(() -> restrictedBuilder.augment(
                        new LinkedHashMap<>(),
                        Map.of(),
                        "job-unsupported-absence-state",
                        plan(),
                        new JobFolderIndexBuilder.CaptureMetadata(
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
        JobFolderIndex manifest = builder.augment(
                new LinkedHashMap<>(), Map.of(), "job-profile-mismatch", plan(), metadata(COMMENTS, NOW));

        assertThatThrownBy(() -> builder.checkAutomatedReviewReadinessAsOfNow(
                        manifest, List.of(practiceRequiring(CONVERSATION, "conversation"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not match manifest");
    }

    @Test
    void shouldRejectManifestThatOmitsAnApplicableSource() {
        JobFolderIndex manifest = coreManifest(builder, "job-incomplete-sources", NOW);
        JobFolderIndex incomplete = new JobFolderIndex(
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
                .hasMessageContaining("do not match the complete source catalog");
    }

    @Test
    void shouldTreatAnEmptyRepositoryTreeAsValidCompleteEvidence() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        builder.augment(
                files,
                Map.of(),
                "job-empty-tree",
                plan(),
                new JobFolderIndexBuilder.CaptureMetadata(
                        Map.of(REPOSITORY_TREE, SourceCompleteness.COMPLETE),
                        Map.of(REPOSITORY_TREE, "commit:tree"),
                        Map.of(),
                        Map.of(),
                        Map.of(),
                        Set.of(REPOSITORY_TREE)));

        JsonNode source = findSource(mapper.readTree(files.get("INDEX.json")), REPOSITORY_TREE.value());
        assertThat(source.path("state").path("availability").asString()).isEqualTo("AVAILABLE");
        assertThat(source.path("state").path("content").asString()).isEqualTo("EMPTY");
        assertThat(source.path("state").path("completeness").asString()).isEqualTo("COMPLETE");
    }

    @Test
    void shouldTreatTheFullPermittedOutlineCorpusAsComplete() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        builder.augment(
                files,
                Map.of(),
                "job-empty-outline",
                plan(),
                new JobFolderIndexBuilder.CaptureMetadata(
                        Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of(OUTLINE)));

        JsonNode source = findSource(mapper.readTree(files.get("INDEX.json")), OUTLINE.value());
        assertThat(source.path("state").path("availability").asString()).isEqualTo("AVAILABLE");
        assertThat(source.path("state").path("content").asString()).isEqualTo("EMPTY");
        assertThat(source.path("state").path("completeness").asString()).isEqualTo("COMPLETE");
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
        return new EvidencePlan(new SourceContractVersion("1.3.0"), ArtifactKinds.PULL_REQUEST);
    }

    private JobFolderIndexBuilder builderAt(Instant instant) {
        return new JobFolderIndexBuilder(
                mapper,
                new ClasspathArtifactSourceCatalogRegistry(mapper, Clock.systemUTC()),
                new PracticePreconditionEvaluator(mapper),
                NO_FENCE,
                Clock.fixed(instant, ZoneOffset.UTC));
    }

    private JobFolderIndex coreManifest(JobFolderIndexBuilder target, String jobId, Instant observedAt) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        String path = "context/metadata.json";
        files.put(path, "{}".getBytes(StandardCharsets.UTF_8));
        return target.augment(files, Map.of(path, CORE), jobId, plan(), metadata(CORE, observedAt));
    }

    private static EvidencePlan conversationPlan() {
        return new EvidencePlan(new SourceContractVersion("1.3.0"), ArtifactKinds.CONVERSATION_THREAD);
    }

    private static JobFolderIndexBuilder.CaptureMetadata metadata(SourceKind kind, Instant observedAt) {
        return new JobFolderIndexBuilder.CaptureMetadata(
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
            var prepared = changeCapture("job-subject-absent");

            AutomatedReviewReadinessResult result = builder.checkAutomatedReviewReadiness(
                    Objects.requireNonNull(prepared.manifest()),
                    List.of(withSubject(practiceRequiring(DIFF, "dependencies"), dependencySubject())),
                    NOW,
                    prepared.files(),
                    change(Set.of("src/App.java")));

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
            var prepared = changeCapture("job-subject-present");

            AutomatedReviewReadinessResult result = builder.checkAutomatedReviewReadiness(
                    Objects.requireNonNull(prepared.manifest()),
                    List.of(withSubject(practiceRequiring(DIFF, "dependencies"), dependencySubject())),
                    NOW,
                    prepared.files(),
                    change(Set.of("src/App.java", "pom.xml")));

            assertThat(result.readyPractices()).hasSize(1);
            assertThat(result.decisions().getFirst().reasonCodes()).isEmpty();
        }

        /**
         * A caller holding the manifest but not the change it describes — a replay, or any code path
         * that has not been taught to pass it. It must ask the practice, never skip it.
         */
        @Test
        void shouldAskThePracticeWhenNobodySuppliedTheChange() {
            var prepared = changeCapture("job-no-change");
            List<Practice> practices =
                    List.of(withSubject(practiceRequiring(DIFF, "dependencies"), dependencySubject()));

            AutomatedReviewReadinessResult asOfNow = builder.checkAutomatedReviewReadinessAsOfNow(
                    Objects.requireNonNull(prepared.manifest()), practices);
            AutomatedReviewReadinessResult withoutChange = builder.checkAutomatedReviewReadiness(
                    Objects.requireNonNull(prepared.manifest()), practices, NOW, prepared.files(), null);

            assertThat(asOfNow.readyPractices()).hasSize(1);
            assertThat(withoutChange.readyPractices()).hasSize(1);
        }

        /**
         * "We could not look" outranks "there was nothing of this kind here". Judging a subject over a
         * capture we could not read would dress an instrument failure up as a fact about somebody's work,
         * so the subject is not even asked and no subject check is recorded.
         */
        @Test
        void shouldNotJudgeTheSubjectOfAPracticeWhoseEvidenceCouldNotBeRead() {
            JobFolderIndex manifest = builder.augment(
                    new LinkedHashMap<>(),
                    Map.of(),
                    "job-unreadable",
                    plan(),
                    new JobFolderIndexBuilder.CaptureMetadata(
                            Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Set.of()));

            AutomatedReviewReadinessResult result = builder.checkAutomatedReviewReadiness(
                    manifest,
                    List.of(withSubject(practiceRequiring(DIFF, "dependencies"), dependencySubject())),
                    NOW,
                    Map.of(),
                    change(Set.of("pom.xml")));

            assertThat(result.readyPractices()).isEmpty();
            var decision = result.decisions().getFirst();
            assertThat(decision.subjectCheck()).isNull();
            assertThat(decision.reasonCodes()).doesNotContain(AutomatedReviewReadinessReason.SUBJECT_NOT_IN_THE_WORK);
            assertThat(decision.sourceChecks().getFirst().reasonCodes())
                    .containsExactly(SourceReadinessReason.SOURCE_NOT_AVAILABLE);
        }

        /** The pinned range on disk; what it touches is read from the mirror, here an inline change. */
        private PreparedDiff changeCapture(String jobId) {
            Map<String, byte[]> files = new LinkedHashMap<>();
            files.put(CHANGE_PATH, CHANGE_JSON);
            JobFolderIndex manifest = builder.augment(
                    files,
                    Map.of(CHANGE_PATH, DIFF),
                    jobId,
                    plan(),
                    new JobFolderIndexBuilder.CaptureMetadata(
                            Map.of(DIFF, SourceCompleteness.COMPLETE),
                            Map.of(DIFF, SourceContentState.NON_EMPTY),
                            Map.of(DIFF, "abc123"),
                            Map.of(),
                            Map.of(),
                            Map.of(),
                            Set.of(DIFF)));
            return new PreparedDiff(manifest, files);
        }

        private record PreparedDiff(JobFolderIndex manifest, Map<String, byte[]> files) {}

        private static ReviewChange change(Set<String> paths) {
            return new ReviewChange() {
                @Override
                public Set<String> changedPaths() {
                    return paths;
                }

                @Override
                public String text() {
                    return "";
                }
            };
        }
    }

    private static PracticePrecondition dependencySubject() {
        return new PracticePrecondition(
                "the change touches no dependency manifest or lockfile",
                List.of(PracticePreconditionClause.changedPathMatches(List.of("**/pom.xml", "**/package.json"))));
    }

    /** Sets the applicability predicate without changing evidence requirements. */
    private static Practice withSubject(Practice practice, PracticePrecondition subject) {
        practice.setPrecondition(subject);
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
        practice.setSignals(List.of(PracticeTestEvidence.defaultSignal(
                conversation ? ArtifactKinds.CONVERSATION_THREAD : ArtifactKinds.PULL_REQUEST)));
        practice.setEvidenceRequirements(List.of(new PracticeEvidenceRequirement(sourceKind, stance)));
        practice.setReviewWhen(Map.of());
        practice.setSubject(ActorRole.AUTHOR);
        practice.setPrecondition(null);
        practice.setAutomatedReviewPolicy(new PracticeAutomatedReviewPolicy(
                new SourceContractVersion("1.3.0"),
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
