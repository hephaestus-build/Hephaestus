package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.context.WorkspaceContextBuilder;
import de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionInputs;
import de.tum.cit.aet.hephaestus.agent.handler.spi.JobSubmission;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.runtime.SandboxLayout;
import de.tum.cit.aet.hephaestus.agent.task.TaskEnvelopeWriter;
import de.tum.cit.aet.hephaestus.core.auth.spi.AccountPreferencesQuery;
import de.tum.cit.aet.hephaestus.integration.scm.domain.issue.IssueRepository;
import de.tum.cit.aet.hephaestus.integration.scm.domain.pullrequest.PullRequestRepository;
import de.tum.cit.aet.hephaestus.practices.PracticeRepository;
import de.tum.cit.aet.hephaestus.practices.feedback.FeedbackChannel;
import de.tum.cit.aet.hephaestus.practices.model.ArtifactKinds;
import de.tum.cit.aet.hephaestus.practices.model.ObservationOrigin;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.RepositoryToMonitorRepository;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import de.tum.cit.aet.hephaestus.workspace.WorkspaceRepository;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class IssueReviewHandlerTest extends BaseUnitTest {

    private final JsonMapper objectMapper = JsonMapper.builder().build();

    @Mock
    private WorkspaceContextBuilder workspaceContextBuilder;

    @Mock
    private PracticeRepository practiceRepository;

    @Mock
    private PracticeDetectionDeliveryService deliveryService;

    @Mock
    private PullRequestCommentPoster commentPoster;

    @Mock
    private FeedbackLedgerRecorder feedbackLedgerRecorder;

    @Mock
    private IssueRepository issueRepository;

    @Mock
    private RepositoryToMonitorRepository repositoryToMonitorRepository;

    @Mock
    private AccountPreferencesQuery accountPreferencesQuery;

    @Mock
    private PullRequestRepository pullRequestRepository;

    @Mock
    private WorkspaceRepository workspaceRepository;

    @Mock
    private FeedbackResponseSuppressionFilter feedbackResponseSuppressionFilter;

    @Mock
    private PracticeFeedbackDispatchService dispatchService;

    private IssueReviewHandler handler;

    private boolean silentModeEngaged;

    @BeforeEach
    void setUp() {
        silentModeEngaged = false;
        var practiceCatalogInjector = new PracticeCatalogInjector(
                objectMapper, practiceRepository, InContextDeliveryGateFixtures.workspaceDefaults());
        handler = new IssueReviewHandler(
                objectMapper,
                new PracticeReviewPreparation(
                        workspaceContextBuilder, practiceCatalogInjector, new TaskEnvelopeWriter(objectMapper)),
                practiceCatalogInjector,
                new PracticeDetectionResultParser(objectMapper),
                new de.tum.cit.aet.hephaestus.agent.handler.composition.FeedbackCompositionResultParser(),
                deliveryService,
                InContextDeliveryGateFixtures.gate(
                        practiceRepository,
                        org.mockito.Mockito.mock(
                                de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.class),
                        feedbackLedgerRecorder),
                commentPoster,
                feedbackLedgerRecorder,
                mock(PracticeFeedbackDeliveryPolicy.class),
                mock(PracticeFeedbackCommentFormatter.class),
                feedbackResponseSuppressionFilter,
                mock(de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository.class),
                dispatchService,
                mock(FeedbackDeliveryService.class));
        lenient()
                .when(feedbackResponseSuppressionFilter.evaluate(any(), any()))
                .thenAnswer(invocation ->
                        new FeedbackResponseSuppressionFilter.SuppressionDecision(invocation.getArgument(1), 0));
        lenient()
                .when(repositoryToMonitorRepository.existsByWorkspaceIdAndNameWithOwner(1L, "owner/repo"))
                .thenReturn(true);
        lenient().when(workspaceRepository.findById(1L)).thenReturn(Optional.of(activePracticeWorkspace()));
    }

    private Workspace activePracticeWorkspace() {
        var workspace = new Workspace();
        workspace.setId(1L);
        workspace.getFeatures().setPracticesEnabled(true);
        return workspace;
    }

    private IssueReviewSubmissionRequest sampleRequest() {
        return new IssueReviewSubmissionRequest(
                777L,
                12,
                123L,
                "owner/repo",
                "Add dark mode",
                "Users want a dark theme toggle in settings.",
                "OPEN",
                "https://github.com/owner/repo/issues/12",
                java.time.Instant.ofEpochMilli(1_700_000_000_000L),
                null);
    }

    @Nested
    class JobType {

        @Test
        void shouldFinishWithoutComposingFeedbackWhenObservationsWereRefused() {
            var refused = new de.tum.cit.aet.hephaestus.agent.job.AgentJob();
            var metadata = objectMapper.createObjectNode();
            metadata.putObject(ObservationAdmissionService.REFUSAL_METADATA_KEY)
                    .put("reasonCode", "no_valid_observations");
            refused.setMetadata(metadata);
            org.assertj.core.api.Assertions.assertThatCode(() -> handler.deliver(refused))
                    .doesNotThrowAnyException();
            org.mockito.Mockito.verifyNoInteractions(dispatchService, feedbackLedgerRecorder, deliveryService);
        }

        @Test
        void returnsIssueReview() {
            assertThat(handler.jobType()).isEqualTo(AgentJobType.ISSUE_REVIEW);
        }
    }

    @Nested
    class ComposableLanes {

        @Test
        void anIssueComposesInContextAtArtifactLevelWithoutInventingADiff() {
            assertThat(ArtifactKinds.hasInlineLane(ArtifactKinds.ISSUE)).isFalse();
            assertThat(IssueReviewHandler.ISSUE_REVIEW_CHANNELS).containsExactlyInAnyOrder(FeedbackChannel.values());
        }

        @Test
        void theStagedRequestAllowsOnlyArtifactPlacement() {
            Map<String, byte[]> files = new LinkedHashMap<>();
            FeedbackCompositionInputs.stage(
                    files,
                    ObservationOrigin.LIVE,
                    IssueReviewHandler.ISSUE_REVIEW_CHANNELS,
                    EnumSet.of(FeedbackCompositionInputs.InContextPlacementKind.ARTIFACT));

            JsonNode request = objectMapper.readTree(
                    new String(files.get(SandboxLayout.FEEDBACK_COMPOSITION_PATH), StandardCharsets.UTF_8));
            assertThat(request.get("channels")
                            .get(FeedbackChannel.IN_CONTEXT.name())
                            .get("enabled")
                            .asBoolean())
                    .isTrue();
            assertThat(request.get("inContextPlacementKinds"))
                    .singleElement()
                    .extracting(JsonNode::asString)
                    .isEqualTo("ARTIFACT");
        }
    }

    @Nested
    class CreateSubmission {

        @Test
        void buildsIssueMetadata() {
            JobSubmission submission = handler.createSubmission(sampleRequest());
            JsonNode metadata = submission.metadata();

            assertThat(metadata.get("artifact_kind").asString()).isEqualTo("scm.issue");
            assertThat(metadata.get("repository_id").asLong()).isEqualTo(123L);
            assertThat(metadata.get("repository_full_name").asString()).isEqualTo("owner/repo");
            assertThat(metadata.get("issue_id").asLong()).isEqualTo(777L);
            assertThat(metadata.get("issue_number").asInt()).isEqualTo(12);
            assertThat(metadata.get("title").asString()).isEqualTo("Add dark mode");
            assertThat(metadata.get("state").asString()).isEqualTo("OPEN");
            assertThat(metadata.get("issue_url").asString()).isEqualTo("https://github.com/owner/repo/issues/12");
        }

        @Test
        void idempotencyKeyHasDisposableFreshnessSegment() {
            JobSubmission submission = handler.createSubmission(sampleRequest());
            assertThat(submission.idempotencyKey()).isEqualTo("issue_review:owner/repo:12:manual:1700000000000");
        }

        @Test
        void rejectsWrongRequestType() {
            assertThatThrownBy(() -> handler.createSubmission(new WrongRequest()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Expected IssueReviewSubmissionRequest");
        }
    }

    private record WrongRequest() implements de.tum.cit.aet.hephaestus.agent.handler.spi.JobSubmissionRequest {}

    /** Resolves every workspace to the unset defaults — HUMAN_APPROVAL autonomy, reach on the work. */
    @Nested
    class PrepareObservations {

        private static final String OBSERVATION = """
            [{
              "practiceSlug": "explains-why",
              "summary": "States the motivation",
              "assessmentStatus": "ASSESSED", "presence": "PRESENT",
              "assessment": "GOOD",
              "severity": null,
              "evidenceRationale": "The text says why.",
              "evidence": {}
            }]
            """;

        @Test
        void shouldRefuseRatherThanFailWhenNothingSubmittedIsAnObservation() {
            var job = new AgentJob();
            job.setId(UUID.randomUUID());

            assertThatThrownBy(
                            () -> handler.prepareObservations(job, objectMapper.readTree("[{\"practiceSlug\": \"\"}]")))
                    .isInstanceOfSatisfying(
                            de.tum.cit.aet.hephaestus.agent.handler.spi.ObservationsRefusedException.class,
                            e -> assertThat(e.reasonCode()).isEqualTo("no_valid_observations"));
            org.mockito.Mockito.verifyNoInteractions(deliveryService);
        }

        @Test
        void shouldRecordThroughTheDeliveryServiceOnlyWhenAsked() {
            var job = new AgentJob();
            job.setId(UUID.randomUUID());
            var snapshot = EvidenceSnapshotFixtures.snapshot(objectMapper, ArtifactKinds.ISSUE.value());
            EvidenceSnapshotFixtures.admittedPractice(snapshot, "explains-why", 1);
            job.setEvidenceSnapshot(snapshot);
            var admissible = mock(PracticeDetectionDeliveryService.PreparedObservations.class);
            when(deliveryService.prepare(org.mockito.ArgumentMatchers.eq(job), any()))
                    .thenReturn(admissible);

            var prepared = handler.prepareObservations(job, objectMapper.readTree(OBSERVATION));
            org.mockito.Mockito.verify(deliveryService, org.mockito.Mockito.never())
                    .publish(any(), any());

            prepared.record(job);
            org.mockito.Mockito.verify(deliveryService).publish(job, admissible);
        }
    }
}
