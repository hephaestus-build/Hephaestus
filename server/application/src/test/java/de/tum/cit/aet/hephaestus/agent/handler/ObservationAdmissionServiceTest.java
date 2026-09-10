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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ObservationAdmissionServiceTest extends BaseUnitTest {

    private final AgentJobRepository jobs = mock(AgentJobRepository.class);
    private final ObservationRepository observations = mock(ObservationRepository.class);
    private final PullRequestReviewHandler pullRequests = mock(PullRequestReviewHandler.class);
    private final IssueReviewHandler issues = mock(IssueReviewHandler.class);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private ObservationAdmissionService service;
    private AgentJob job;

    @BeforeEach
    void setUp() {
        service = new ObservationAdmissionService(jobs, observations, pullRequests, issues, mapper);
        job = new AgentJob();
        job.setId(UUID.randomUUID());
        job.setStatus(AgentJobStatus.RUNNING);
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
        service.admit(job.getId(), mapper.createArrayNode());
        assertThat(ObservationAdmissionService.observationsWereRefused(job)).isFalse();
    }

    @Test
    void samePayloadReplaysWithoutAdmittingTwice() {
        ArrayNode payload = mapper.createArrayNode().add("one");
        ObjectNode first = service.admit(job.getId(), payload);
        ObjectNode replay = service.admit(job.getId(), payload);

        assertThat(replay.path("admissionDigest").asString())
                .isEqualTo(first.path("admissionDigest").asString());
        verify(pullRequests, times(1)).admitObservations(job, payload);
    }

    @Test
    void changedPayloadConflictsAfterAdmission() {
        service.admit(job.getId(), mapper.createArrayNode().add("one"));
        assertThatThrownBy(() ->
                        service.admit(job.getId(), mapper.createArrayNode().add("two")))
                .isInstanceOf(ObservationAdmissionService.AdmissionConflictException.class);
        verify(pullRequests, times(1)).admitObservations(eq(job), any());
    }

    @Test
    void refusesJobThatIsNotRunning() {
        job.setStatus(AgentJobStatus.COMPLETED);
        assertThatThrownBy(() ->
                        service.admit(job.getId(), mapper.createArrayNode().add("one")))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(pullRequests, issues);
    }

    @Test
    void dispatchesIssueAdmissionToIssueGuardPipeline() {
        job.setJobType(AgentJobType.ISSUE_REVIEW);
        ArrayNode payload = mapper.createArrayNode().add("one");
        service.admit(job.getId(), payload);
        verify(issues).admitObservations(job, payload);
        verifyNoInteractions(pullRequests);
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

        ObjectNode response =
                service.admit(job.getId(), mapper.createArrayNode().add("one"));

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
