package de.tum.cit.aet.hephaestus.agent.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import de.tum.cit.aet.hephaestus.agent.AgentJobType;
import de.tum.cit.aet.hephaestus.agent.job.AgentJob;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobRepository;
import de.tum.cit.aet.hephaestus.agent.job.AgentJobStatus;
import de.tum.cit.aet.hephaestus.practices.model.Observation;
import de.tum.cit.aet.hephaestus.practices.model.Practice;
import de.tum.cit.aet.hephaestus.practices.model.Presence;
import de.tum.cit.aet.hephaestus.practices.observation.ObservationRepository;
import de.tum.cit.aet.hephaestus.testconfig.BaseUnitTest;
import de.tum.cit.aet.hephaestus.workspace.Workspace;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ObservationAdmissionServiceTest extends BaseUnitTest {

    private final AgentJobRepository jobs = mock(AgentJobRepository.class);
    private final ObservationRepository observations = mock(ObservationRepository.class);
    private final PullRequestReviewHandler pullRequests = mock(PullRequestReviewHandler.class);
    private final IssueReviewHandler issues = mock(IssueReviewHandler.class);
    private final ConversationReviewHandler conversations = mock(ConversationReviewHandler.class);
    private final DocumentReviewHandler documents = mock(DocumentReviewHandler.class);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final tools.jackson.databind.JsonNode NO_FAILURES = mapper.createArrayNode();
    private final PracticeDetectionDeliveryService delivery = mock(PracticeDetectionDeliveryService.class);
    private ObservationAdmissionService service;
    private AgentJob job;
    private ObservationAdmissionService.AdmissionIdentity identity;

    @BeforeEach
    void setUp() {
        var transactionManager = mock(org.springframework.transaction.PlatformTransactionManager.class);
        lenient()
                .when(transactionManager.getTransaction(any()))
                .thenAnswer(invocation -> new org.springframework.transaction.support.SimpleTransactionStatus());
        service = new ObservationAdmissionService(
                jobs,
                observations,
                pullRequests,
                issues,
                conversations,
                documents,
                mapper,
                delivery,
                transactionManager);
        job = new AgentJob();
        job.setId(UUID.randomUUID());
        job.setStatus(AgentJobStatus.RUNNING);
        job.setWorkerId("worker-1");
        identity = new ObservationAdmissionService.AdmissionIdentity(job.getId(), 1L, 0, "worker-1");
        job.setJobType(AgentJobType.PULL_REQUEST_REVIEW);
        Workspace workspace = new Workspace();
        workspace.setId(1L);
        job.setWorkspace(workspace);
        job.setMetadata(mapper.createObjectNode());
        when(jobs.findByIdWithWorkspaceForUpdate(job.getId())).thenReturn(Optional.of(job));
        when(observations.findByAgentJobId(job.getId(), job.getWorkspace().getId()))
                .thenReturn(List.of());
    }

    @Test
    void shouldClearPreviousRefusalWhenObservationsAreAdmitted() {
        ObjectNode metadata = mapper.createObjectNode();
        metadata.putObject(ObservationAdmissionService.REFUSAL_METADATA_KEY).put("reasonCode", "no_valid_observations");
        job.setMetadata(metadata);
        assertThat(ObservationAdmissionService.observationsWereRefused(job)).isTrue();
        service.admit(identity, mapper.createArrayNode());
        assertThat(ObservationAdmissionService.observationsWereRefused(job)).isFalse();
    }

    @Test
    void samePayloadReplaysWithoutAdmittingTwice() {
        ArrayNode payload = mapper.createArrayNode().add("one");
        ObjectNode first = service.admit(identity, payload);
        ObjectNode replay = service.admit(identity, payload);

        assertThat(replay.path("admissionDigest").asString())
                .isEqualTo(first.path("admissionDigest").asString());
        verify(pullRequests, times(1)).prepareObservations(job, payload);
    }

    @Test
    void changedPayloadConflictsAfterAdmission() {
        service.admit(identity, mapper.createArrayNode().add("one"));
        assertThatThrownBy(
                        () -> service.admit(identity, mapper.createArrayNode().add("two")))
                .isInstanceOf(ObservationAdmissionService.AdmissionConflictException.class);
        verify(pullRequests, times(1)).prepareObservations(eq(job), any());
    }

    @Test
    void refusesJobThatIsNotRunning() {
        job.setStatus(AgentJobStatus.COMPLETED);
        assertThatThrownBy(
                        () -> service.admit(identity, mapper.createArrayNode().add("one")))
                .isInstanceOf(ObservationAdmissionService.StaleAttemptException.class);
        verifyNoInteractions(pullRequests, issues);
    }

    @ParameterizedTest
    @ValueSource(strings = {"attempt", "worker", "workspace", "cancelled", "missing"})
    void shouldRejectAdmissionAndRefusalWhenAuthenticatedOwnershipIsStale(String mismatch) {
        switch (mismatch) {
            case "attempt" -> job.setRetryCount(1);
            case "worker" -> job.setWorkerId("worker-2");
            case "workspace" -> job.getWorkspace().setId(2L);
            case "cancelled" -> job.setStatus(AgentJobStatus.CANCELLED);
            case "missing" ->
                when(jobs.findByIdWithWorkspaceForUpdate(job.getId())).thenReturn(Optional.empty());
            default -> throw new AssertionError(mismatch);
        }

        assertThatThrownBy(() -> service.admit(identity, mapper.createArrayNode()))
                .isInstanceOf(ObservationAdmissionService.StaleAttemptException.class);
        assertThatThrownBy(() -> service.recordRefusal(identity, "no_valid_observations", "Refused", NO_FAILURES))
                .isInstanceOf(ObservationAdmissionService.StaleAttemptException.class);

        verifyNoInteractions(pullRequests, issues);
        verify(jobs, never()).save(any());
        assertThat(job.getMetadata()).isEqualTo(mapper.createObjectNode());
    }

    @Test
    void shouldPersistRefusalWhenAttemptStillOwnsRunningJob() {
        service.recordRefusal(identity, "no_valid_observations", "Refused", NO_FAILURES);
        assertThat(Objects.requireNonNull(job.getMetadata())
                        .path(ObservationAdmissionService.REFUSAL_METADATA_KEY)
                        .path("reasonCode")
                        .asString())
                .isEqualTo("no_valid_observations");
        verify(jobs).save(job);
    }

    @Test
    void shouldRejectDelayedRefusalWhenAnotherSubmissionWasAdmitted() {
        service.admit(identity, mapper.createArrayNode());
        assertThatThrownBy(() -> service.recordRefusal(identity, "no_valid_observations", "Refused", NO_FAILURES))
                .isInstanceOf(ObservationAdmissionService.AdmissionConflictException.class);
        assertThat(ObservationAdmissionService.observationsWereRefused(job)).isFalse();
        verify(jobs, times(1)).save(job);
    }

    @Test
    void dispatchesIssueAdmissionToIssueGuardPipeline() {
        job.setJobType(AgentJobType.ISSUE_REVIEW);
        ArrayNode payload = mapper.createArrayNode().add("one");
        service.admit(identity, payload);
        verify(issues).prepareObservations(job, payload);
        verifyNoInteractions(pullRequests);
    }

    @Test
    void shouldRejectPublicationWhenOwnershipChangesDuringVerification() {
        when(pullRequests.prepareObservations(eq(job), any())).thenAnswer(invocation -> {
            job.setRetryCount(1);
            return mock(PracticeDetectionDeliveryService.PreparedObservations.class);
        });
        assertThatThrownBy(() -> service.admit(identity, mapper.createArrayNode()))
                .isInstanceOf(ObservationAdmissionService.StaleAttemptException.class);
        verifyNoInteractions(delivery);
        verify(jobs, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"CONVERSATION_REVIEW", "DOCUMENT_REVIEW"})
    void shouldUseFencedAdmissionForRepoLessReviews(String type) {
        job.setJobType(AgentJobType.valueOf(type));
        ArrayNode payload = mapper.createArrayNode().add("observation");
        service.admit(identity, payload);
        if (job.getJobType() == AgentJobType.CONVERSATION_REVIEW)
            verify(conversations).prepareObservations(job, payload);
        else verify(documents).prepareObservations(job, payload);
        verify(delivery).publish(eq(job), any());
        assertThat(Objects.requireNonNull(job.getMetadata())
                        .path(ObservationAdmissionService.DIGEST_METADATA_KEY)
                        .asString())
                .isNotBlank();
    }

    @Test
    void responseCarriesDurableIdentityAndFullEvidence() {
        job.setJobType(AgentJobType.ISSUE_REVIEW);
        Observation observation = mock(Observation.class);
        Practice practice = mock(Practice.class);
        ObjectNode evidence = mapper.createObjectNode();
        evidence.putArray("citations")
                .addObject()
                .put("sourceKind", "scm.issue.core")
                .put("quote", "why");
        when(observation.getId()).thenReturn(UUID.randomUUID());
        when(observation.getPractice()).thenReturn(practice);
        when(practice.getSlug()).thenReturn("explains-why");
        when(observation.getSummary()).thenReturn("Explains the motivation");
        when(observation.getPresence()).thenReturn(Presence.PRESENT);
        when(observation.getEvidence()).thenReturn(evidence);
        when(observation.getEvidenceRationale()).thenReturn("The issue states why.");
        when(observations.findByAgentJobId(job.getId(), job.getWorkspace().getId()))
                .thenReturn(List.of(observation));

        ObjectNode response = service.admit(identity, mapper.createArrayNode().add("one"));

        assertThat(response.path("observations").get(0).path("id").asString()).isNotBlank();
        assertThat(response.path("observations").get(0).path("evidence").path("citations"))
                .hasSize(1);
        assertThat(response.path("observations")
                        .get(0)
                        .path("citations")
                        .get(0)
                        .path("quote")
                        .asString())
                .isEqualTo("why");
        assertThat(response.path("observations")
                        .get(0)
                        .path("citations")
                        .get(0)
                        .path("anchorable")
                        .asBoolean())
                .isFalse();
    }
}
