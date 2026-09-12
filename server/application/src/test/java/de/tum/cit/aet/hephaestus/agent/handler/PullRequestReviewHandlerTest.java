package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.context.ContextManifestBuilder;
import de.tum.cit.aet.hephaestus.agent.context.ContextRequest;
import de.tum.cit.aet.hephaestus.agent.context.EvidencePlan;
import de.tum.cit.aet.hephaestus.agent.context.JobEvidenceFiles;
import de.tum.cit.aet.hephaestus.agent.context.PreparedEvidence;
import de.tum.cit.aet.hephaestus.agent.context.SecretScan;
import de.tum.cit.aet.hephaestus.agent.context.WorkspaceContextBuilder;
import de.tum.cit.aet.hephaestus.agent.handler.PracticeDetectionDeliveryService.PreparedObservations;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ExistingDeliveryLookup;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobDeliveryException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobPreparationException;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobSubmission;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobSubmissionRequest;
import de.tum.cit.aet.hephaestus.agent.handler.spi.ObservationsRefusedException;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.task.TaskEnvelopeWriter;
import de.tum.cit.aet.hephaestus.evidence.ArtifactSourceManifest;
import de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessReport;
import de.tum.cit.aet.hephaestus.integration.core.events.RepositoryRef;
import de.tum.cit.aet.hephaestus.integration.core.events.ScmEventPayload;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.Issue;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeTestEvidence;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.Assessment;
import de.tum.cit.aet.hephaestus.practices.model.AssessmentStatus;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy;
import de.tum.cit.aet.hephaestus.practices.model.PracticeRevision;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.model.Severity;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class PullRequestReviewHandlerTest extends BaseUnitTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Mock
    private JobEvidenceFiles cas;

    @Mock
    private PracticeRepository practiceRepository;

    @Mock
    private WorkspaceContextBuilder workspaceContextBuilder;

    @Mock
    private PracticeDetectionDeliveryService deliveryService;

    @Mock
    private SecretDiffScanner secretScanner;

    private String capturedPaths = "";

    @Mock
    private FeedbackDeliveryService feedbackService;

    @Mock
    private de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository observationRepository;

    private static final Long WORKSPACE_ID = 99L;

    private PracticeDetectionResultParser resultParser;
    private TaskEnvelopeWriter taskEnvelopeWriter;
    private PullRequestReviewHandler handler;

    @BeforeEach
    void setUp() {
        resultParser = new PracticeDetectionResultParser(objectMapper);
        taskEnvelopeWriter = new TaskEnvelopeWriter(objectMapper);
        var practiceCatalogInjector = new PracticeCatalogInjector(
                objectMapper, practiceRepository, InContextDeliveryGateFixtures.workspaceDefaults());
        handler = new PullRequestReviewHandler(
                objectMapper,
                cas,
                practiceCatalogInjector,
                new PracticeReviewPreparation(workspaceContextBuilder, practiceCatalogInjector, taskEnvelopeWriter),
                resultParser,
                new de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionResultParser(),
                deliveryService,
                feedbackService,
                secretScanner,
                new FeedbackResponseSuppressionFilter(
                        org.mockito.Mockito.mock(
                                de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.class),
                        org.mockito.Mockito.mock(
                                de.tum.cit.aet.hephaestus.practices.observation.reaction.ReactionRepository.class),
                        org.mockito.Mockito.mock(FeedbackLedgerRecorder.class),
                        new de.tum.cit.aet.hephaestus.practices.review.PracticeReviewProperties(
                                false, 15, 5, false, false)),
                InContextDeliveryGateFixtures.gate(
                        practiceRepository,
                        org.mockito.Mockito.mock(
                                de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.class),
                        org.mockito.Mockito.mock(FeedbackLedgerRecorder.class)),
                observationRepository);
        lenient().when(cas.inspect(any(), anyString(), anyString(), any())).thenAnswer(invocation -> {
            JobEvidenceFiles.TextInspection<?> inspection = invocation.getArgument(3);
            return java.util.Optional.of(inspection.inspect(new java.io.StringReader(capturedPaths)));
        });
    }

    @Test
    void shouldFinishWithoutComposingFeedbackWhenObservationsWereRefused() {
        var refused = new de.tum.cit.aet.hephaestus.agent.job.AgentJob();
        var metadata = objectMapper.createObjectNode();
        metadata.putObject(ObservationAdmissionService.REFUSAL_METADATA_KEY).put("reasonCode", "no_valid_observations");
        refused.setMetadata(metadata);
        assertThatCode(() -> handler.deliver(refused)).doesNotThrowAnyException();
        org.mockito.Mockito.verifyNoInteractions(feedbackService, observationRepository, deliveryService);
    }

    @Test
    void shouldRecoverTheSummaryForTheSameReviewJob() {
        AgentJob job = jobWithMetadata(sampleJobMetadata());
        ExistingDeliveryLookup found = ExistingDeliveryLookup.found("IC_existing");
        when(feedbackService.findExistingSummary(job)).thenReturn(found);

        assertThat(handler.findExistingDelivery(job)).isSameAs(found);
        verify(feedbackService).findExistingSummary(job);
    }

    private PullRequestReviewSubmissionRequest sampleRequest() {
        var pullRequestData = new ScmEventPayload.PullRequestData(
                456L,
                42,
                "Fix authentication bug",
                "This PR fixes the login issue",
                Issue.State.OPEN,
                false,
                false,
                10,
                5,
                3,
                "https://github.com/owner/repo/pull/42",
                new RepositoryRef(123L, "owner/repo", "main"),
                789L,
                Instant.now(),
                Instant.now(),
                null,
                null,
                null);
        return new PullRequestReviewSubmissionRequest(
                pullRequestData, "feature/auth-fix", "abc123def456", "main", "a".repeat(40));
    }

    private ObjectNode sampleJobMetadata() {
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("repository_id", 123L);
        metadata.put("repository_full_name", "owner/repo");
        metadata.put("pull_request_id", 456L);
        metadata.put("pr_number", 42);
        metadata.put("pr_url", "https://github.com/owner/repo/pull/42");
        metadata.put("commit_sha", "abc123def456");
        metadata.put("source_branch", "feature/auth-fix");
        metadata.put("target_branch", "main");
        return metadata;
    }

    private AgentJob jobWithMetadata(ObjectNode metadata) {
        var job = new AgentJob();
        job.setId(UUID.randomUUID());
        job.setMetadata(metadata);
        job.setEvidenceSnapshot(admittedPracticeSnapshot());
        Workspace workspace = new Workspace();
        workspace.setId(WORKSPACE_ID);
        job.setWorkspace(workspace);
        return job;
    }

    private static final String DIFF_SHA = "b".repeat(64);

    /** Every fixture practice admitted, over a captured diff whose secret scan found nothing. */
    private ObjectNode admittedPracticeSnapshot() {
        ObjectNode snapshot = EvidenceSnapshotFixtures.snapshot(objectMapper);
        EvidenceSnapshotFixtures.admittedPractice(snapshot, "pr-description-quality", 1)
                .put("defectDetector", false);
        EvidenceSnapshotFixtures.admittedPractice(snapshot, "error-handling", 2).put("defectDetector", false);
        EvidenceSnapshotFixtures.admittedPractice(snapshot, PullRequestReviewHandler.SECRET_PRACTICE, 3)
                .put("defectDetector", true);
        ObjectNode diff = EvidenceSnapshotFixtures.availableSource(
                snapshot, "scm.pull-request.diff", "a".repeat(40) + ":" + "b".repeat(40));
        EvidenceSnapshotFixtures.artifact(diff, "inputs/context/diff_paths.nul", "a".repeat(64));
        EvidenceSnapshotFixtures.artifact(diff, "inputs/context/diff.patch", DIFF_SHA);
        EvidenceSnapshotFixtures.secretScan(objectMapper, snapshot, DIFF_SHA, List.of());
        return snapshot;
    }

    private Practice createPractice(String slug, String name, String criteria) {
        Practice p = new Practice();
        p.setId((long) slug.hashCode());
        p.setSlug(slug);
        p.setName(name);
        p.setCriteria(criteria);
        p.setAutonomy(PracticeAutonomy.AUTOMATIC);
        p.setBindings(PracticeTestEvidence.bindings(ArtifactKinds.PULL_REQUEST));
        p.setAutomatedReviewPolicy(PracticeTestEvidence.forArtifact(ArtifactKinds.PULL_REQUEST));
        var revision = new PracticeRevision();
        ReflectionTestUtils.setField(revision, "id", Math.abs((long) slug.hashCode()) + 1);
        p.setCurrentRevision(revision);
        return p;
    }

    private List<Practice> samplePractices() {
        return List.of(
                createPractice("pr-description-quality", "PR Description Quality", "criteria"),
                createPractice("error-handling", "Error Handling", "fallback criteria"));
    }

    private void stubDefaults() {
        lenient()
                .when(workspaceContextBuilder.prepare(
                        any(ContextRequest.PracticeReviewRequest.class), any(EvidencePlan.class)))
                .thenReturn(prepared(Map.of("inputs/context/metadata.json", "{}".getBytes(StandardCharsets.UTF_8))));
        lenient()
                .when(workspaceContextBuilder.prepareAutomatedReviewReadiness(
                        any(), any(), anyString(), any(), any(), any()))
                .thenAnswer(invocation -> readiness(invocation.getArgument(1)));
        lenient()
                .when(practiceRepository.findByWorkspaceIdAndArtifactKind(WORKSPACE_ID, ArtifactKinds.PULL_REQUEST))
                .thenReturn(samplePractices());
    }

    private PreparedEvidence prepared(Map<String, byte[]> files) {
        return new PreparedEvidence(files, org.mockito.Mockito.mock(ArtifactSourceManifest.class));
    }

    /** Every practice asked is ready, recorded the way the readiness check records it. */
    private ContextManifestBuilder.PreparedAutomatedReviewReadiness readiness(List<Practice> practices) {
        Instant now = Instant.parse("2026-08-03T10:00:00Z");
        return new ContextManifestBuilder.PreparedAutomatedReviewReadiness(
                practices,
                new AutomatedReviewReadinessReport(
                        de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry.CURRENT_VERSION,
                        "0".repeat(64),
                        ArtifactKinds.PULL_REQUEST.value(),
                        now,
                        now,
                        practices.stream()
                                .map(practice ->
                                        new de.tum.cit.aet.hephaestus.evidence.AutomatedReviewReadinessDecision(
                                                practice.getSlug(),
                                                now,
                                                true,
                                                List.of(),
                                                List.of(new de.tum.cit.aet.hephaestus.evidence.SourceReadinessCheck(
                                                        de.tum.cit.aet.hephaestus.practices.PracticeSubjectClause
                                                                .DIFF_SOURCE,
                                                        de.tum.cit.aet.hephaestus.evidence.ArtifactSourceCatalogRegistry
                                                                .CURRENT_VERSION,
                                                        now,
                                                        now,
                                                        true,
                                                        List.of()))))
                                .toList()));
    }

    @Nested
    class JobType {

        @Test
        void returnsPullRequestReview() {
            assertThat(handler.jobType()).isEqualTo(AgentJobType.PULL_REQUEST_REVIEW);
        }
    }

    @Nested
    class CreateSubmission {

        @Test
        void extractsMetadata() {
            JobSubmission submission = handler.createSubmission(sampleRequest());
            JsonNode metadata = submission.metadata();

            assertThat(metadata.get("repository_id").asLong()).isEqualTo(123L);
            assertThat(metadata.get("repository_full_name").asString()).isEqualTo("owner/repo");
            assertThat(metadata.get("pr_number").asInt()).isEqualTo(42);
            assertThat(metadata.get("commit_sha").asString()).isEqualTo("abc123def456");
            assertThat(metadata.path("base_ref_oid").asString()).isEqualTo("a".repeat(40));
            assertThat(metadata.get("title").asString()).isEqualTo("Fix authentication bug");
            assertThat(metadata.get("body").asString()).isEqualTo("This PR fixes the login issue");
            assertThat(submission.idempotencyKey()).isEqualTo("pr_review:owner/repo:42:manual:abc123def456");
        }

        @Test
        void preservesSubmittedReviewSubjectAndIdentity() {
            var base = sampleRequest();
            var request = new PullRequestReviewSubmissionRequest(
                            base.pullRequest(),
                            base.headRefName(),
                            base.headRefOid(),
                            base.baseRefName(),
                            base.baseRefOid(),
                            de.tum.cit.aet.hephaestus.integration.scm.domain.signal.ScmSignals.PULL_REQUEST_REVIEWED)
                    .forSubmittedReview(new ScmEventPayload.ReviewData(
                            100L,
                            "Review body",
                            de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequestreview.PullRequestReview.State
                                    .COMMENTED,
                            false,
                            null,
                            200L,
                            456L,
                            null,
                            123L));

            JobSubmission submission = handler.createSubmission(request);

            assertThat(submission.metadata().path("review_id").asLong()).isEqualTo(100L);
            assertThat(submission.metadata().path("about_user_id").asLong()).isEqualTo(200L);
            assertThat(submission.metadata().path("subject_role").asString()).isEqualTo("REVIEWER");
            assertThat(submission.idempotencyKey()).endsWith(":review-100");
        }

        @Test
        void rejectsWrongRequestType() {
            JobSubmissionRequest wrongType = new JobSubmissionRequest() {};
            assertThatThrownBy(() -> handler.createSubmission(wrongType))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Expected PullRequestReviewSubmissionRequest");
        }
    }

    @Nested
    class PrepareInputs {

        @Test
        void delegatesToWorkspaceContextBuilder() {
            stubDefaults();
            AgentJob job = jobWithMetadata(sampleJobMetadata());

            handler.prepareInputs(job);

            ArgumentCaptor<ContextRequest> captor = ArgumentCaptor.forClass(ContextRequest.class);
            verify(workspaceContextBuilder).prepare(captor.capture(), any(EvidencePlan.class));
            assertThat(captor.getValue()).isInstanceOf(ContextRequest.PracticeReviewRequest.class);
            assertThat(((ContextRequest.PracticeReviewRequest) captor.getValue()).job())
                    .isSameAs(job);
        }

        /**
         * The composer only runs when this file is staged, and the runner is the only thing that reads it.
         * Asserted over what preparation actually produces, because a test that calls the staging helper
         * directly passes whether or not a review ever calls it.
         */
        @Test
        void stagesTheRequestThatTurnsFeedbackCompositionOn() {
            stubDefaults();

            Map<String, byte[]> files =
                    handler.prepareInputs(jobWithMetadata(sampleJobMetadata())).files();

            assertThat(files)
                    .as("a live review composes feedback, so the request must reach the sandbox")
                    .containsKey(SandboxLayout.FEEDBACK_COMPOSITION_PATH);
            JsonNode request = objectMapper.readTree(
                    new String(files.get(SandboxLayout.FEEDBACK_COMPOSITION_PATH), StandardCharsets.UTF_8));
            assertThat(request.path("enabled").asBoolean()).isTrue();
            assertThat(request.path("inContextPlacementKinds"))
                    .as("a pull request has a diff, so a note may be placed on one")
                    .anySatisfy(kind -> assertThat(kind.asString()).isEqualTo("DIFF"));
        }

        @Test
        void mergesProviderFiles() {
            byte[] metadataBytes = "{\"pr_number\":42}".getBytes(StandardCharsets.UTF_8);
            when(workspaceContextBuilder.prepare(
                            any(ContextRequest.PracticeReviewRequest.class), any(EvidencePlan.class)))
                    .thenReturn(prepared(Map.of("inputs/context/metadata.json", metadataBytes)));
            when(workspaceContextBuilder.prepareAutomatedReviewReadiness(
                            any(), any(), anyString(), any(), any(), any()))
                    .thenAnswer(invocation -> readiness(invocation.getArgument(1)));
            when(practiceRepository.findByWorkspaceIdAndArtifactKind(WORKSPACE_ID, ArtifactKinds.PULL_REQUEST))
                    .thenReturn(samplePractices());

            Map<String, byte[]> files =
                    handler.prepareInputs(jobWithMetadata(sampleJobMetadata())).files();

            assertThat(files.get("inputs/context/metadata.json")).isEqualTo(metadataBytes);
        }

        @Test
        void writesTaskJsonEnvelope() throws Exception {
            stubDefaults();
            Map<String, byte[]> files =
                    handler.prepareInputs(jobWithMetadata(sampleJobMetadata())).files();

            assertThat(files).containsKey("task.json");
            JsonNode envelope = objectMapper.readTree(files.get("task.json"));
            assertThat(envelope.get("schemaVersion").asInt()).isEqualTo(2);
            assertThat(envelope.get("workspaceId").asLong()).isEqualTo(WORKSPACE_ID);
            JsonNode task = envelope.get("task");
            assertThat(task.get("kind").asString()).isEqualTo("practice_review");
            assertThat(task.get("pullRequestNumber").asInt()).isEqualTo(42);
            assertThat(task.get("repositoryFullName").asString()).isEqualTo("owner/repo");
            assertThat(task.get("prompt").asString()).contains("Review merge request #42");
        }

        @Test
        void injectsPracticeCatalog() {
            stubDefaults();
            Map<String, byte[]> files =
                    handler.prepareInputs(jobWithMetadata(sampleJobMetadata())).files();

            assertThat(files).containsKey("inputs/practices/index.json");
            assertThat(files).containsKey("inputs/practices/all-criteria.md");
            assertThat(files).containsKey("inputs/practices/pr-description-quality.md");
            assertThat(files).containsKey("inputs/practices/error-handling.md");
            assertThat(files).containsKey("work/analysis/practices/.gitkeep");
        }

        @Test
        void doesNotWriteLegacyPromptFile() {
            stubDefaults();
            Map<String, byte[]> files =
                    handler.prepareInputs(jobWithMetadata(sampleJobMetadata())).files();
            assertThat(files).doesNotContainKey(".prompt");
        }

        @Test
        void rejectsMalformedSlug() {
            when(practiceRepository.findByWorkspaceIdAndArtifactKind(WORKSPACE_ID, ArtifactKinds.PULL_REQUEST))
                    .thenReturn(List.of(createPractice("../etc/passwd", "bad", "c")));

            assertThatThrownBy(() -> handler.prepareInputs(jobWithMetadata(sampleJobMetadata())))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessageContaining("Practice slug fails ABI pattern");
        }

        @Test
        void shouldScanTheCapturedChangeOnlyWhenTheSecretPracticeIsReady() {
            stubDefaults();
            AgentJob job = jobWithMetadata(sampleJobMetadata());

            assertThat(handler.prepareInputs(job).secretScan()).isNull();
            verifyNoInteractions(secretScanner);

            Practice secrets = createPractice(PullRequestReviewHandler.SECRET_PRACTICE, "Secrets", "criteria");
            when(practiceRepository.findByWorkspaceIdAndArtifactKind(WORKSPACE_ID, ArtifactKinds.PULL_REQUEST))
                    .thenReturn(List.of(secrets));
            var scan = new SecretScan("inputs/context/diff.patch", "b".repeat(64), List.of());
            when(secretScanner.scan(eq(job.getId()), any())).thenReturn(scan);

            assertThat(handler.prepareInputs(job).secretScan()).isSameAs(scan);
        }

        @Test
        void shouldReleaseTheCapturedEvidenceWhenTheSecretScanFails() {
            stubDefaults();
            var released = new java.util.concurrent.atomic.AtomicBoolean();
            when(workspaceContextBuilder.prepare(
                            any(ContextRequest.PracticeReviewRequest.class), any(EvidencePlan.class)))
                    .thenReturn(new PreparedEvidence(
                            Map.of("inputs/context/metadata.json", "{}".getBytes(StandardCharsets.UTF_8)),
                            Map.of(),
                            List.of(() -> released.set(true)),
                            org.mockito.Mockito.mock(ArtifactSourceManifest.class)));
            when(practiceRepository.findByWorkspaceIdAndArtifactKind(WORKSPACE_ID, ArtifactKinds.PULL_REQUEST))
                    .thenReturn(List.of(createPractice(PullRequestReviewHandler.SECRET_PRACTICE, "Secrets", "c")));
            AgentJob job = jobWithMetadata(sampleJobMetadata());
            when(secretScanner.scan(eq(job.getId()), any())).thenThrow(new JobPreparationException("scan failed"));

            assertThatThrownBy(() -> handler.prepareInputs(job))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessage("scan failed");
            assertThat(released).isTrue();
        }

        @Test
        void throwsWhenNoActivePractices() {
            when(practiceRepository.findByWorkspaceIdAndArtifactKind(WORKSPACE_ID, ArtifactKinds.PULL_REQUEST))
                    .thenReturn(List.of());

            assertThatThrownBy(() -> handler.prepareInputs(jobWithMetadata(sampleJobMetadata())))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessageContaining("No active scm.pull_request practices");
            verifyNoInteractions(workspaceContextBuilder);
        }

        @Test
        void throwsWhenMetadataMissing() {
            var job = new AgentJob();
            org.springframework.test.util.ReflectionTestUtils.setField(job, "metadata", null);
            assertThatThrownBy(() -> handler.prepareInputs(job))
                    .isInstanceOf(JobPreparationException.class)
                    .hasMessageContaining("no metadata");
        }

        @Test
        void preservesProviderOrder() {
            var providerFiles = new LinkedHashMap<String, byte[]>();
            providerFiles.put("inputs/context/metadata.json", "{}".getBytes(StandardCharsets.UTF_8));
            providerFiles.put("inputs/context/diff.patch", "diff".getBytes(StandardCharsets.UTF_8));
            providerFiles.put("inputs/context/comments.json", "[]".getBytes(StandardCharsets.UTF_8));
            when(workspaceContextBuilder.prepare(any(), any())).thenReturn(prepared(providerFiles));
            when(workspaceContextBuilder.prepareAutomatedReviewReadiness(
                            any(), any(), anyString(), any(), any(), any()))
                    .thenAnswer(invocation -> readiness(invocation.getArgument(1)));
            when(practiceRepository.findByWorkspaceIdAndArtifactKind(WORKSPACE_ID, ArtifactKinds.PULL_REQUEST))
                    .thenReturn(samplePractices());

            Map<String, byte[]> files =
                    handler.prepareInputs(jobWithMetadata(sampleJobMetadata())).files();
            var keys = files.keySet().iterator();
            assertThat(keys.next()).isEqualTo("inputs/context/metadata.json");
            assertThat(keys.next()).isEqualTo("inputs/context/diff.patch");
            assertThat(keys.next()).isEqualTo("inputs/context/comments.json");
        }
    }

    @Nested
    class ParseDiffNameOnlyPaths {

        @Test
        void simplePaths() {
            String output = "src/Main.swift\nViews/ContentView.swift\nREADME.md\n";
            assertThat(PullRequestReviewHandler.parseDiffNameOnlyPaths(output))
                    .containsExactlyInAnyOrder("src/Main.swift", "Views/ContentView.swift", "README.md");
        }

        @Test
        void blankInput() {
            assertThat(PullRequestReviewHandler.parseDiffNameOnlyPaths("")).isEmpty();
            assertThat(PullRequestReviewHandler.parseDiffNameOnlyPaths("  \n  "))
                    .isEmpty();
        }
    }

    @Nested
    class Deliver {

        private AgentJob jobWithOutput(String rawOutputJson) {
            var job = new AgentJob();
            job.setId(UUID.randomUUID());
            job.setEvidenceSnapshot(admittedPracticeSnapshot());
            ObjectNode output = objectMapper.createObjectNode();
            output.put("rawOutput", rawOutputJson);
            job.setOutput(output);
            return job;
        }

        private void admit(AgentJob job, String rawOutputJson) {
            handler.prepareObservations(
                            job, objectMapper.readTree(rawOutputJson).path("observations"))
                    .record(job);
        }

        private de.tum.cit.aet.hephaestus.practices.model.Observation persisted(
                AgentJob job,
                Practice practice,
                String summary,
                de.tum.cit.aet.hephaestus.practices.model.Severity severity) {
            var observation = org.mockito.Mockito.mock(de.tum.cit.aet.hephaestus.practices.model.Observation.class);
            lenient().when(observation.getPractice()).thenReturn(practice);
            lenient()
                    .when(observation.getEvidence())
                    .thenReturn(AdmittedObservationFixtures.evidence(job.getId(), "scm.pull-request.core"));
            lenient().when(observation.getAssessmentStatus()).thenReturn(AssessmentStatus.ASSESSED);
            lenient().when(observation.getSummary()).thenReturn(summary);
            lenient()
                    .when(observation.getPresence())
                    .thenReturn(de.tum.cit.aet.hephaestus.practices.model.Presence.ABSENT);
            lenient()
                    .when(observation.getAssessment())
                    .thenReturn(de.tum.cit.aet.hephaestus.practices.model.Assessment.GOOD);
            lenient().when(observation.getSeverity()).thenReturn(severity);
            lenient().when(observation.getEvidenceRationale()).thenReturn("Reasoning for " + practice.getSlug() + ".");
            lenient().when(observation.getOccurrenceKey()).thenReturn("occ-" + practice.getSlug());
            lenient().when(observation.getRecurrenceKey()).thenReturn("rk-" + practice.getSlug());
            return observation;
        }

        @Test
        void shouldRefuseInconsistentPinnedAssessmentBeforePersistingObservations() {
            String rawOutput = """
                {"observations": [{
                  "practiceSlug": "avoids-insecure-defaults-and-over-broad-permissions",
                  "summary": "The harmful behaviour is good",
                  "assessmentStatus": "ASSESSED", "presence": "PRESENT",
                  "assessment": "GOOD", "severity": null,
                  "evidenceRationale": "Original evidence rationale",
                  "evidence": {}
                }]}
                """;
            AgentJob job = jobWithOutput(rawOutput);

            assertThatThrownBy(() -> admit(job, rawOutput))
                    .isInstanceOfSatisfying(
                            ObservationsRefusedException.class,
                            e -> assertThat(e.reasonCode()).isEqualTo("incoherent_assessment"));
            verifyNoInteractions(deliveryService, feedbackService, observationRepository);
        }

        @Test
        void shouldHoldAutomaticFeedbackInsideTheReviewPackageWhenAnyPracticeNeedsApproval() {
            String lead = "The retry path is covered now, but the description never says why it changed.";
            ObjectNode metadata = sampleJobMetadata();
            metadata.put(ObservationAdmissionService.DIGEST_METADATA_KEY, "digest-1");
            AgentJob job = jobWithMetadata(metadata);
            ObjectNode output = objectMapper.createObjectNode();
            ObjectNode feedback = output.putObject("feedback");
            feedback.put("admissionDigest", "digest-1");
            feedback.put("lead", lead);
            job.setOutput(output);

            Practice approvalGated = createPractice("error-handling", "Error Handling", "criteria");
            approvalGated.setAutonomy(de.tum.cit.aet.hephaestus.practices.model.PracticeAutonomy.HUMAN_APPROVAL);
            Practice automatic = createPractice("describe-what-and-why", "Describe What And Why", "criteria");
            when(practiceRepository.findByWorkspaceId(WORKSPACE_ID))
                    .thenReturn(java.util.List.of(approvalGated, automatic));
            // Built before the stubbing call: persisted() stubs, and Mockito rejects a stub nested in when().
            var gated = persisted(
                    job,
                    approvalGated,
                    "Unhandled error path",
                    de.tum.cit.aet.hephaestus.practices.model.Severity.MAJOR);
            var auto = persisted(
                    job, automatic, "No rationale sentence", de.tum.cit.aet.hephaestus.practices.model.Severity.MINOR);
            when(observationRepository.findByAgentJobId(
                            job.getId(), job.getWorkspace().getId()))
                    .thenReturn(java.util.List.of(gated, auto));

            handler.deliver(job);

            var proposal = org.mockito.ArgumentCaptor.forClass(PracticeDetectionResultParser.DeliveryContent.class);
            verify(feedbackService).recordProposal(org.mockito.ArgumentMatchers.eq(job), proposal.capture(), any());
            verify(feedbackService, never()).deliverFeedback(any(), any(), any());

            assertThat(proposal.getValue().mrNote()).startsWith(lead);
        }

        /** A job past admission, carrying the coverage ledger its run wrote. */
        private AgentJob jobAwaitingDelivery(int eligible, int evaluated) {
            ObjectNode metadata = sampleJobMetadata();
            metadata.put(ObservationAdmissionService.DIGEST_METADATA_KEY, "digest-1");
            AgentJob job = jobWithMetadata(metadata);
            ObjectNode output = objectMapper.createObjectNode();
            output.putObject("feedback").put("admissionDigest", "digest-1");
            ObjectNode coverage = output.putObject("practiceCoverage");
            coverage.put("eligible", eligible);
            coverage.put("evaluated", evaluated);
            job.setOutput(output);
            return job;
        }

        private void observed(AgentJob job, Practice practice, Assessment assessment) {
            when(practiceRepository.findByWorkspaceId(WORKSPACE_ID)).thenReturn(java.util.List.of(practice));
            var observation = org.mockito.Mockito.mock(de.tum.cit.aet.hephaestus.practices.model.Observation.class);
            lenient().when(observation.getPractice()).thenReturn(practice);
            lenient()
                    .when(observation.getEvidence())
                    .thenReturn(AdmittedObservationFixtures.evidence(job.getId(), "scm.pull-request.core"));
            lenient().when(observation.getAssessmentStatus()).thenReturn(AssessmentStatus.ASSESSED);
            lenient().when(observation.getSummary()).thenReturn("What the review saw");
            lenient()
                    .when(observation.getPresence())
                    .thenReturn(assessment == Assessment.BAD ? Presence.ABSENT : Presence.PRESENT);
            lenient().when(observation.getAssessment()).thenReturn(Assessment.GOOD);
            lenient().when(observation.getSeverity()).thenReturn(assessment == Assessment.BAD ? Severity.MAJOR : null);
            lenient().when(observation.getEvidenceRationale()).thenReturn("The evidence warrants it.");
            lenient().when(observation.getOccurrenceKey()).thenReturn("occ-" + practice.getSlug());
            lenient().when(observation.getRecurrenceKey()).thenReturn("rk-" + practice.getSlug());
            when(observationRepository.findByAgentJobId(
                            job.getId(), job.getWorkspace().getId()))
                    .thenReturn(java.util.List.of(observation));
        }

        @Test
        void shouldWithholdAnAllClearWhenTheReviewDidNotReachEveryPractice() {
            AgentJob job = jobAwaitingDelivery(2, 1);
            observed(
                    job,
                    createPractice("pr-description-quality", "PR Description Quality", "criteria"),
                    Assessment.GOOD);

            handler.deliver(job);

            verify(feedbackService, never()).deliverFeedback(any(), any(), any());
            verify(feedbackService, never()).recordProposal(any(), any(), any());
        }

        @Test
        void shouldStillReportWhatAPartialReviewFound() {
            AgentJob job = jobAwaitingDelivery(2, 1);
            observed(job, createPractice("error-handling", "Error Handling", "criteria"), Assessment.BAD);

            handler.deliver(job);

            verify(feedbackService).deliverFeedback(eq(job), any(), any());
        }

        @Test
        void shouldPostAnAllClearWhenTheReviewReachedEveryPractice() {
            AgentJob job = jobAwaitingDelivery(2, 2);
            observed(
                    job,
                    createPractice("pr-description-quality", "PR Description Quality", "criteria"),
                    Assessment.GOOD);

            handler.deliver(job);

            verify(feedbackService).deliverFeedback(eq(job), any(), any());
        }

        @Test
        void throwsWhenNoValidObservations() {
            AgentJob job = jobWithOutput("{\"observations\":[]}");
            assertThatThrownBy(() -> admit(job, "{\"observations\":[]}"))
                    .isInstanceOfSatisfying(
                            ObservationsRefusedException.class,
                            e -> assertThat(e.reasonCode()).isEqualTo("no_valid_observations"))
                    .hasMessageContaining("No valid observations");
        }

        @Test
        @SuppressWarnings("unchecked")
        void hardcodedSecretUsesPracticeSeverityCap() {
            String rawOutput = """
                {
                  "observations": [{
                    "practiceSlug": "avoids-insecure-defaults-and-over-broad-permissions",
                    "summary": "Hard-coded credential",
                    "assessmentStatus": "ASSESSED", "presence": "PRESENT",
                    "assessment": "BAD",
                    "severity": "CRITICAL",
                    "evidenceRationale": "A live API key is committed.",
                    "evidence": { "citations": [{ "path": "Sources/Config.swift", "startLine": 3 }] }
                  }]
                }
                """;
            AgentJob job = jobWithOutput(rawOutput);
            ArgumentCaptor<List<PracticeDetectionResultParser.ValidatedObservation>> captor =
                    ArgumentCaptor.forClass(List.class);
            when(deliveryService.prepare(eq(job), captor.capture()))
                    .thenReturn(org.mockito.Mockito.mock(PreparedObservations.class));

            admit(job, rawOutput);

            List<PracticeDetectionResultParser.ValidatedObservation> delivered = captor.getValue();
            var secret = delivered.stream()
                    .filter(f -> "avoids-insecure-defaults-and-over-broad-permissions".equals(f.practiceSlug()))
                    .findFirst()
                    .orElseThrow();
            assertThat(secret.severity()).isEqualTo(Severity.MAJOR);
        }

        private void stubDiff(String path) {
            capturedPaths = path + "\0";
        }

        private static final String PRESENT_OBSERVATION = """
            {
              "observations": [{
                "practiceSlug": "pr-description-quality",
                "summary": "Good PR description",
                "assessmentStatus": "ASSESSED", "presence": "PRESENT",
                "assessment": "GOOD",
                "severity": null,
                "evidenceRationale": "The description states the purpose.",
                "evidence": {}
              }]
            }
            """;

        @Test
        void shouldRefuseACapturedDiffListingWithAnEmptyPath() {
            AgentJob job = jobWithOutput(PRESENT_OBSERVATION);
            capturedPaths = "Sources/Auth.swift\0\0";

            assertThatThrownBy(() -> admit(job, PRESENT_OBSERVATION))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessage("Captured diff contains an empty path");
            verifyNoInteractions(deliveryService);
        }

        @Test
        void shouldRefuseACapturedDiffListingThatIsNotNulTerminated() {
            AgentJob job = jobWithOutput(PRESENT_OBSERVATION);
            capturedPaths = "Sources/Auth.swift";

            assertThatThrownBy(() -> admit(job, PRESENT_OBSERVATION))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessage("Captured diff path is not NUL terminated");
            verifyNoInteractions(deliveryService);
        }

        @Test
        void shouldRefuseACapturedDiffPathOneByteOverTheBound() {
            AgentJob job = jobWithOutput(PRESENT_OBSERVATION);
            stubDiff("a".repeat(32_769));

            assertThatThrownBy(() -> admit(job, PRESENT_OBSERVATION))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessage("Captured diff path exceeds the filesystem path resource bound");
            verifyNoInteractions(deliveryService);
        }

        @Test
        void shouldAcceptACapturedDiffPathExactlyAtTheBound() {
            AgentJob job = jobWithOutput(PRESENT_OBSERVATION);
            stubDiff("a".repeat(32_768));

            assertThatCode(() -> admit(job, PRESENT_OBSERVATION)).doesNotThrowAnyException();
        }

        @Test
        void throwsWhenAllNotApplicableButDiffHasFiles() {
            String rawOutput = """
                {
                  "observations": [{
                    "practiceSlug": "pr-description-quality",
                    "summary": "Not applicable here",
                    "assessmentStatus": "NOT_APPLICABLE", "presence": null, "assessment": null, "severity": null,
                    "evidenceRationale": "The practice has no subject in this change.",
                    "evidence": { "citations": [], "inapplicability": { "reason": "No relevant subject exists." } }
                  }]
                }
                """;
            AgentJob job = jobWithMetadata(sampleJobMetadata());
            ObjectNode output = objectMapper.createObjectNode();
            output.put("rawOutput", rawOutput);
            job.setOutput(output);
            stubDiff("Sources/Auth.swift");

            assertThatThrownBy(() -> admit(job, rawOutput))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("answered without reading the change");
            verifyNoInteractions(deliveryService);
        }

        @Test
        @SuppressWarnings("unchecked")
        void admitsWhenNothingDecidedButAnObservationQuotesTheDiff() {
            String rawOutput = """
                {
                  "observations": [{
                    "practiceSlug": "pr-description-quality",
                    "summary": "Not applicable here",
                    "assessmentStatus": "NOT_APPLICABLE", "presence": null, "assessment": null, "severity": null,
                    "evidenceRationale": "The practice has no subject in this change.",
                    "evidence": {
                      "citations": [{
                        "sourceKind": "scm.pull-request.diff",
                        "artifactPath": "inputs/context/diff.patch",
                        "path": "Sources/Auth.swift",
                        "side": "NEW",
                        "startLine": 1,
                        "endLine": 1,
                        "quote": "+changed"
                      }],
                      "inapplicability": { "reason": "No relevant subject exists." }
                    }
                  }]
                }
                """;
            AgentJob job = jobWithMetadata(sampleJobMetadata());
            ObjectNode output = objectMapper.createObjectNode();
            output.put("rawOutput", rawOutput);
            job.setOutput(output);
            stubDiff("Sources/Auth.swift");
            ArgumentCaptor<List<PracticeDetectionResultParser.ValidatedObservation>> captor =
                    ArgumentCaptor.forClass(List.class);
            when(deliveryService.prepare(eq(job), captor.capture()))
                    .thenReturn(org.mockito.Mockito.mock(PreparedObservations.class));

            admit(job, rawOutput);

            assertThat(captor.getValue()).singleElement().satisfies(observation -> {
                assertThat(observation.practiceSlug()).isEqualTo("pr-description-quality");
                assertThat(observation.assessmentStatus()).isEqualTo(AssessmentStatus.NOT_APPLICABLE);
            });
        }

        @Test
        void throwsWhenAllFindingsFilteredByDiffScope() {
            String rawOutput = """
                {
                  "observations": [{
                    "practiceSlug": "error-handling",
                    "summary": "Unhandled error path",
                    "assessmentStatus": "ASSESSED", "presence": "ABSENT",
                    "assessment": "GOOD",
                    "severity": "MAJOR",
                    "evidenceRationale": "The error branch is swallowed.",
                    "evidence": { "citations": [{ "path": "Sources/NotInDiff.swift", "startLine": 3 }] }
                  }]
                }
                """;
            AgentJob job = jobWithMetadata(sampleJobMetadata());
            ObjectNode output = objectMapper.createObjectNode();
            output.put("rawOutput", rawOutput);
            job.setOutput(output);
            stubDiff("Sources/Other.swift");

            assertThatThrownBy(() -> admit(job, rawOutput))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("filtered by diff scope");
            verifyNoInteractions(deliveryService);
        }

        @Test
        @SuppressWarnings("unchecked")
        void injectsSecretFindingWhenModelAbstainsButDiffCommitsCredential() {
            String rawOutput = """
                {
                  "observations": [{
                    "practiceSlug": "pr-description-quality",
                    "summary": "Clear description",
                    "assessmentStatus": "ASSESSED", "presence": "PRESENT",
                    "assessment": "GOOD",
                    "severity": null,
                    "evidenceRationale": "The description states the purpose.",
                    "evidence": {}
                  }]
                }
                """;
            AgentJob job = jobWithMetadata(sampleJobMetadata());
            ObjectNode output = objectMapper.createObjectNode();
            output.put("rawOutput", rawOutput);
            job.setOutput(output);
            stubDiff("Sources/Config.swift");
            EvidenceSnapshotFixtures.secretScan(
                    objectMapper,
                    (ObjectNode) java.util.Objects.requireNonNull(job.getEvidenceSnapshot()),
                    DIFF_SHA,
                    List.of(new SecretScan.Hit(
                            "Sources/Config.swift",
                            1,
                            "b2b88104bf5c02259227480b0eabe2f9b7d63501e03e788b7b82a499b818e12a",
                            "aws-access-token")));
            ArgumentCaptor<List<PracticeDetectionResultParser.ValidatedObservation>> captor =
                    ArgumentCaptor.forClass(List.class);
            when(deliveryService.prepare(eq(job), captor.capture()))
                    .thenReturn(org.mockito.Mockito.mock(PreparedObservations.class));

            admit(job, rawOutput);

            List<PracticeDetectionResultParser.ValidatedObservation> delivered = captor.getValue();
            var secret = delivered.stream()
                    .filter(f -> "avoids-insecure-defaults-and-over-broad-permissions".equals(f.practiceSlug()))
                    .findFirst()
                    .orElseThrow();
            assertThat(secret.presence()).isEqualTo(Presence.PRESENT);
            assertThat(secret.assessment()).isEqualTo(Assessment.BAD);
            JsonNode evidence = secret.evidence();
            org.junit.jupiter.api.Assertions.assertNotNull(evidence);
            assertThat(evidence.path("detector").asString()).isEqualTo("secret-diff-scanner");
            JsonNode citation = evidence.path("citations").get(0);
            assertThat(citation.has("quote")).isFalse();
            assertThat(citation.path("quoteSha256").asString())
                    .isEqualTo("b2b88104bf5c02259227480b0eabe2f9b7d63501e03e788b7b82a499b818e12a");
        }

        @Test
        void shouldRefuseToAdmitWhenTheRecordedSecretVerdictsAreNotThoseOfTheCapturedDiff() {
            AgentJob job = jobWithMetadata(sampleJobMetadata());
            stubDiff("Sources/Config.swift");
            EvidenceSnapshotFixtures.secretScan(
                    objectMapper,
                    (ObjectNode) java.util.Objects.requireNonNull(job.getEvidenceSnapshot()),
                    "f".repeat(64),
                    List.of());

            assertThatThrownBy(() -> admit(job, PRESENT_OBSERVATION))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("not those of the captured diff");
            verifyNoInteractions(deliveryService);
        }

        @Test
        void shouldRefuseToAdmitWhenTheSecretPracticeWasAskedButNoVerdictsWereRecorded() {
            AgentJob job = jobWithMetadata(sampleJobMetadata());
            stubDiff("Sources/Config.swift");
            ((ObjectNode) java.util.Objects.requireNonNull(job.getEvidenceSnapshot())).remove(SecretScan.SNAPSHOT_NODE);

            assertThatThrownBy(() -> admit(job, PRESENT_OBSERVATION))
                    .isInstanceOf(JobDeliveryException.class)
                    .hasMessageContaining("missing");
            verifyNoInteractions(deliveryService);
        }
    }
}
